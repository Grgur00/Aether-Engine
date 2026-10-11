"""H4 M0 discovery: explicit unavailable evidence, no unsupported eligibility."""
import argparse
import importlib.metadata
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

from .common import digest, read_evidence, write_json
from .memory_controller import probe


def command(argv):
    try:
        result = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        return {"command": argv, "returncode": result.returncode,
                "stdout": result.stdout, "stderr": result.stderr}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"command": argv, "available": False, "error": str(error)}


def disk_report(scratch, output, limit):
    report = {"scratch": str(scratch), "output": str(output), "adequate": False,
              "estimate_policy": "largest logical payload times 3 plus 1 GiB reserve; not measured amplification",
              "estimated_required_scratch_bytes": 4 * limit * 3 + 1024 ** 3,
              "peak_amplification_measured": False}
    try:
        for key, path in (("scratch", scratch), ("output", output)):
            usage = shutil.disk_usage(path)
            report[key + "_capacity"] = dict(zip(("total", "used", "free"), usage))
        report["adequate"] = (report["scratch_capacity"]["free"] >= report["estimated_required_scratch_bytes"]
                              and report["output_capacity"]["free"] >= 256 * 1024 ** 2)
    except OSError as error:
        report["error"] = str(error)
    return report


def run(output, scratch, *, memory_limit=2 * 1024 ** 3, attempt_memory=False):
    output, scratch = Path(output).resolve(), Path(scratch).resolve(strict=True)
    if not scratch.is_dir():
        raise ValueError("scratch must be an existing directory")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("preflight output must be empty; preserve prior evidence")
    hardware = {"schema": "aether-h4-hardware-v1", "python": sys.version,
                "platform": platform.platform(), "cpu_count": os.cpu_count(),
                "cpu_affinity": sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
                "commands": [command(argv) for argv in (["uname", "-a"], ["lscpu"], ["free", "-h"],
                    ["df", "-h", str(output), str(scratch)], ["java", "-version"])],
                "files": {name: read_evidence(name) for name in (
                    "/proc/meminfo", "/proc/self/cgroup", "/proc/self/mountinfo",
                    "/sys/fs/cgroup/cgroup.controllers", "/sys/fs/cgroup/memory.max",
                    "/sys/fs/cgroup/memory.current")}, "packages": {}}
    for name in ("numpy", "h5py", "torch"):
        try:
            hardware["packages"][name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            hardware["packages"][name] = None
    disk = disk_report(scratch, output, memory_limit)
    if attempt_memory and disk.get("scratch_capacity", {}).get("free", 0) >= 512 * 1024 ** 2:
        memory = probe(scratch, memory_limit)
    else:
        memory = {"memory_control_supported": False, "out_of_core_verified": False,
                  "reason": "memory probe not requested or insufficient safe probe scratch space"}
    preflight = {"schema": "aether-h4-preflight-v1", "stage": "M0-discovery",
                 "memory_control_supported": memory["memory_control_supported"],
                 "out_of_core_verified": False, "primary_h4_eligible": False,
                 "dataset_verified": False, "benchmark_process_membership_verified": False,
                 "reason": "dataset provenance, Java/worker membership, and sustained out-of-core I/O/reclaim are not yet verified",
                 "disk_capacity_adequate": disk["adequate"],
                 "protocol_sha256": digest(Path(__file__).with_name("protocol.json")),
                 "tool_sha256": {str(path.relative_to(Path(__file__).parent)).replace("\\", "/"): digest(path)
                                 for path in sorted(Path(__file__).parent.rglob("*"))
                                 if path.suffix in (".py", ".java")}}
    reports = {"hardware.json": hardware, "memory_control_report.json": memory,
               "disk_capacity_report.json": disk, "preflight.json": preflight}
    for name, value in reports.items():
        write_json(output / name, value)
    write_json(output / "sha256.json", {name: digest(output / name) for name in reports})
    return preflight


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--attempt-memory-control", action="store_true")
    args = parser.parse_args()
    report = run(args.output, args.scratch, attempt_memory=args.attempt_memory_control)
    print("H4 resource discovery saved; primary_h4_eligible=" + str(report["primary_h4_eligible"]))
    return 2 if not report["primary_h4_eligible"] else 0


if __name__ == "__main__":
    raise SystemExit(main())
