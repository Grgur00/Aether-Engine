"""Verify a downloaded CPU diagnostic ZIP without treating it as an H4 result."""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import zipfile
import xml.etree.ElementTree as ET

from .admission_evidence import validate_successful_online


def capacity_summary(files, status):
    gates = status["capacity_gates"]
    sizes = (1, 2, 4, 8)
    if (not isinstance(gates, dict) or not gates
            or list(gates) != [str(size) for size in sizes[:len(gates)]]
            or any(type(value) is not bool for value in gates.values())
            or any(not gates[str(size)] for size in sizes[:len(gates) - 1])):
        raise ValueError("capacity gates must be an ordered prefix ending at the first failure")
    result = {}
    for size in sizes:
        name = f"online-{size}gib/preflight.json"
        if str(size) not in gates:
            if name in files:
                raise ValueError("unexpected admission after the capacity gate stopped")
            continue
        if name not in files:
            raise ValueError("capacity stage report missing: " + name)
        report = json.loads(files[name])
        if report.get("passed") is not gates[str(size)] or report.get("primary_h4_eligible") is not False:
            raise ValueError("capacity status disagrees with admission report")
        row = dict(passed=gates[str(size)], reason=report.get("reason", report.get("error")))
        if row["passed"]:
            payload = validate_successful_online(report)
            if payload < size * 1024 ** 3:
                raise ValueError("small smoke cannot establish requested capacity")
            row.update(samples=report["samples"], logical_payload_bytes=payload,
                       population_seconds_including_generation=report.get("population_seconds_including_synthetic_generation"),
                       shuffled_read=report.get("shuffled_read"), heap_sampling=report.get("heap_sampling"),
                       disk_peak=report.get("disk_peak"))
            records = report["preclose_quiescence"]["backgroundCompaction"].get("records", [])
            completed = [record for record in records if record.get("completed") is True
                         and record.get("manifestPublished") is True and not record.get("failure")]
            row["recorded_compaction"] = dict(
                published_records=len(completed),
                input_bytes=sum(record["inputBytes"] for record in completed),
                output_bytes=sum(record["outputBytes"] for record in completed),
                summed_service_seconds=sum(record["totalNs"] for record in completed) / 1e9,
                limitation="recorded compaction history only; not full physical write amplification or exclusive population wall time")
        else:
            row.update(required_free_bytes=report.get("required_free_bytes"), actual_free_bytes=report.get("actual_free_bytes"))
        result[str(size)] = row
    all_passed = len(gates) == 4 and all(gates.values())
    if status.get("stage") == "completed" and status.get("all_capacity_gates_passed") is not all_passed:
        raise ValueError("aggregate capacity status disagrees with stage evidence")
    return result


def java_test_summary(files):
    result = dict(tests=0, failures=0, errors=0, skipped=0, modules=[])
    for name, payload in files.items():
        if not name.startswith("java-tests/") or not name.endswith(".xml"):
            continue
        suite = ET.fromstring(payload)
        if suite.tag != "testsuite":
            raise ValueError("unexpected Java test report root")
        cases = suite.findall("testcase")
        counts = dict(tests=len(cases), failures=sum(case.find("failure") is not None for case in cases),
                      errors=sum(case.find("error") is not None for case in cases),
                      skipped=sum(case.find("skipped") is not None for case in cases))
        if any(int(suite.get(key, "-1")) != value for key, value in counts.items()):
            raise ValueError("Java test suite counts disagree with test cases")
        for key, value in counts.items():
            result[key] += value
        result["modules"].append(PurePosixPath(name).parts[1])
    result["modules"] = sorted(set(result["modules"]))
    result["passed"] = (result["tests"] > 0 and not any(result[key] for key in ("failures", "errors", "skipped"))
                        and result["modules"] == ["aether-engine", "aether-lsm", "aether-sstable", "aether-training-cache"])
    return result


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
    if "capacity_gates" in status:
        smoke_name = "online-smoke-32/preflight.json"
        if smoke_name not in files:
            raise ValueError("capacity diagnostic is missing " + smoke_name)
        smoke = json.loads(files[smoke_name])
        if validate_successful_online(smoke) != 32 * 110592:
            raise ValueError("capacity diagnostic smoke differs from the 32-artifact workload")
        summary["online_smoke_passed"] = True
        summary["capacity"] = capacity_summary(files, status)
        summary["java_tests"] = java_test_summary(files)
        summary["online_1gib_passed"] = summary["capacity"].get("1", {}).get("passed")
        if status.get("stage") == "completed" and not summary["java_tests"]["passed"]:
            raise ValueError("completed capacity diagnostic lacks passing Java regression evidence")
        return summary
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
