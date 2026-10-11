"""Verify a downloaded CPU diagnostic ZIP without treating it as an H4 result."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile

from .admission_evidence import validate_successful_online


def inspect(archive_path, source_inventory):
    with zipfile.ZipFile(archive_path) as archive:
        members = archive.infolist()
        names = [item.filename for item in members]
        if len(set(names)) != len(names) or sum(item.file_size for item in members) > 256 * 1024 ** 2:
            raise ValueError("Duplicate members or oversized diagnostic archive")
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts or "\\" in name:
                raise ValueError("Unsafe archive member")
        files = {name: archive.read(name) for name in names}
    inventory = json.loads(files["sha256.json"])
    expected_names = {name for name in files if PurePosixPath(name).name != "sha256.json"}
    if set(inventory) != expected_names:
        raise ValueError("Diagnostic inventory does not cover exactly the archived reports")
    for name, expected in inventory.items():
        if hashlib.sha256(files[name]).hexdigest() != expected:
            raise ValueError("Diagnostic report checksum mismatch: " + name)
    source = json.loads(files["source-inventory.json"]) if "source-inventory.json" in files else None
    if source != source_inventory:
        raise ValueError("Diagnostic source does not match the uploaded snapshot")
    status = json.loads(files["run-status.json"])
    if (status.get("gpu_requested") is not False or status.get("full_pcam_benchmark") is not False
            or status.get("primary_h4_eligible") is not False):
        raise ValueError("CPU diagnostic scope changed")
    summary = {"archive_verified": True, "source_snapshot_verified": True,
               "stage": status.get("stage"), "primary_h4_eligible": False,
               "out_of_core_verified": False, "memory_control_probe_supported": None,
               "online_smoke_passed": None, "online_1gib_passed": None,
               "limitation": "Synthetic admission and a permission probe do not qualify primary PCam retrieval"}
    if "resources/memory_control_report.json" in files:
        report = json.loads(files["resources/memory_control_report.json"])
        summary["memory_control_probe_supported"] = report.get("memory_control_supported")
        summary["memory_control_reason"] = report.get("reason")
    for folder, key in (("online-smoke-32", "online_smoke_passed"), ("online-1gib", "online_1gib_passed")):
        name = folder + "/preflight.json"
        if name not in files:
            if status.get("stage") == "completed":
                raise ValueError("Completed diagnostic is missing " + name)
            continue
        report = json.loads(files[name])
        if report.get("primary_h4_eligible") is not False:
            raise ValueError("Admission diagnostic claims primary eligibility")
        summary[key] = report.get("passed") is True
        if report.get("passed") is True:
            payload_bytes = validate_successful_online(report)
            if folder == "online-1gib" and payload_bytes < 1024 ** 3:
                raise ValueError("Small smoke cannot establish 1 GiB admission")
        summary[folder + "_error"] = report.get("error", report.get("reason"))
        summary[folder + "_exception_evidence"] = report.get("diagnostic_exception_evidence")
    if status.get("stage") == "completed" and status.get("admission_1gib_passed") != summary["online_1gib_passed"]:
        raise ValueError("Run status disagrees with admission report")
    if status.get("stage") == "failed":
        summary["failure"] = {"type": status.get("error_type"), "message": status.get("error")}
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("archive", type=Path)
    parser.add_argument("--source-inventory", type=Path, required=True)
    args = parser.parse_args()
    summary = inspect(args.archive, json.loads(args.source_inventory.read_text()))
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
