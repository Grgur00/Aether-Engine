"""Evidence for the separate filesystem provenance prototype's documented limits."""
from dataclasses import asdict, replace
import importlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def prototype(monkeypatch):
    monkeypatch.syspath_prepend(str(ROOT / "clients/python"))
    return importlib.import_module("aether_training_cache.ml")


@pytest.fixture
def store(prototype, tmp_path):
    return prototype.AetherMLStore(tmp_path / "store", code_commit="contract-test")


def publish(store, key="key", data=b"abc", parents=()):
    return store.commit_bytes(data, cache_key=key, source_artifact_ids=parents,
                              transformation_name="fixture", transformation_version="1")


def test_cached_none_recomputes_but_other_falsy_values_hit(store):
    calls = []
    transform = store.cached_transform_function(lambda value: calls.append(value), version="1", name="none")
    assert transform(1) is None
    assert transform(1) is None
    assert calls == [1, 1]
    false_calls = []
    false_transform = store.cached_transform_function(lambda value: false_calls.append(value) or False,
                                                      version="1", name="false")
    assert false_transform(1) is False
    assert false_transform(1) is False
    assert false_calls == [1]


def test_explicit_parents_do_not_change_key_or_merge_hit_lineage(store):
    transform = store.cached_transform_function(lambda value: value + 1, version="1", name="add")
    key = transform.aether_cache_key(1)
    _, first = transform.apply(1, source_artifact_ids=["aether:parent-a"])
    _, hit = transform.apply(1, source_artifact_ids=["aether:parent-b"])
    assert transform.aether_cache_key(1) == key
    assert first.artifact_id == hit.artifact_id
    assert hit.source_artifact_ids == ["aether:parent-a"]


def test_metadata_normalization_is_top_level_only_and_original_function_input_is_retained(store):
    metadata = publish(store)
    normalized, parent = store._normalize_input(metadata)
    assert normalized == {"artifact_id": metadata.artifact_id}
    assert parent == metadata.artifact_id
    assert store._hash_inputs([metadata], {})[1] == [metadata.artifact_id]
    assert store._hash_inputs([[metadata]], {})[1] == []
    received = []
    transform = store.cached_transform_function(lambda value: received.append(value) or "ok", version="1", name="original")
    assert transform(metadata) == "ok"
    assert received[0] is metadata


def test_same_node_reuses_disk_metadata_but_returns_fresh_receipt(store, monkeypatch, prototype):
    times = iter([10, 20])
    monkeypatch.setattr(prototype.time, "time", lambda: next(times))
    first = publish(store, parents=["aether:first-parent"])
    second = publish(store, parents=["aether:second-parent"])
    assert first.artifact_id == second.artifact_id
    assert (first.created_at, second.created_at) == (10, 20)
    assert second.source_artifact_ids == ["aether:second-parent"]
    persisted = store.artifact_metadata(second.artifact_id)
    assert persisted.created_at == 10
    assert persisted.source_artifact_ids == ["aether:first-parent"]


def test_same_key_can_replace_pointer_with_different_content_and_keep_old_node(store):
    first = publish(store, data=b"first")
    second = publish(store, data=b"second")
    assert first.artifact_id != second.artifact_id
    assert store.cached_artifact_id("key") == second.artifact_id
    assert store.load_artifact_bytes(first.artifact_id) == b"first"
    assert store.load_artifact_bytes(second.artifact_id) == b"second"
    assert not store.validate().orphan_artifact_files


def test_recommit_does_not_heal_existing_corrupt_content_and_batch_cache_read_skips_it(store):
    metadata = publish(store)
    store.artifact_path(metadata.artifact_id).write_bytes(b"bad")
    publish(store)
    with pytest.raises(IOError, match="checksum"):
        store.load_artifact_bytes(metadata.artifact_id)
    assert store.load_cached_bytes_many(["key"]) == {}
    assert store.validate().checksum_mismatches == [metadata.artifact_id]


def test_batch_commit_failure_keeps_prior_publications_and_records_failure_timer(store):
    entries = [{"cache_key": "first", "data": b"one"}, {"cache_key": "bad"}]
    with pytest.raises(KeyError, match="data"):
        store.commit_bytes_many(entries, transformation_name="fixture", transformation_version="1")
    assert store.load_cached_bytes_many(["first"]) == {"first": b"one"}
    assert store.cached_artifact_id("bad") is None
    metrics = store.operation_metrics()
    assert metrics["artifact_commit_batch"]["count"] == 1
    assert metrics["artifact_commit"]["count"] == 0


@pytest.mark.parametrize("failure_stage", ["metadata", "cache"])
def test_commit_sequence_can_leave_blob_or_node_without_cache_pointer(store, monkeypatch, failure_stage):
    write = store._atomic_write_json

    def fail(path, value):
        if path.parent == (store.metadata_dir if failure_stage == "metadata" else store.cache_dir):
            raise OSError("injected write failure")
        write(path, value)

    monkeypatch.setattr(store, "_atomic_write_json", fail)
    with pytest.raises(OSError, match="injected"):
        publish(store)
    assert store.cached_artifact_id("key") is None
    assert len(list(store.artifacts_dir.rglob("*"))) >= 3
    report = store.validate()
    assert bool(report.orphan_artifact_files) is (failure_stage == "metadata")
    assert len(list(store.metadata_dir.glob("*.json"))) == (0 if failure_stage == "metadata" else 1)
    assert store.operation_metrics()["artifact_commit"]["count"] == 1


def test_atomic_file_helper_fsyncs_file_then_replaces_without_directory_sync(store, monkeypatch, prototype):
    events = []
    original = prototype.os.replace

    def replace_file(source, destination):
        events.append("replace")
        original(source, destination)

    monkeypatch.setattr(prototype.os, "fsync", lambda descriptor: events.append("fsync"))
    monkeypatch.setattr(prototype.os, "replace", replace_file)
    destination = store.root / "one.json"
    store._atomic_write_bytes(destination, b"one")
    assert destination.read_bytes() == b"one"
    assert events == ["fsync", "replace"]
    assert list(store.tmp_dir.iterdir()) == []


def test_direct_cache_fields_can_read_without_node_and_disagree_with_node_checks(store):
    metadata = publish(store)
    entry_path = store._cache_path("key")
    entry = json.loads(entry_path.read_text())
    entry["size"] = 999
    entry_path.write_text(json.dumps(entry))
    assert store.validate().is_consistent
    store._metadata_path(metadata.artifact_id).unlink()
    assert store.cached_artifact_ids(["key"]) == {"key": metadata.artifact_id}
    assert store.load_cached_bytes_many(["key"]) == {"key": b"abc"}
    assert store.load_cached("key") is None


def test_snapshot_direct_verification_can_pass_with_missing_parent_and_compare_ignores_order(store):
    metadata = publish(store, parents=["aether:missing-parent"])
    first = store.create_snapshot("first", [metadata.artifact_id, metadata.artifact_id], {"split": "a"})
    second = store.create_snapshot("second", [metadata.artifact_id], {"split": "b"})
    verification = store.verify_snapshot(first.snapshot_id)
    assert verification.is_reproducible
    assert verification.verified_artifact_ids == [metadata.artifact_id] * 2
    assert store.validate().missing_parent_artifacts == [metadata.artifact_id + "->aether:missing-parent"]
    with pytest.raises(KeyError, match="unknown artifact"):
        store.snapshot_manifest(first.snapshot_id)
    assert len(store.snapshot_manifest(first.snapshot_id, include_lineage=False)["artifacts"]) == 1
    assert store.compare_snapshots(first.snapshot_id, second.snapshot_id) == {
        "added": [], "removed": [], "unchanged": [metadata.artifact_id]}


def test_snapshot_creation_does_not_preflight_members_and_empty_snapshot_passes(store):
    missing = store.create_snapshot("missing", ["aether:absent"])
    assert store.verify_snapshot(missing.snapshot_id).missing_metadata == ["aether:absent"]
    assert store.verify_snapshot(store.create_snapshot("empty", []).snapshot_id).is_reproducible
    with pytest.raises(KeyError):
        store.restore_snapshot(missing.snapshot_id)


def test_lineage_is_reflexive_and_terminates_cycles_without_reporting_them(store):
    metadata = publish(store)
    cyclic = replace(metadata, source_artifact_ids=[metadata.artifact_id])
    store._metadata_path(metadata.artifact_id).write_text(json.dumps(asdict(cyclic)))
    assert [node.artifact_id for node in store.lineage(metadata.artifact_id)] == [metadata.artifact_id]
    assert store.depends_on(metadata.artifact_id, metadata.artifact_id)
    assert store.validate().is_consistent


def test_missing_child_payload_skips_parent_diagnostics(store):
    metadata = publish(store, parents=["aether:absent"])
    store.artifact_path(metadata.artifact_id).unlink()
    report = store.validate()
    assert report.missing_artifacts == [metadata.artifact_id]
    assert report.missing_parent_artifacts == []


def test_recover_returns_original_report_and_leaves_orphans_by_default(store):
    orphan = store.artifacts_dir / "orphan"
    orphan.write_bytes(b"unreferenced")
    (store.tmp_dir / "temporary").write_bytes(b"temp")
    store._cache_path("broken").write_text("{")
    report = store.recover()
    assert report.temporary_files == ["tmp/temporary"]
    assert report.corrupt_cache_entries == ["broken.json"]
    assert not report.is_consistent
    assert store.validate().temporary_files == []
    assert orphan.exists()
    store.recover(remove_orphan_artifacts=True)
    assert store.validate().is_consistent


def test_validation_can_raise_on_malformed_metadata_instead_of_returning_report(store):
    (store.metadata_dir / "bad.json").write_text("{")
    with pytest.raises(json.JSONDecodeError):
        store.validate()


def test_experiment_write_overwrites_and_does_not_validate_missing_snapshot(store, prototype):
    first = prototype.ExperimentRecord("experiment", 99, "first", {})
    second = replace(first, model="second", output_artifacts=["aether:missing"])
    store.record_experiment(first)
    store.record_experiment(second)
    assert json.loads((store.experiments_dir / "experiment.json").read_text())["model"] == "second"
    assert store.validate().missing_experiment_snapshots == ["experiment->99"]
    with pytest.raises(KeyError, match="unknown snapshot"):
        store.experiments_using("aether:missing")


def test_context_exit_is_not_close_or_rollback_and_path_helpers_do_not_constrain_ids(store):
    with store as owner:
        assert owner is store
        publish(store)
    assert store.load_cached_bytes_many(["key"]) == {"key": b"abc"}
    assert store._cache_path("../outside").resolve().parent != store.cache_dir.resolve()
    assert store._metadata_path("aether:../outside").resolve().parent != store.metadata_dir.resolve()


def test_loader_factory_selects_fallback_or_torch_based_on_extra_options(prototype, monkeypatch):
    fallback = prototype.AetherDataLoader([1, 2, 3], batch_size=2)
    assert isinstance(fallback, prototype._SequentialAetherDataLoader)
    assert not isinstance(fallback, prototype.AetherDataLoader)
    assert list(fallback) == [[1, 2], [3]]
    recorded = []

    def torch_loader(dataset, **settings):
        recorded.append((dataset, settings))
        return "framework-loader"

    monkeypatch.setitem(sys.modules, "torch.utils.data", SimpleNamespace(DataLoader=torch_loader))
    assert prototype.AetherDataLoader([1], pin_memory=False) == "framework-loader"
    assert recorded[0][1]["pin_memory"] is False
    assert recorded[0][1]["num_workers"] == 0


def test_storage_ratios_are_resident_file_sizes_and_content_is_deduplicated(store):
    publish(store, key="first")
    publish(store, key="second")
    metrics = store.storage_metrics(raw_dataset_bytes=10)
    assert metrics["logicalArtifactBytes"] == 6
    assert metrics["cachedArtifactBytes"] == 3
    assert metrics["writeAmplification"] == metrics["totalStorageBytes"] / 10
    assert metrics["metadataBytes"] >= metrics["cacheIndexBytes"]
    assert metrics["walBytes"] == metrics["compactionWriteBytes"] == 0
    assert store.storage_metrics()["writeAmplification"] is None


def test_latency_summary_uses_normal_interval_and_rank_not_h2_analysis(prototype):
    assert prototype._latency_distribution([])["count"] == 0
    assert prototype._latency_distribution([10])["confidence95"] == {"low": 10, "high": 10, "margin": 0}
    values = list(range(1, 21))
    summary = prototype._latency_distribution(values)
    assert (summary["p50"], summary["p95"], summary["p99"]) == (10, 19, 20)
    expected_margin = 1.96 * summary["standardDeviation"] / (20 ** 0.5)
    assert summary["confidence95"]["margin"] == expected_margin
    with pytest.raises(IndexError):
        prototype._percentile([], 95)


def test_environment_commit_precedence_is_descriptive_not_store_source_validation(prototype, monkeypatch, tmp_path):
    for name in ("_gpu_report", "_ram_report"):
        monkeypatch.setattr(prototype, name, lambda: {"fixture": True})
    monkeypatch.setattr(prototype, "_disk_report", lambda root: {"root": str(root)})
    monkeypatch.setattr(prototype, "_java_version", lambda: "fixture-java")
    monkeypatch.setattr(prototype, "_pytorch_version", lambda: "fixture-torch")
    monkeypatch.setattr(prototype, "_git_commit", lambda root: "fixture-git")
    monkeypatch.setenv("AETHER_CODE_COMMIT", "environment-commit")
    assert prototype.environment_report(root=tmp_path)["gitCommit"] == "environment-commit"
    assert prototype.environment_report(root=tmp_path, code_commit="explicit")["gitCommit"] == "explicit"
    monkeypatch.delenv("AETHER_CODE_COMMIT")
    assert prototype.environment_report(root=tmp_path)["gitCommit"] == "fixture-git"
    assert prototype.AetherMLStore(tmp_path / "different").code_commit == "unknown"
