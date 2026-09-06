"""Artificial records test validation only; these fixtures are never research results."""
import copy
import csv
import json
from pathlib import Path

import pytest

from evidence import digest, file_digest, validate_block
from paper_common import write_json
from prepare_evolution import prepare


@pytest.fixture
def evidence(tmp_path):
    backends = {"raw": "RAW_RECOMPUTE", "aether": "AETHER_CACHE", "mmap": "STATIC_PREPROCESSED_MMAP"}
    condition = {"conditionId": "fixture", "samples": 2}
    env = {"measurementIdentity": {"fixture": True}, "sourceSha256": {"test": "fixture"}}
    env_id = digest(env["measurementIdentity"])
    protocol = {"conditions": [condition], "repeats": 2, "seedBase": 10, "backends": backends,
                "sourceSha256": env["sourceSha256"]}
    write_json(tmp_path / "protocol.json", protocol)
    write_json(tmp_path / f"environment-{env_id}.json", env)
    root = tmp_path / "fixture/block-0000"
    root.mkdir(parents=True)
    for name in ("v1.csv", "v2.csv"):
        (root / name).write_text("fixture\n")
    dynamics = {"invariants": {"passed": True}, "prepopulatedEntries": 1}
    run = {"modelParityPassed": True, "engineInfo": {"durability": "DURABLE"},
           "cacheDynamics": dynamics, "mmapDynamics": dynamics, "backendOrder": list(backends.values()),
           "backends": {key: {"steadyState": {"effectiveSamplesPerSecond": 10}} for key in backends.values()}}
    write_json(root / "training.json", {"storageEngine": "java", "status": "PASSED", "allPassed": True,
        "accelerator": {"backend": "cuda", "deviceAvailable": True},
        "correctness": {"allChecksumsEqual": True}, "runs": [run]})
    block = {"schema": "aether-paper-block-v1", "status": "PASSED", "correctnessPassed": True,
             "protocolHash": digest(protocol), "condition": condition, "conditionId": "fixture", "blockIndex": 0,
             "seed": 10, "environmentId": env_id, "initialReusableEntries": 1,
             "aetherDynamics": dynamics, "mmapDynamics": dynamics, "backendOrder": list(backends.values()),
             "throughput": {key: 10 for key in backends},
             "trainingReportSha256": file_digest(root / "training.json"),
             "v1ManifestSha256": file_digest(root / "v1.csv"), "v2ManifestSha256": file_digest(root / "v2.csv")}
    write_json(root / "block.json", block)
    return root / "block.json"


def test_complete_evidence(evidence):
    assert validate_block(evidence)["blockIndex"] == 0


@pytest.mark.parametrize("relative", ["training.json", "v1.csv", "v2.csv"])
def test_altered_files_rejected(evidence, relative):
    (evidence.parent / relative).write_text("altered")
    with pytest.raises(ValueError, match="altered"):
        validate_block(evidence)


@pytest.mark.parametrize("field,value", [("throughput", {"raw": 99}), ("seed", 11), ("initialReusableEntries", 0)])
def test_altered_summary_rejected(evidence, field, value):
    block = json.loads(evidence.read_text())
    block[field] = value
    write_json(evidence, block)
    with pytest.raises(ValueError):
        validate_block(evidence)


def test_cpu_results_cannot_become_gpu_evidence(evidence):
    training = evidence.parent / "training.json"
    report = json.loads(training.read_text())
    report["accelerator"]["deviceAvailable"] = False
    write_json(training, report)
    block = json.loads(evidence.read_text())
    block["trainingReportSha256"] = file_digest(training)
    write_json(evidence, block)
    with pytest.raises(ValueError, match="CUDA"):
        validate_block(evidence)


def test_changed_protocol_rejected(evidence):
    path = evidence.parent.parent.parent / "protocol.json"
    report = json.loads(path.read_text())
    report["repeats"] = 24
    write_json(path, report)
    with pytest.raises(ValueError, match="protocol"):
        validate_block(evidence)


def test_evolution_is_reproducible_and_uses_distinct_real_rows(tmp_path):
    source = tmp_path / "source.csv"
    source.write_text("sample_id,image_path,split\n" + "".join(f"s{i},images/{i}.png,train\n" for i in range(12)))
    for name in ("a", "b"):
        prepare(source, tmp_path / name, v1_size=7, v2_size=9, reusable=4, seed=74)
    assert (tmp_path / "a/v1.csv").read_bytes() == (tmp_path / "b/v1.csv").read_bytes()
    assert (tmp_path / "a/v2.csv").read_bytes() == (tmp_path / "b/v2.csv").read_bytes()
    def ids(name):
        with (tmp_path / "a" / name).open() as stream:
            rows = list(csv.DictReader(stream))
        assert all(Path(row["image_path"]).is_absolute() for row in rows)
        return {row["sample_id"] for row in rows}
    assert len(ids("v1.csv") & ids("v2.csv")) == 4
    with pytest.raises(ValueError, match="not enough"):
        prepare(source, tmp_path / "oversized", v1_size=9, v2_size=10, reusable=1)


def test_existing_evolution_is_not_partially_overwritten(tmp_path):
    source = tmp_path / "source.csv"
    source.write_text("sample_id,split\na,train\nb,train\n")
    output = tmp_path / "output"
    output.mkdir()
    (output / "v2.csv").write_text("keep")
    with pytest.raises(FileExistsError):
        prepare(source, output, v1_size=1, v2_size=1, reusable=0)
    assert not (output / "v1.csv").exists()
    assert (output / "v2.csv").read_text() == "keep"
