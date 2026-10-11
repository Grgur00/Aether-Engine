import json
from copy import deepcopy
from pathlib import Path
from types import SimpleNamespace

import pytest

from h4 import kaggle_preflight, memory_controller
from h4.common import counters, digest, read_evidence, write_json


def test_unavailable_file_is_explicit(tmp_path):
    evidence = read_evidence(tmp_path / "missing")
    assert evidence["available"] is False
    assert evidence["error"]


def test_atomic_json_and_counter_parsing(tmp_path):
    path = tmp_path / "result.json"
    write_json(path, {"passed": False})
    assert json.loads(path.read_text()) == {"passed": False}
    assert list(tmp_path.iterdir()) == [path]
    assert counters("anon 3\nfile 4\n") == {"anon": 3, "file": 4}
    with pytest.raises(ValueError):
        counters("file not-a-counter")


def test_protocol_is_exploratory_and_preserves_requested_storage():
    protocol = json.loads(Path(kaggle_preflight.__file__).with_name("protocol.json").read_text())
    assert protocol["confirmatory_frozen"] is False
    assert protocol["confirmatory"] is None
    assert protocol["dataset"]["samples"] == 262144
    assert protocol["artifact"]["payload_bytes"] == 3 * 96 * 96 * 4
    assert protocol["aether"]["prior_bulk_target_sstable_bytes"] == 32 * 1024 ** 2
    assert protocol["aether"]["target_sstable_bytes"] is None
    assert protocol["aether"]["population"] == "online-durable-put-many-v1"
    assert protocol["amendments"][0]["id"] == "H4-online-admission-v1"
    assert protocol["memory_limit_bytes"] == 2 * 1024 ** 3


def test_undelegated_parent_is_never_modified(tmp_path):
    control = tmp_path / "cgroup.subtree_control"
    control.write_text("cpu io")
    with pytest.raises(RuntimeError, match="parent unchanged"):
        memory_controller.configure_child(tmp_path, "aether-h4-fixture", 2 * 1024 ** 3)
    assert control.read_text() == "cpu io"
    assert list(tmp_path.iterdir()) == [control]


@pytest.mark.parametrize("name,limit", [("../escape", 2 * 1024 ** 3),
                                        ("aether-h4-fixture", 1)])
def test_unsafe_child_configuration_rejected_before_writes(tmp_path, name, limit):
    with pytest.raises(ValueError):
        memory_controller.configure_child(tmp_path, name, limit)
    assert not list(tmp_path.iterdir())


def test_nonlinux_is_not_memory_control_evidence(tmp_path, monkeypatch):
    monkeypatch.setattr(memory_controller.platform, "system", lambda: "Windows")
    result = memory_controller.probe(tmp_path)
    assert result["memory_control_supported"] is False
    assert result["out_of_core_verified"] is False
    assert "Linux" in result["reason"]


def test_successful_capability_probe_cannot_qualify_primary_h4(tmp_path, monkeypatch):
    monkeypatch.setattr(kaggle_preflight, "command", lambda argv: {"command": argv, "returncode": 0})
    monkeypatch.setattr(kaggle_preflight, "probe", lambda *args: {
        "memory_control_supported": True, "file_cache_accounting_verified": True,
        "out_of_core_verified": False})
    monkeypatch.setattr(kaggle_preflight.shutil, "disk_usage", lambda _: (100 * 1024 ** 3, 0, 100 * 1024 ** 3))
    output = tmp_path / "reports"
    result = kaggle_preflight.run(output, tmp_path, attempt_memory=True)
    assert result["memory_control_supported"] is True
    assert result["primary_h4_eligible"] is False
    assert result["dataset_verified"] is False
    assert result["out_of_core_verified"] is False
    checksums = json.loads((output / "sha256.json").read_text())
    assert set(checksums) == {"preflight.json", "hardware.json", "memory_control_report.json", "disk_capacity_report.json"}
    assert all(digest(output / name) == expected for name, expected in checksums.items())
    with pytest.raises(ValueError, match="preserve prior evidence"):
        kaggle_preflight.run(output, tmp_path)


def test_disk_estimate_is_not_measured_amplification(tmp_path, monkeypatch):
    monkeypatch.setattr(kaggle_preflight.shutil, "disk_usage", lambda _: (100, 50, 50))
    result = kaggle_preflight.disk_report(tmp_path, tmp_path, 2 * 1024 ** 3)
    assert result["adequate"] is False
    assert result["peak_amplification_measured"] is False
    assert result["estimated_required_scratch_bytes"] == 25 * 1024 ** 3


@pytest.mark.parametrize("membership,events,exit_code,expected", [
    (True, "oom 0\noom_kill 0\n", 0, True),
    (False, "oom 0\noom_kill 0\n", 0, False),
    (True, "oom 1\noom_kill 1\n", 0, False),
    (True, "", 0, False),
    (True, "oom 0\noom_kill 0\n", 1, False),
])
def test_controlled_worker_evidence_fails_closed(tmp_path, monkeypatch, membership, events, exit_code, expected):
    child = tmp_path / "aether-h4-fixture"
    child.mkdir()
    monkeypatch.setattr(memory_controller.platform, "system", lambda: "Linux")
    monkeypatch.setattr(memory_controller, "discover_parent", lambda: tmp_path)
    monkeypatch.setattr(memory_controller, "configure_child", lambda *args: child)
    monkeypatch.setattr(memory_controller, "snapshot", lambda _: {
        "memory.events": {"available": True, "text": events}})
    worker = {"membership_verified": membership, "java": {"membership_verified": membership},
              "before": {"memory.stat": {"text": "file 0\n"}},
              "after": {"memory.stat": {"text": "file 67108864\n"}}}

    def launch(argv, **kwargs):
        assert argv[1:3] == ["-m", "h4.memory_controller"]
        assert str(Path(memory_controller.__file__).resolve().parents[1]) in kwargs["env"]["PYTHONPATH"]
        return SimpleNamespace(returncode=exit_code, stdout=json.dumps(worker), stderr="")

    monkeypatch.setattr(memory_controller.subprocess, "run", launch)
    report = memory_controller.probe(tmp_path)
    assert report["memory_control_supported"] is expected
    assert report["out_of_core_verified"] is False
    assert report["pressure_verified"] is False
    assert report["physical_reads_verified"] is False
    assert report["parent_modified"] is False
    assert report["cleanup"]["completed"] is True
    assert not child.exists()


def resource_window():
    def evidence(text):
        return {"available": True, "text": text}
    before = {
        "memory.max": evidence(str(2 * 1024 ** 3)), "memory.swap.max": evidence("0"),
        "memory.events": evidence("oom 0\noom_kill 0\n"), "cgroup.procs": evidence("123\n456\n"),
        "memory.stat": evidence("file 100\npgscan 0\npgsteal 0\nworkingset_refault_file 0\n"),
        "io.stat": evidence("8:0 rbytes=0 wbytes=0 rios=0 wios=0\n")}
    after = deepcopy(before)
    after["memory.stat"] = evidence("file 1000000\npgscan 1000\npgsteal 900\nworkingset_refault_file 700\n")
    after["io.stat"] = evidence("8:0 rbytes=268435456 wbytes=0 rios=100 wios=0\n")
    return before, after


def assess(before, after):
    return memory_controller.assess_read_window(before, after, limit=2 * 1024 ** 3,
        payload_bytes=8 * 1024 ** 3, consumed_bytes=8 * 1024 ** 3, pids=[123, 456])


def test_resource_window_needs_real_reclaim_and_reads_not_only_large_logical_payload():
    before, after = resource_window()
    report = assess(before, after)
    assert report["out_of_core_resource_evidence_verified"] is True
    assert report["primary_h4_eligible"] is False
    assert report["physical_read_bytes"] == 256 * 1024 ** 2
    report = assess(before, before)
    assert report["out_of_core_resource_evidence_verified"] is False


@pytest.mark.parametrize("field,text", [
    ("memory.max", "max"), ("memory.swap.max", "1024"),
    ("memory.events", "oom 1\noom_kill 0"), ("memory.events", ""),
    ("cgroup.procs", "123"), ("cgroup.procs", "123\n456\n789"),
    ("io.stat", ""), ("io.stat", "8:0 wbytes=4"),
    ("io.stat", "8:0 rbytes=1"),
    ("memory.stat", "file 100\npgscan 0\npgsteal 0\nworkingset_refault_file 0"),
])
def test_incomplete_or_nonqualifying_resource_window_is_rejected(field, text):
    before, after = resource_window()
    after[field] = {"available": True, "text": text}
    assert assess(before, after)["out_of_core_resource_evidence_verified"] is False


def test_unavailable_and_regressed_io_are_not_zero_read_evidence():
    before, after = resource_window()
    after["io.stat"] = {"available": False, "error": "denied"}
    report = assess(before, after)
    assert report["physical_read_bytes"] is None
    before, after = resource_window()
    before["io.stat"]["text"] = "8:0 rbytes=300000000"
    assert assess(before, after)["out_of_core_resource_evidence_verified"] is False
