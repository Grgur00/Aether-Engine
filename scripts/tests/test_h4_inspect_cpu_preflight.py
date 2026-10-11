import hashlib
import json
import zipfile

import pytest

from h4.inspect_cpu_preflight import inspect, capacity_summary, java_test_summary


def bundle(path, reports):
    files = {name: json.dumps(value).encode() for name, value in reports.items()}
    files["sha256.json"] = json.dumps({name: hashlib.sha256(value).hexdigest()
                                      for name, value in files.items()}).encode()
    with zipfile.ZipFile(path, "w") as archive:
        for name, value in files.items():
            archive.writestr(name, value)


def test_setup_failure_is_verified_without_inventing_admission_or_resource_results(tmp_path):
    source = {"files": {"harness.py": "a" * 64}}
    reports = {"source-inventory.json": source, "run-status.json": {
        "stage": "failed", "gpu_requested": False, "full_pcam_benchmark": False,
        "primary_h4_eligible": False, "error_type": "CalledProcessError", "error": "bootstrap"}}
    path = tmp_path / "results.zip"
    bundle(path, reports)
    summary = inspect(path, source)
    assert summary["archive_verified"]
    assert summary["online_1gib_passed"] is None
    assert summary["memory_control_probe_supported"] is None
    assert not summary["primary_h4_eligible"]
    with pytest.raises(ValueError, match="uploaded snapshot"):
        inspect(path, {"files": {}})


def test_completed_status_requires_actual_workload_reports(tmp_path):
    source = {"files": {}}
    reports = {"source-inventory.json": source, "run-status.json": {
        "stage": "completed", "gpu_requested": False, "full_pcam_benchmark": False,
        "primary_h4_eligible": False}}
    path = tmp_path / "results.zip"
    bundle(path, reports)
    with pytest.raises(ValueError, match="missing"):
        inspect(path, source)


def test_passed_flag_alone_cannot_qualify_one_gib(tmp_path):
    source = {"files": {}}
    reports = {"source-inventory.json": source, "run-status.json": {
        "stage": "failed", "gpu_requested": False, "full_pcam_benchmark": False,
        "primary_h4_eligible": False}, "online-1gib/preflight.json": {
        "passed": True, "primary_h4_eligible": False}}
    path = tmp_path / "results.zip"
    bundle(path, reports)
    with pytest.raises(ValueError, match="bounded workload"):
        inspect(path, source)


def test_zip_checksum_tampering_is_rejected(tmp_path):
    path = tmp_path / "results.zip"
    with zipfile.ZipFile(path, "w") as archive:
        archive.writestr("sha256.json", json.dumps({"run-status.json": "a" * 64}))
        archive.writestr("run-status.json", "{}")
    with pytest.raises(ValueError, match="checksum mismatch"):
        inspect(path, {})


def test_capacity_block_is_not_an_engine_failure():
    status = dict(stage="completed", capacity_gates={"1": False}, all_capacity_gates_passed=False)
    files = {"online-1gib/preflight.json": json.dumps(dict(passed=False,
             primary_h4_eligible=False, reason="insufficient safe disk capacity",
             required_free_bytes=26 * 1024 ** 3, actual_free_bytes=19 * 1024 ** 3)).encode()}
    result = capacity_summary(files, status)
    assert result["1"]["reason"] == "insufficient safe disk capacity"
    assert "2" not in result
    files["online-2gib/preflight.json"] = files["online-1gib/preflight.json"]
    with pytest.raises(ValueError, match="after the capacity gate stopped"):
        capacity_summary(files, status)


@pytest.mark.parametrize("gates", [{"2": True}, {"1": False, "2": True}, {"1": 1}, {}])
def test_capacity_requires_ordered_boolean_prefix(gates):
    with pytest.raises(ValueError, match="ordered prefix"):
        capacity_summary({}, dict(capacity_gates=gates))


def test_capacity_requires_report_and_consistent_status():
    status = dict(stage="completed", capacity_gates={"1": True}, all_capacity_gates_passed=False)
    with pytest.raises(ValueError, match="missing"):
        capacity_summary({}, status)
    files = {"online-1gib/preflight.json": json.dumps(dict(passed=False, primary_h4_eligible=False)).encode()}
    with pytest.raises(ValueError, match="disagrees"):
        capacity_summary(files, status)


def test_java_summary_requires_actual_cases_and_all_modules():
    files = {"java-tests/" + module + "/TEST-fixture.xml":
             b'<testsuite tests="1" failures="0" errors="0" skipped="0"><testcase name="fixture"/></testsuite>'
             for module in ("aether-engine", "aether-lsm", "aether-sstable", "aether-training-cache")}
    result = java_test_summary(files)
    assert result["passed"] and result["tests"] == 4
    del files["java-tests/aether-engine/TEST-fixture.xml"]
    assert not java_test_summary(files)["passed"]
    files["java-tests/aether-engine/TEST-fixture.xml"] = b'<testsuite tests="99" failures="0" errors="0" skipped="0"/>'
    with pytest.raises(ValueError, match="counts disagree"):
        java_test_summary(files)
