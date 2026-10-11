import hashlib
import json
import zipfile

import pytest

from h4.inspect_cpu_preflight import inspect


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
