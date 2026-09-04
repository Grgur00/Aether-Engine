import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

from aether_bench import export_tidy_reports, key_stats, load_report_data, reports_passed


def main():
    parser = argparse.ArgumentParser(description="Regenerate AetherML prototype reproducibility reports.")
    parser.add_argument("--output-dir", default="build/aetherml-reproduce")
    parser.add_argument("--samples", type=int, default=32)
    parser.add_argument("--trials", type=int, default=8)
    args = parser.parse_args()
    if args.samples < 1 or args.trials < 1:
        raise ValueError("samples and trials must be positive")

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    reports = []
    started = time.time()

    reports.append(run_report([
        sys.executable,
        "clients/python/benchmark_aetherml.py",
        "--samples",
        str(args.samples),
        "--repeat",
        "3",
        "--work",
        "100",
        "--changed-percent",
        "30",
        "--output",
        str(output_dir / "aetherml-benchmark.json"),
    ]))
    reports.append(run_report([
        sys.executable,
        "clients/python/oct_segmentation_workload.py",
        "--samples",
        str(args.samples),
        "--epochs",
        "2",
        "--height",
        "64",
        "--width",
        "64",
        "--resize",
        "32",
        "--batch-size",
        "8",
        "--output",
        str(output_dir / "oct-segmentation-workload.json"),
    ]))
    reports.append(run_report([
        sys.executable,
        "clients/python/aetherml_crash_recovery.py",
        "--trials",
        str(args.trials),
        "--output",
        str(output_dir / "aetherml-crash-recovery.json"),
    ]))

    report_data = load_report_data(reports)
    combined_path = output_dir / "aetherml-report.json"
    summary = {
        "startedAt": started,
        "endedAt": time.time(),
        "reports": reports,
        "keyStats": key_stats(report_data),
        "reportData": report_data,
        "combinedReportPath": str(combined_path),
        "allPassed": reports_passed(reports, report_data),
    }
    summary_path = output_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    combined_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    export_tidy_reports(summary, output_dir)
    print(json.dumps({
        "allPassed": summary["allPassed"],
        "combinedReportPath": summary["combinedReportPath"],
        "keyStats": summary["keyStats"],
    }, indent=2))
    if not summary["allPassed"]:
        raise SystemExit(1)


def run_report(command):
    env = dict(os.environ)
    env["PYTHONPATH"] = "clients/python" + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    started = time.time()
    completed = subprocess.run(command, capture_output=True, text=True, env=env)
    report = {
        "name": Path(command[1]).stem,
        "command": command,
        "returnCode": completed.returncode,
        "startedAt": started,
        "endedAt": time.time(),
        "outputPath": output_path_from_command(command),
        "stdoutTail": completed.stdout.splitlines()[-5:],
        "stderrTail": completed.stderr.splitlines()[-5:],
    }
    print(json.dumps(report, indent=2))
    return report


def output_path_from_command(command):
    if "--output" not in command:
        return None
    index = command.index("--output") + 1
    return command[index] if index < len(command) else None


if __name__ == "__main__":
    main()
