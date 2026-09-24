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
    parser.add_argument("target", choices=["smoke", "test", "profile", "pilot", "primary", "all"])
    parser.add_argument("--config", default=None)
    parser.add_argument("--output", default="results")
    parser.add_argument("--scratch-root", type=Path, help="Generated training stores on a separate filesystem")
    parser.add_argument("--server-trace", action="store_true", help="Enable pilot flush diagnostics")
    parser.add_argument("--pilot-repeats", type=int, default=10, help="Use 1 with --server-trace for a single diagnostic block")
    parser.add_argument("--epochs", type=int, default=None, help="Epochs per training block for pilot/primary runs")
    parser.add_argument("--prefetch-depth", type=int, default=None, help="Lookahead depth; primary is frozen at 0, pilot defaults to 1")
    parser.add_argument("--hit-path-profile", action="store_true", help="Run prepopulated read-only hit-path diagnostic")
    parser.add_argument("--request-sizes", default=None, help="Comma-separated request sizes for hit-path bytes-only sweep")
    parser.add_argument("--prefetch-depths", default=None, help="Comma-separated prefetch depths for hit-path full-input sweep")
    parser.add_argument("--hit-warmup-epochs", type=int, default=None, help="Untimed warmup epochs for hit-path diagnostic")
    parser.add_argument("--training-epochs", type=int, default=1, help="Epochs per CPU training fixture in smoke mode")
    args = parser.parse_args()
    from confirmatory import PRIMARY_CONFIG, SEED_BASE
    args.config = args.config or (PRIMARY_CONFIG if args.target == "primary" else "configs/paper/datasets.json")
    if args.prefetch_depth is None:
        args.prefetch_depth = 0 if args.target == "primary" else 1
    if args.target == "primary":
        if args.epochs not in (None, 20) or args.prefetch_depth != 0:
            parser.error("primary is frozen at --epochs 20 --prefetch-depth 0")
        args.epochs = 20
    if args.epochs is not None:
        if args.epochs < 1:
            parser.error("--epochs must be positive")
        if args.target not in {"pilot", "primary"}:
            parser.error("--epochs applies to pilot/primary runs")
    if args.prefetch_depth < 0 or (args.target not in {"pilot", "primary"} and args.prefetch_depth != 1):
        parser.error("custom --prefetch-depth is available only for pilot/primary and must be non-negative")
    if args.pilot_repeats < 1 or (args.pilot_repeats != 10 and (args.target != "pilot" or not args.server_trace)):
        parser.error("custom pilot repeats require pilot --server-trace and a positive count")
    if args.server_trace and args.target not in {"pilot", "profile"}:
        parser.error("--server-trace is available for pilot/profile diagnostics")
    hit_path_options = (args.request_sizes, args.prefetch_depths, args.hit_warmup_epochs)
    if args.hit_path_profile and args.target != "profile":
        parser.error("--hit-path-profile is available only for profile")
    if any(value is not None for value in hit_path_options) and not args.hit_path_profile:
        parser.error("hit-path sweep options require --hit-path-profile")
    if args.training_epochs < 1 or (args.target != "smoke" and args.training_epochs != 1):
        parser.error("--training-epochs must be positive and is only configurable for smoke")
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
        scratch = ["--scratch-root", str(args.scratch_root.resolve())] if args.scratch_root else []
        run([sys.executable, "scripts/artifact_smoke.py", "--output", output,
             "--training-epochs", str(args.training_epochs), *scratch])
        run([sys.executable, "scripts/transform_evolution.py", "--output", output + "/evolution"])
        run([sys.executable, "scripts/fault_injection.py", "--trials-per-point", "1", "--output", output + "/faults"])
        run([sys.executable, "scripts/concurrency_matrix.py", "--clients", "2", "--workers", "2", "--samples", "12",
             "--epochs", "2", "--repeats", "1", "--payload-bytes", "1024", "--output", output + "/concurrency"])
    elif args.target == "profile":
        if args.hit_path_profile:
            scratch = ["--scratch-root", str(args.scratch_root.resolve())] if args.scratch_root else []
            command = [sys.executable, "scripts/run_matrix.py", "--config", args.config,
                       "--datasets", "oct5k", "--hit-path-profile", "--output", output, *scratch]
            if args.server_trace:
                command.append("--server-trace")
            if args.request_sizes is not None:
                command += ["--request-sizes", args.request_sizes]
            if args.prefetch_depths is not None:
                command += ["--prefetch-depths", args.prefetch_depths]
            if args.hit_warmup_epochs is not None:
                command += ["--hit-warmup-epochs", str(args.hit_warmup_epochs)]
            run(command)
            return
        run([sys.executable, "scripts/profile_cache_requests.py", "--output", output + "/request-profile",
             "--windows", "30", "--operations", "10", "--payload-bytes", "4096", "--server-trace"])
    elif args.target == "pilot":
        scratch = ["--scratch-root", str(args.scratch_root.resolve())] if args.scratch_root else []
        if args.server_trace:
            scratch += ["--server-trace"]
        command = [sys.executable, "scripts/run_matrix.py", "--config", args.config, "--datasets", "oct5k",
             "--repeats", str(args.pilot_repeats), "--prefetch-depth", str(args.prefetch_depth),
             "--resume", "--output", output, *scratch]
        if args.epochs is not None:
            command += ["--epochs", str(args.epochs)]
        run(command)
        if args.pilot_repeats >= 10:
            run([sys.executable, "scripts/analyze.py", "--input", output, "--output", output + "/pilot-analysis", "--pilot"])
            run([sys.executable, "scripts/figures.py", "--input", output, "--output", output + "/pilot-figures"])
        else:
            print("Diagnostic blocks saved; fewer than ten blocks are not pilot inference.")
    elif args.target == "primary":
        scratch = ["--scratch-root", str(args.scratch_root.resolve())] if args.scratch_root else []
        command = [sys.executable, "scripts/run_matrix.py", "--config", args.config,
                   "--confirmatory", "--repeats", "24", "--batch-size", "16",
                   "--seed-base", str(SEED_BASE),
                   "--prefetch-depth", "0", "--resume", "--output", output, *scratch]
        if args.epochs is not None:
            command += ["--epochs", str(args.epochs)]
        run(command)
        run([sys.executable, "scripts/analyze.py", "--input", output, "--output", output + "/processed"])
        run([sys.executable, "scripts/figures.py", "--input", output, "--output", output + "/figures"])
    else:
        matrix = json.loads((ROOT / "configs/paper/evaluation.json").read_text())
        for campaign in matrix["campaigns"]:
            destination = output + "/" + campaign["name"]
            if campaign["driver"] == "dali_comparison.py":
                run([sys.executable, "scripts/validate_gpu.py", "--require-dali"])
            command = [sys.executable, "scripts/" + campaign["driver"], *campaign["arguments"], "--output", destination]
            if campaign["driver"] in {"run_matrix.py", "dali_comparison.py"}:
                command += ["--config", PRIMARY_CONFIG if "--confirmatory" in campaign["arguments"] else args.config, "--resume"]
                if args.scratch_root:
                    command += ["--scratch-root", str(args.scratch_root.resolve())]
            elif campaign["driver"] in {"fault_injection.py", "concurrency_matrix.py"}:
                command += ["--resume"]
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
