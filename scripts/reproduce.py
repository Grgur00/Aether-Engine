"""Cross-platform artifact entry point; subprocess failures stop the campaign."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

from paper_common import ROOT


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("target", choices=["smoke", "test", "primary", "all"])
    parser.add_argument("--config", default="configs/paper/datasets.json")
    parser.add_argument("--output", default="results")
    args = parser.parse_args()
    output = str(Path(args.output).resolve())
    child_env = os.environ.copy()
    child_env["PYTHONPATH"] = os.pathsep.join([str(ROOT / "clients/python"), str(ROOT / "scripts"), child_env.get("PYTHONPATH", "")])
    def run(command):
        subprocess.run(command, cwd=ROOT, env=child_env, check=True)
    gradle = str(ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew"))
    run([gradle, ":modules:aether-training-cache:test", ":modules:aether-training-cache:paperRuntimeClasspath", "--no-daemon"])
    if args.target in {"smoke", "test"}:
        run([sys.executable, "-m", "pytest", "clients/python/tests", "scripts/tests", "-q"])
        if args.target == "test":
            return
        run([sys.executable, "scripts/artifact_smoke.py", "--output", output])
        run([sys.executable, "scripts/transform_evolution.py", "--output", output + "/evolution"])
        run([sys.executable, "scripts/fault_injection.py", "--trials-per-point", "1", "--output", output + "/faults"])
        run([sys.executable, "scripts/concurrency_matrix.py", "--clients", "2", "--workers", "2", "--samples", "12",
             "--epochs", "2", "--repeats", "1", "--payload-bytes", "1024", "--output", output + "/concurrency"])
    elif args.target == "primary":
        run([sys.executable, "scripts/run_matrix.py", "--config", args.config, "--confirmatory", "--resume", "--output", output])
        run([sys.executable, "scripts/analyze.py", "--input", output, "--output", output + "/processed", "--holm"])
        run([sys.executable, "scripts/figures.py", "--input", output, "--output", output + "/figures"])
    else:
        matrix = json.loads((ROOT / "configs/paper/evaluation.json").read_text())
        for campaign in matrix["campaigns"]:
            destination = output + "/" + campaign["name"]
            if campaign["driver"] == "dali_comparison.py":
                run([sys.executable, "scripts/validate_gpu.py", "--require-dali"])
            command = [sys.executable, "scripts/" + campaign["driver"], *campaign["arguments"], "--output", destination]
            if campaign["driver"] in {"run_matrix.py", "dali_comparison.py"}:
                command += ["--config", args.config, "--resume"]
            run(command)
            if campaign["driver"] == "run_matrix.py":
                run([sys.executable, "scripts/analyze.py", "--input", destination, "--output", destination + "/processed"])
                run([sys.executable, "scripts/figures.py", "--input", destination, "--output", destination + "/figures"])
                run([sys.executable, "scripts/systems_figures.py", "training", "--input", destination, "--output", destination + "/tables"])
            elif campaign["driver"] == "dali_comparison.py":
                run([sys.executable, "scripts/dali_analyze.py", "--input", destination, "--output", destination + "/processed"])
            elif campaign["name"] in {"concurrency", "durability"}:
                run([sys.executable, "scripts/systems_figures.py", campaign["name"], "--input", destination, "--output", destination + "/figures"])


if __name__ == "__main__":
    main()
