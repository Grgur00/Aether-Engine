"""Kill an actual Java engine process at instrumented publication boundaries."""
import argparse
import json
import os
import subprocess
import time
from pathlib import Path

from paper_common import environment, java_classpath, sha256, write_json

POINTS = ("before-data-write", "after-data-fsync", "before-index-commit",
          "after-index-commit-before-ack", "after-ack")


def run_trial(root, point, mode, timeout=60):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=False)
    base = ["java", "--enable-preview", "-cp", java_classpath(),
            "io.aetherdb.training.cache.TrainingCacheFaultProbe"]
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    with (root / "writer.log").open("w", encoding="utf-8") as log:
        process = subprocess.Popen(base + ["write", str(root), point, mode], stdout=log, stderr=log,
                                   creationflags=flags)
        reached = False
        try:
            deadline = time.monotonic() + timeout
            marker = root / "boundary.txt"
            while time.monotonic() < deadline:
                if marker.exists() and marker.read_text() == point:
                    reached = True
                    break
                if process.poll() is not None:
                    raise RuntimeError(f"fault writer exited before boundary; see {root / 'writer.log'}")
                time.sleep(.02)
            if not reached:
                raise TimeoutError(f"fault boundary {point} not reached")
        finally:
            if process.poll() is None:
                process.kill()  # SIGKILL on POSIX; TerminateProcess on Windows.
            process.wait(timeout=15)
    result = subprocess.run(base + ["verify", str(root), point, mode], capture_output=True,
                            text=True, check=True, timeout=timeout, creationflags=flags)
    (root / "recovery.log").write_text(result.stdout + result.stderr, encoding="utf-8")
    report = json.loads(next(line for line in reversed(result.stdout.splitlines()) if line.startswith("{")))
    report.update(faultPoint=point, writeMode=mode, boundaryReached=reached,
                  trialId=root.name,
                  killMechanism="TerminateProcess" if os.name == "nt" else "SIGKILL",
                  contract="process-crash; not power-loss", writerExitCode=process.returncode)
    write_json(root / "result.json", report)
    return {**report, "resultSha256": sha256(root / "result.json")}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine", choices=["java"], default="java")
    parser.add_argument("--fault-points", default=",".join(POINTS))
    parser.add_argument("--trials-per-point", type=int, default=100)
    parser.add_argument("--write-modes", default="single,batch")
    parser.add_argument("--verify-checksum", action="store_true", help="Always enabled")
    parser.add_argument("--verify-acknowledged-writes", action="store_true", help="Always enabled")
    parser.add_argument("--output", type=Path, default=Path("results/durability"))
    args = parser.parse_args(argv)
    points, modes = args.fault_points.split(","), args.write_modes.split(",")
    if args.trials_per_point < 1 or not set(points) <= set(POINTS) or not set(modes) <= {"single", "batch"}:
        parser.error("positive trial count and supported fault points/write modes required")
    args.output.mkdir(parents=True, exist_ok=True)
    write_json(args.output / "environment.json", environment())
    results = []
    for point in points:
        for mode in modes:
            for trial in range(args.trials_per_point):
                result = run_trial(args.output / f"{point}-{mode}-{trial:04d}", point, mode)
                results.append(result)
                write_json(args.output / "summary.json", {"trials": results, "allPassed": all(r["passed"] for r in results)})
                print(f"{point} {mode} {trial + 1}/{args.trials_per_point}: {result['passed']}", flush=True)
                if not result["passed"]:
                    raise RuntimeError("recovery contract violated; see recorded trial")


if __name__ == "__main__":
    main()
