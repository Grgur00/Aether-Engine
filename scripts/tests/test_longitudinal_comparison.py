import csv
import hashlib
import json
import os
import subprocess
import sys
from collections import Counter

import pytest

from longitudinal_manifests import COUNTS, prepare, verify, read_rows, validate_versions
from longitudinal_state import (BACKENDS, backend_order, cumulative, execute_stage, check_capacity,
                                inventory, load_receipt)


def manifest_fixture(path, count=1505):
    rows = []
    for i in range(count):
        a, b = hashlib.sha256(str(i).encode()).hexdigest(), hashlib.sha256(f"mask{i}".encode()).hexdigest()
        rows.append(dict(sample_id=str(i), source_identity=hashlib.sha256(f"{a}:{b}".encode()).hexdigest(),
                         image_sha256=a, mask_sha256=b, image_path=f"{i}.png", mask_path=f"{i}-mask.png", split="train"))
    with path.open("w", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=rows[0])
        writer.writeheader()
        writer.writerows(rows)


def test_nested_manifest_generation(tmp_path):
    source = tmp_path / "pool.csv"
    manifest_fixture(source)
    a = prepare(source, tmp_path / "a")
    b = prepare(source, tmp_path / "b")
    c = prepare(source, tmp_path / "c", seed=42)
    assert a == b
    assert a["manifestSha256"] != c["manifestSha256"]
    assert a["newCounts"] == [1200, 60, 63, 66, 69]
    assert a["unused"] == 47
    _, paths = verify(tmp_path / "a")
    rows = [read_rows(p)[1] for p in paths]
    validate_versions(rows, COUNTS)
    rows[1][0] = {**rows[1][0], "mask_sha256": "changed"}
    with pytest.raises(ValueError, match="prefixes"):
        validate_versions(rows, COUNTS)
    assert sum(COUNTS[1:]) * 20 == 108600


def test_manifest_duplicates_rejected(tmp_path):
    source = tmp_path / "pool.csv"
    manifest_fixture(source, 10)
    with source.open("a") as stream:
        stream.write(source.read_text().splitlines()[1] + "\n")
    with pytest.raises(ValueError, match="duplicate"):
        prepare(source, tmp_path / "bad", counts=[4, 5])


def test_balanced_order():
    for stage in range(5):
        orders = [backend_order(block, stage, 20260926) for block in range(5)]
        for position in range(4):
            counts = Counter(order[position] for order in orders)
            assert sorted(counts.values()) == [1, 1, 1, 2]
        assert orders == [backend_order(block, stage, 20260926) for block in range(5)]


def test_capacity_fails_before_work(tmp_path):
    with pytest.raises(RuntimeError, match="insufficient"):
        check_capacity(tmp_path, 100, free=99)


def test_lifecycle_and_crash_resume(tmp_path):
    calls = []
    counts = [4, 5, 6, 7, 8]
    identity = {"protocolHash": "protocol", "sourceHash": "source"}
    scratch, reports = tmp_path / "scratch", tmp_path / "reports"
    reports.mkdir()
    def worker(backend, stage, live):
        items = live / "items.json"
        previous = json.loads(items.read_text()) if items.exists() else []
        assert previous == list(range(counts[stage - 1])) if stage else not previous
        calls.append((backend, stage))
        items.write_text(json.dumps(list(range(counts[stage]))))
        return {"preprocessCalls": counts[stage] - len(previous), "timingsMs": {"work": 10.0}, "fullLifecycleMs": 10.0}
    for stage in range(2):
        for backend in BACKENDS:
            execute_stage(scratch, reports, backend, stage, identity, worker)
    execute_stage(scratch, reports, "aether", 2, identity, worker)
    code = '''
import os, sys
from pathlib import Path
from longitudinal_state import execute_stage
def crash(backend, stage, live):
    (live / "items.json").write_text("untrusted partial state")
    os._exit(7)
execute_stage(Path(sys.argv[1]), Path(sys.argv[2]), "mmap", 2,
              {"protocolHash": "protocol", "sourceHash": "source"}, crash)
'''
    result = subprocess.run([sys.executable, "-c", code, str(scratch), str(reports)])
    assert result.returncode == 7
    before = len(calls)
    for stage in range(5):
        for backend in BACKENDS:
            execute_stage(scratch, reports, backend, stage, identity, worker)
    assert len(calls) == 20 and len(calls) > before
    for backend in BACKENDS:
        stages = [load_receipt(reports / f"v{i}-{backend}.json", identity) for i in range(5)]
        assert [s["preprocessCalls"] for s in stages] == [4, 1, 1, 1, 1]
        assert cumulative(stages) == {f"V{i}": 10 * (i + 1) for i in range(5)}
        assert inventory(scratch / backend / "live") == stages[-1]["storeInventory"]
    receipt = reports / "v4-aether.json"
    value = json.loads(receipt.read_text())
    value["payload"]["fullLifecycleMs"] = 0
    receipt.write_text(json.dumps(value))
    with pytest.raises(ValueError, match="checksum"):
        load_receipt(receipt, identity)


def test_changed_checkpoint_fails_closed(tmp_path):
    reports, scratch = tmp_path / "reports", tmp_path / "scratch"
    reports.mkdir()
    def worker(backend, stage, live):
        (live / "data").write_bytes(b"valid")
        return {"timingsMs": {"work": 1}, "fullLifecycleMs": 1}
    execute_stage(scratch, reports, "mmap", 0, {"source": "one"}, worker)
    (scratch / "mmap/checkpoint-v0/data").write_bytes(b"corruption")
    with pytest.raises(ValueError, match="checkpoint changed"):
        execute_stage(scratch, reports, "mmap", 1, {"source": "one"}, worker)
    with pytest.raises(ValueError, match="provenance"):
        execute_stage(scratch, reports, "mmap", 0, {"source": "two"}, worker)


def test_no_double_counting():
    from longitudinal_analyze import analyze
    result = {"stages": [{"timingsMs": {"startup": 2, "work": 8}, "fullLifecycleMs": 10} for _ in range(5)]}
    result["cumulativeMs"] = cumulative(result["stages"])
    block = {"backendResults": {name: result for name in BACKENDS}}
    summary = analyze([block, block], 100)
    for values in summary["comparisons"].values():
        assert values["cumulative"]["V4"]["geometricMeanRatio"] == 1
        assert values["includingCommonIdentification"]["V4"]["geometricMeanRatio"] == 1
    result["stages"][0]["fullLifecycleMs"] = 12
    with pytest.raises(ValueError, match="accounting"):
        cumulative(result["stages"])


def test_live_orphan_prevents_recovery(tmp_path):
    reports = tmp_path / "reports"
    reports.mkdir()
    (reports / "v0-aether.lease.json").write_text(json.dumps({"pids": [os.getpid()]}))
    with pytest.raises(RuntimeError, match="still alive"):
        execute_stage(tmp_path / "scratch", reports, "aether", 0, {}, lambda *a: None)


def test_training_request_accounting_rejects_missing_sample():
    from longitudinal_comparison import validate_stage
    report = {"preprocessCalls": 60, "trainingCacheMisses": 0, "uniqueArtifacts": 1260,
              "reusedSamples": 1200, "trainingSampleRequests": 25199, "epochs": []}
    with pytest.raises(ValueError, match="accounting"):
        validate_stage(report, {"versions": COUNTS, "epochs": 20}, 1)


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="opt-in 20-process real cache lifecycle")
def test_real_five_version_process_restart(tmp_path):
    pytest.importorskip("monai")
    from test_monai_comparison import fixture_data
    from longitudinal_comparison import main
    from system_campaign import load_campaign
    fixture_data(tmp_path / "inputs")
    source = tmp_path / "inputs/v2.csv"
    prepare(source, tmp_path / "manifests", counts=[1, 2, 3, 4, 5])
    config = {"manifestDirectory": str(tmp_path / "manifests"), "sourceManifest": str(source),
              "sourceSamples": 5, "versions": [1, 2, 3, 4, 5], "seed": 20260926,
              "pairedBlocks": 1, "epochs": 1, "batchSize": 16, "imageSize": 16,
              "prefetchDepth": 0, "serverTrace": False, "confirmatory": False}
    path = tmp_path / "config.json"
    path.write_text(json.dumps(config))
    command = ["--config", str(path), "--output", str(tmp_path / "run"),
               "--scratch-root", str(tmp_path / "scratch"), "--cpu-fixture"]
    main(command)
    meta = load_campaign(tmp_path / "run")
    block = load_receipt(tmp_path / "run/blocks/00/paired.json", {"protocolHash": meta["protocolHash"]})
    for name, result in block["backendResults"].items():
        assert [s["preprocessCalls"] for s in result["stages"]] == [1] * 5
        assert [s["reusedSamples"] for s in result["stages"]] == [0, 1, 2, 3, 4]
        assert sum(s["trainingSampleRequests"] for s in result["stages"]) == 14
        if name == "aether":
            assert len({s["engineInfo"]["pid"] for s in result["stages"]}) == 5
    main(command + ["--resume"])
    assert (tmp_path / "run/figures/cumulative.png").stat().st_size > 1000
