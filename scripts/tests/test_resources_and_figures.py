"""Parser/renderer fixtures only. No generated value is a research measurement."""
from pathlib import Path

import pytest

from aether_training_cache import resources
from figures import generate


def test_linux_process_fields_and_unavailable_io(tmp_path, monkeypatch):
    root = tmp_path / "123"
    root.mkdir()
    fields = ["0"] * 40
    fields[0], fields[7], fields[9], fields[11], fields[12] = "R", "7", "2", "200", "100"
    (root / "stat").write_text("123 (a process ) name) " + " ".join(fields))
    (root / "status").write_text("VmRSS:\t100 kB\nVmHWM:\t120 kB\n")
    monkeypatch.setattr(resources, "Path", lambda _: tmp_path)
    monkeypatch.setattr(resources.os, "sysconf", lambda _: 100, raising=False)
    before = resources.snapshot(123)
    assert before["available"] and before["cpuSeconds"] == 3
    assert before["minorFaults"] == 7 and before["majorFaults"] == 2
    assert before["rssBytes"] == 102400
    after = {**before, "cpuSeconds": 4, "minorFaults": 9}
    report = resources.delta(before, after)
    assert report["cpuSeconds"] == 1 and report["minorFaults"] == 2
    assert report["diskReadBytes"] is None


def test_standalone_figures_use_paired_ratios_and_emit_artifacts(tmp_path):
    condition = {"conditionId": "test-fixture", "dataset": "TEST FIXTURE", "samples": 2,
                 "reusePercent": None, "workers": 0, "preprocessPasses": 1, "gpuCount": 1}
    blocks = [{"protocolHash": "test-protocol", "environmentId": "test-environment",
        "conditionId": "test-fixture", "blockIndex": i, "condition": condition,
        "throughput": {"aether": a, "raw": r, "mmap": m, "ram": 15 + i}}
        for i, (a, r, m) in enumerate([(10, 8, 10.1), (11, 8.2, 10.8), (12, 9, 12.1)])]
    generate(blocks, tmp_path)
    assert (tmp_path / "throughput.csv").is_file()
    assert (tmp_path / "primary-table.tex").is_file()
    assert len(list(tmp_path.glob("ratio-*.pdf"))) == 1
    for path in tmp_path.glob("*.pdf"):
        assert path.read_bytes().startswith(b"%PDF")
    assert len(list(tmp_path.glob("*.png"))) == 2
