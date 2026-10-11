import copy
import json
import shutil

import pytest

from h4.analyze_h4 import load_campaigns, run, summarize
from h4.backend_worker import run_backend
from h4.common import write_json
from h4.h4_runner import archive_checksums, host_class_hash
from h4.inputs import bind_inputs
from h4.prepare_pcam_manifest import canonical_hash, prepare
from test_h4_pcam import fixture_source
from test_h4_runner import host_fixture


def archive_fixture(tmp_path, monkeypatch):
    """Pipeline fixture: real mmap PCam-fixture reads; simulated paired backend records."""
    data = tmp_path / "data"
    data.mkdir()
    protocol = fixture_source(data, monkeypatch)
    prepared = tmp_path / "prepared"
    prepare(data, prepared, protocol)
    binding = bind_inputs(data, prepared, protocol)
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    record = run_backend(dict(backend="mmap", condition=4, data=str(data), scratch=str(scratch)), binding)
    assert record["passed"]
    directory = tmp_path / "campaign"
    directory.mkdir()
    files = {"unit-fixture-only": "0" * 64}
    source_hash = canonical_hash(files)
    facts = host_fixture()
    host_hash = host_class_hash(facts)
    identity = {key: binding[key] for key in ("protocol_sha256", "manifest_sha256", "dataset_sha256")}
    identity.update(source_sha256=source_hash, host_class_sha256=host_hash)
    plan = dict(identity, schema="aether-h4-pilot-plan-v1", phase="exploratory", design_sha256=None,
                conditions=[4], blocks=[0, 1], ordering={"0": ["aether", "mmap"], "1": ["mmap", "aether"]})
    write_json(directory / "plan.json", plan)
    write_json(directory / "source.json", dict(sha256=source_hash, files=files))
    write_json(directory / "session-fixture.json", dict(sha256=host_hash, facts=facts))
    for block_id in (0, 1):
        block_dir = directory / f"condition-4-block-{block_id}"
        block_dir.mkdir()
        aether = copy.deepcopy(record)
        aether["backend"] = "aether"  # Simulated schema fixture, never a real Aether measurement.
        block = dict(identity, schema="aether-h4-paired-block-v1", phase="exploratory", design_sha256=None,
                     passed=True, primary_h4_eligible=False, condition=4, block_id=block_id,
                     session_id="fixture", backend_order=plan["ordering"][str(block_id)],
                     results=dict(aether=aether, mmap=record))
        write_json(block_dir / "block.json", block)
        archive_checksums(block_dir)
    archive_checksums(directory)
    return directory, prepared, protocol


def test_archived_analysis_pairs_blocks_not_passes_and_reports_missing_io(tmp_path, monkeypatch):
    directory, prepared, protocol = archive_fixture(tmp_path, monkeypatch)
    summary = run([directory], prepared, protocol, tmp_path / "analysis")
    assert summary["phase"] == "exploratory"
    assert not summary["primary_h4_eligible"]
    assert summary["confirmatory_test"] is None
    assert len(summary["observations"]) == 8
    for condition in summary["conditions"]:
        assert condition["paired_statistics"]["paired_blocks"] == 2
        assert condition["paired_statistics"]["geometric_mean_ratio"] == 1
        assert condition["out_of_core_qualified_pairs"] == 0
        assert condition["backends"]["aether"]["resources"]["physical_read_bytes"]["mean"] is None
    assert (tmp_path / "analysis/paired-observations.csv").exists()


def test_duplicate_archives_and_corrupt_raw_records_are_rejected(tmp_path, monkeypatch):
    directory, prepared, protocol = archive_fixture(tmp_path, monkeypatch)
    duplicate = tmp_path / "duplicate"
    shutil.copytree(directory, duplicate)
    with pytest.raises(ValueError, match="duplicate"):
        load_campaigns([directory, duplicate], prepared, protocol)
    block_path = directory / "condition-4-block-0/block.json"
    block_path.write_text("modified")
    with pytest.raises(ValueError, match="inventory"):
        load_campaigns([directory], prepared, protocol)


def test_failed_pair_is_explicitly_excluded_and_not_counted_as_a_valid_block(tmp_path, monkeypatch):
    directory, prepared, protocol = archive_fixture(tmp_path, monkeypatch)
    block_dir = directory / "condition-4-block-1"
    block = json.loads((block_dir / "block.json").read_text())
    block.update(passed=False, error="simulated failure")
    write_json(block_dir / "block.json", block)
    archive_checksums(block_dir)
    archive_checksums(directory)
    summary = summarize(load_campaigns([directory], prepared, protocol))
    assert len(summary["excluded_blocks"]) == 1
    assert summary["excluded_blocks"][0]["reason"] == "simulated failure"
    assert all(cell["paired_statistics"]["paired_blocks"] == 1 for cell in summary["conditions"])
