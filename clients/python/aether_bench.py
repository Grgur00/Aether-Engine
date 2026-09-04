import argparse
import csv
import json
import os
import subprocess
import sys
import time
from pathlib import Path


def main():
    args = parse_args()
    summary = run_suite(args)
    print(json.dumps(console_summary(summary), indent=2))
    if not summary["allPassed"]:
        raise SystemExit(1)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Run AetherML reproducibility and performance benchmarks.")
    parser.add_argument("--profile", choices=["smoke", "gpu-acceptance", "gpu-training"], default="smoke")
    parser.add_argument("--dataset", choices=["oct"], default="oct")
    parser.add_argument("--pipeline", choices=["segmentation"], default="segmentation")
    parser.add_argument("--samples", type=int, default=None)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--repeat", "--epochs", dest="repeat", type=int, default=None)
    parser.add_argument("--trials", type=int, default=8)
    parser.add_argument("--work", type=int, default=None)
    parser.add_argument("--changed-percent", type=float, default=30.0)
    parser.add_argument("--height", type=int, default=None)
    parser.add_argument("--width", type=int, default=None)
    parser.add_argument("--resize", type=int, default=None)
    parser.add_argument("--batch-size", type=int, default=None)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--input-dir", default=None)
    parser.add_argument("--extensions", default=".bin,.raw,.dat,.png,.jpg,.jpeg,.tif,.tiff")
    parser.add_argument("--dataset-kind", choices=["synthetic", "oct5k"], default="synthetic")
    parser.add_argument("--dataset-manifest")
    parser.add_argument("--dataset-split", default="train")
    parser.add_argument("--oct5k-image-size", type=int)
    parser.add_argument("--oct5k-transform-version", default="oct5k-v1")
    parser.add_argument("--trust-manifest-hashes", action="store_true")
    parser.add_argument("--accelerator-backend", choices=["auto", "cuda", "rocm"], default="auto")
    parser.add_argument("--expected-gpu", default="")
    parser.add_argument("--warmup-steps", type=int, default=12)
    parser.add_argument("--measured-steps", type=int, default=0)
    parser.add_argument("--model-tier", choices=["small", "medium", "large"], default="small")
    parser.add_argument("--runs", type=int, default=None)
    parser.add_argument("--preprocess-passes", type=int, default=1)
    parser.add_argument("--initial-cache-hit-ratio", type=float, default=0.0)
    parser.add_argument("--prepopulate-previous-version", action="store_true")
    parser.add_argument("--prefetch-batches", type=int, default=0)
    parser.add_argument("--augmentation-mode", choices=["auto", "none", "light"], default="auto")
    parser.add_argument("--gpu-sample-interval-ms", type=float, default=250.0)
    parser.add_argument(
        "--sweep-plan",
        choices=["none", "gpu-required"],
        default="none",
        help="Write a reproducible GPU sweep plan into the report without executing the sweep matrix.",
    )
    parser.add_argument("--plan-only", action="store_true", help="Write configuration/sweep report metadata without running benchmarks.")
    parser.add_argument("--output-dir", default="build/aether-bench")
    args = parser.parse_args(argv)
    apply_profile_defaults(args)
    if args.oct5k_image_size is not None:
        args.resize = args.oct5k_image_size
    if args.augmentation_mode == "auto":
        args.augmentation_mode = "light" if args.dataset_kind == "oct5k" else "none"
    validate_args(args)
    return args


def apply_profile_defaults(args):
    defaults = {
        "smoke": {"samples": 32, "repeat": 3, "work": 100, "height": 64, "width": 64, "resize": 32, "batch_size": 8, "runs": 1},
        "gpu-acceptance": {"samples": 128, "repeat": 2, "work": 100, "height": 128, "width": 128, "resize": 128, "batch_size": 8, "runs": 3},
        "gpu-training": {"samples": 1024, "repeat": 5, "work": 100, "height": 256, "width": 256, "resize": 256, "batch_size": 16, "runs": 3},
    }[args.profile]
    for name, value in defaults.items():
        if getattr(args, name) is None:
            setattr(args, name, value)


def validate_args(args):
    if args.samples < 1 or args.repeat < 1 or args.trials < 1 or args.runs < 1:
        raise ValueError("samples, repeat, trials, and runs must be positive")
    if args.work < 1:
        raise ValueError("work must be positive")
    if args.workers < 0:
        raise ValueError("workers must be non-negative")
    if not 0 <= args.changed_percent <= 100:
        raise ValueError("changed-percent must be between 0 and 100")
    if args.height < 4 or args.width < 4 or args.resize < 4:
        raise ValueError("height, width, and resize must be at least 4")
    if args.batch_size < 1:
        raise ValueError("batch-size must be positive")
    if args.preprocess_passes < 1:
        raise ValueError("preprocess-passes must be positive")
    if args.prefetch_batches < 0:
        raise ValueError("prefetch-batches must be non-negative")
    if args.gpu_sample_interval_ms < 0:
        raise ValueError("gpu-sample-interval-ms must be non-negative")
    if not 0 <= args.initial_cache_hit_ratio <= 100:
        raise ValueError("initial-cache-hit-ratio must be between 0 and 100")
    if args.input_dir is not None and not Path(args.input_dir).is_dir():
        raise ValueError("input-dir must be an existing directory")
    if args.dataset_kind == "oct5k":
        if not args.dataset_manifest:
            raise ValueError("dataset-kind=oct5k requires --dataset-manifest")
        if not Path(args.dataset_manifest).is_file():
            raise ValueError("dataset-manifest must be an existing CSV file")
    if args.oct5k_image_size is not None and args.oct5k_image_size < 4:
        raise ValueError("oct5k-image-size must be at least 4")
    if args.plan_only and args.sweep_plan == "none":
        raise ValueError("plan-only requires --sweep-plan")


def run_suite(args):
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    started = time.time()
    benchmark_path = output_dir / "aetherml-benchmark.json"
    oct_path = output_dir / "oct-segmentation-workload.json"
    crash_path = output_dir / "aetherml-crash-recovery.json"
    reports = []
    if args.plan_only:
        pass
    elif args.profile == "smoke":
        reports.append(run_report([
            sys.executable,
            script_path("benchmark_aetherml.py"),
            "--samples",
            str(args.samples),
            "--repeat",
            str(args.repeat),
            "--work",
            str(args.work),
            "--changed-percent",
            str(args.changed_percent),
            "--output",
            str(benchmark_path),
        ]))
        reports.append(run_report(oct_workload_command(args, oct_path)))
    else:
        reports.append(run_report(gpu_training_command(args, output_dir / "gpu-training.json")))
    if not args.plan_only:
        reports.append(run_report([
            sys.executable,
            script_path("aetherml_crash_recovery.py"),
            "--trials",
            str(args.trials),
            "--output",
            str(crash_path),
        ]))
    report_data = load_report_data(reports)
    combined_path = output_dir / "aetherml-report.json"
    all_passed = reports_passed(reports, report_data)
    summary = {
        "benchmark": "aether-bench",
        "profile": args.profile,
        "dataset": args.dataset,
        "pipeline": args.pipeline,
        "configuration": {
            "samples": args.samples,
            "workers": args.workers,
            "repeat": args.repeat,
            "trials": args.trials,
            "work": args.work,
            "changedPercent": args.changed_percent,
            "height": args.height,
            "width": args.width,
            "resize": args.resize,
            "batchSize": args.batch_size,
            "seed": args.seed,
            "inputDir": args.input_dir,
            "datasetKind": args.dataset_kind,
            "datasetManifest": args.dataset_manifest,
            "datasetSplit": args.dataset_split,
            "oct5kImageSize": args.oct5k_image_size,
            "oct5kTransformVersion": args.oct5k_transform_version,
            "verifyManifestHashes": not args.trust_manifest_hashes,
            "acceleratorBackend": args.accelerator_backend,
            "expectedGpu": args.expected_gpu,
            "warmupSteps": args.warmup_steps,
            "measuredSteps": args.measured_steps,
            "modelTier": args.model_tier,
            "runs": args.runs,
            "preprocessPasses": args.preprocess_passes,
            "initialCacheHitRatio": args.initial_cache_hit_ratio,
            "prepopulatePreviousVersion": args.prepopulate_previous_version,
            "prefetchBatches": args.prefetch_batches,
            "augmentationMode": args.augmentation_mode,
            "gpuSampleIntervalMs": args.gpu_sample_interval_ms,
            "sweepPlan": args.sweep_plan,
            "planOnly": args.plan_only,
        },
        "sweepPlan": build_sweep_plan(args, output_dir) if args.sweep_plan != "none" else None,
        "startedAt": started,
        "endedAt": time.time(),
        "reports": reports,
        "keyStats": key_stats(report_data),
        "reportData": report_data,
        "combinedReportPath": str(combined_path),
        "allPassed": all_passed,
    }
    summary["evidenceAudit"] = evidence_audit(summary)
    summary_path = output_dir / "summary.json"
    summary_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    combined_path.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    export_tidy_reports(summary, output_dir)
    return summary


def oct_workload_command(args, output_path):
    command = [
            sys.executable,
            script_path("oct_segmentation_workload.py"),
            "--samples",
            str(args.samples),
            "--epochs",
            str(args.repeat),
            "--height",
            str(args.height),
            "--width",
            str(args.width),
            "--resize",
            str(args.resize),
            "--batch-size",
            str(args.batch_size),
            "--seed",
            str(args.seed),
            "--output",
            str(output_path),
    ]
    if args.input_dir is not None:
        command.extend(["--input-dir", args.input_dir, "--extensions", args.extensions])
    return command


def gpu_training_command(args, output_path):
    command = [
        sys.executable,
        script_path("benchmark_gpu_segmentation.py"),
        "--profile",
        args.profile,
        "--samples",
        str(args.samples),
        "--epochs",
        str(args.repeat),
        "--height",
        str(args.height),
        "--width",
        str(args.width),
        "--resize",
        str(args.resize),
        "--batch-size",
        str(args.batch_size),
        "--workers",
        str(args.workers),
        "--changed-percent",
        str(args.changed_percent),
        "--seed",
        str(args.seed),
        "--dataset-kind",
        args.dataset_kind,
        "--dataset-split",
        args.dataset_split,
        "--oct5k-transform-version",
        args.oct5k_transform_version,
        *([] if not args.trust_manifest_hashes else ["--trust-manifest-hashes"]),
        "--expected-gpu",
        args.expected_gpu,
        "--accelerator-backend",
        args.accelerator_backend,
        "--warmup-steps",
        str(args.warmup_steps),
        "--measured-steps",
        str(args.measured_steps),
        "--model-tier",
        args.model_tier,
        "--runs",
        str(args.runs),
        "--preprocess-passes",
        str(args.preprocess_passes),
        "--initial-cache-hit-ratio",
        str(args.initial_cache_hit_ratio),
        *([] if not args.prepopulate_previous_version else ["--prepopulate-previous-version"]),
        "--prefetch-batches",
        str(args.prefetch_batches),
        "--augmentation-mode",
        args.augmentation_mode,
        "--gpu-sample-interval-ms",
        str(args.gpu_sample_interval_ms),
        "--output",
        str(output_path),
    ]
    if args.input_dir is not None:
        command.extend(["--input-dir", args.input_dir, "--extensions", args.extensions])
    if args.dataset_manifest is not None:
        command.extend(["--dataset-manifest", args.dataset_manifest])
    if args.oct5k_image_size is not None:
        command.extend(["--oct5k-image-size", str(args.oct5k_image_size)])
    return command


def build_sweep_plan(args, output_dir):
    base = sweep_base_args(args)
    matrix = [
        ("model-size", [{"model_tier": tier} for tier in ("small", "medium", "large")]),
        ("resolution", [{"height": size, "width": size, "resize": size} for size in (128, 256, 512)]),
        ("changed-percent", [{"changed_percent": value, "prepopulate_previous_version": True} for value in (0, 10, 30, 50, 100)]),
        ("hit-ratio", [{"initial_cache_hit_ratio": value} for value in (0, 25, 50, 75, 100)]),
        ("preprocess-passes", [{"preprocess_passes": value} for value in (1, 2, 4, 8, 16)]),
        ("worker", [{"workers": value} for value in (0, 1, 2, 4)]),
        ("prefetch", [{"prefetch_batches": value} for value in (1, 2, 4)]),
    ]
    variants = []
    for sweep_name, changes in matrix:
        for index, change in enumerate(changes):
            variant = sweep_variant_args(base, change)
            output_path = output_dir / "sweeps" / sweep_name / f"variant-{index}.json"
            variants.append({
                "sweep": sweep_name,
                "variant": index,
                "changes": change,
                "command": gpu_training_command(variant, output_path),
                "outputPath": str(output_path),
                "status": sweep_variant_status(sweep_name),
            })
    return {
        "status": "PLANNED",
        "variantCount": len(variants),
        "sweeps": sorted({variant["sweep"] for variant in variants}),
        "variants": variants,
    }


def sweep_base_args(args):
    namespace = argparse.Namespace(**vars(args))
    if namespace.profile == "smoke":
        namespace.profile = "gpu-training"
        defaults = {
            "samples": 1024,
            "repeat": 5,
            "work": 100,
            "height": 256,
            "width": 256,
            "resize": 256,
            "batch_size": 16,
            "runs": 3,
        }
        for name, value in defaults.items():
            setattr(namespace, name, value)
    return namespace


def sweep_variant_args(args, changes):
    namespace = argparse.Namespace(**vars(args))
    namespace.sweep_plan = "none"
    for key, value in changes.items():
        setattr(namespace, key, value)
    return namespace


def sweep_variant_status(sweep_name):
    if sweep_name == "worker":
        return "PLANNED_UNSUPPORTED_UNTIL_FAIR_MULTIPROCESSING"
    return "PLANNED_EXECUTABLE"


def run_report(command):
    env = dict(os.environ)
    package_root = str(Path(__file__).resolve().parent)
    env["PYTHONPATH"] = package_root + os.pathsep + env.get("PYTHONPATH", "")
    env["PYTHONDONTWRITEBYTECODE"] = "1"
    started = time.time()
    completed = subprocess.run(command, capture_output=True, text=True, env=env)
    return {
        "name": Path(command[1]).stem,
        "command": command,
        "returnCode": completed.returncode,
        "startedAt": started,
        "endedAt": time.time(),
        "outputPath": output_path_from_command(command),
        "stdoutTail": completed.stdout.splitlines()[-5:],
        "stderrTail": completed.stderr.splitlines()[-5:],
    }


def output_path_from_command(command):
    if "--output" not in command:
        return None
    index = command.index("--output") + 1
    return command[index] if index < len(command) else None


def load_report_data(reports):
    payloads = {}
    for report in reports:
        output_path = report.get("outputPath")
        if report["returnCode"] != 0 or output_path is None:
            continue
        path = Path(output_path)
        if not path.exists():
            continue
        payloads[report["name"]] = json.loads(path.read_text(encoding="utf-8"))
    return payloads


def reports_passed(reports, report_data):
    if not all(report["returnCode"] == 0 for report in reports):
        return False
    return all(payload.get("allPassed", True) for payload in report_data.values())


def evidence_audit(summary):
    gpu = summary.get("reportData", {}).get("benchmark_gpu_segmentation", {})
    checks = [
        audit_check("reproducibleCommands", commands_are_reproducible(summary.get("reports", [])), "every executed child report command includes a script or module"),
    ]
    if not gpu:
        checks.append(audit_check("gpuReportPresent", summary.get("profile") == "smoke" or summary.get("configuration", {}).get("planOnly"), "GPU report is required for GPU profiles"))
        return audit_result(checks, gpu)

    config = gpu.get("configuration", {})
    validity = gpu.get("validity", {})
    accelerator = gpu.get("accelerator", {})
    accelerator_backend = config.get("acceleratorBackend", "auto")
    expected_gpu = config.get("expectedGpu") or summary.get("configuration", {}).get("expectedGpu") or ""
    model = gpu.get("model", {})
    correctness = gpu.get("correctness", {})
    backend_names = set(gpu.get("backends", {}))
    run_count = gpu.get("runAggregate", {}).get("runs") or len(gpu.get("runs", []))
    checks.extend([
        audit_check("numpyAvailable", gpu.get("numpy", {}).get("available") is True, "NumPy imported in the benchmark interpreter"),
        audit_check("acceleratorBackend", accelerator_backend_supported(accelerator, accelerator_backend), "configured CUDA/ROCm PyTorch accelerator was active"),
        audit_check("expectedGpu", expected_gpu_matches(accelerator.get("deviceName") or "", expected_gpu), "GPU name matches configured expectation when one is provided"),
        audit_check("deviceSmoke", gpu.get("deviceSmokeTestPassed") is True, "GPU matmul smoke test passed"),
        audit_check("modelOnGpu", model.get("device") == "cuda:0", "model parameters are on the PyTorch CUDA device"),
        audit_check("allBackendsPresent", backend_names == {"RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"}, "all four required backends were measured"),
        audit_check("backendEquivalence", correctness.get("allChecksumsEqual") is True and all_backend_equivalence_passed(correctness), "all backends feed equivalent deterministic tensors"),
        audit_check("batchedAetherProtocol", gpu.get("protocol", {}).get("singleGetRequests") == 0 and gpu.get("protocol", {}).get("singlePutRequests") == 0, "Aether path uses batched protocol counters, not per-sample operations"),
        audit_check("deterministicInvalidation", validity.get("deterministicCacheInvalidation", {}).get("passed") is True, "cache key misses on source/transform changes"),
        audit_check("stepRecordsPresent", measured_step_records_present(gpu), "raw per-step timing records are retained"),
        audit_check("utilizationAccountedFor", utilization_accounted_for(gpu), "GPU utilization is sampled or explicitly recorded unavailable"),
        audit_check("processMetricsPresent", process_metrics_present(gpu), "CPU/RSS/disk process metrics are recorded where the OS exposes them"),
        audit_check("cacheDynamicsPresent", cache_dynamics_present(gpu), "Aether hit ratio, miss ratio, recomputed samples, and publish cost are reported"),
        audit_check("outcomePresent", outcome_present(gpu), "report explicitly separates pipeline-only signals from training-throughput signals"),
    ])
    final_scale = (
        gpu.get("profile") == "gpu-training"
        and config.get("samples", 0) >= 1024
        and config.get("resize", 0) >= 256
        and config.get("measuredStepsEffective", 0) >= 200
        and run_count >= 10
    )
    checks.append(audit_check("finalTrainingScale", final_scale, "final claim requires gpu-training scale: >=1024 samples, 256 resize, >=200 measured steps, 10 runs"))
    return audit_result(checks, gpu)


def audit_check(name, passed, detail):
    return {"name": name, "passed": bool(passed), "detail": detail}


def accelerator_backend_supported(accelerator, expected_backend):
    if not accelerator.get("deviceAvailable"):
        return False
    if expected_backend == "rocm":
        return bool(accelerator.get("hipVersion"))
    if expected_backend == "cuda":
        return bool(accelerator.get("cudaVersion") and not accelerator.get("hipVersion"))
    return bool(accelerator.get("cudaVersion") or accelerator.get("hipVersion"))


def expected_gpu_matches(device_name, expected_gpu):
    if not expected_gpu:
        return True
    expected = expected_gpu.lower()
    actual = device_name.lower()
    return expected in actual or ("amd radeon rx 7900" in expected and "amd radeon rx 7900" in actual)


def audit_result(checks, gpu):
    primary = gpu.get("comparisons", {}).get("primary", {}) if gpu else {}
    ratio = metric_value(primary.get("aetherSamplesPerSecondRatio"))
    outcome = gpu.get("outcome", {}) if gpu else {}
    throughput_signal = bool(outcome.get("flagPassRates", {}).get("samplesPerSecondImproved", 0) > 0.5)
    total_wall_signal = bool(outcome.get("flagPassRates", {}).get("totalWallReduced", 0) > 0.5)
    training_improvement_supported = all(check["passed"] for check in checks) and ratio is not None and ratio > 1.0 and throughput_signal and total_wall_signal
    return {
        "status": "COMPLETE_FOR_GPU_TRAINING_CLAIM" if training_improvement_supported else "INCOMPLETE_FOR_GPU_TRAINING_CLAIM",
        "checks": checks,
        "trainingImprovementSupported": training_improvement_supported,
        "aetherSamplesPerSecondRatio": ratio,
        "throughputSignalMajority": throughput_signal,
        "totalWallSignalMajority": total_wall_signal,
        "claimGuidance": "Do not describe Aether as improving GPU training unless trainingImprovementSupported is true.",
    }


def commands_are_reproducible(reports):
    for report in reports:
        command = report.get("command", [])
        if len(command) < 2:
            return False
        if "-m" in command:
            index = command.index("-m")
            if index + 1 < len(command):
                continue
        if len(command) >= 2 and str(command[1]).endswith(".py"):
            continue
        return False
    return True


def all_backend_equivalence_passed(correctness):
    equivalence = correctness.get("backendEquivalence", {})
    if set(equivalence) != {"RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"}:
        return False
    return all(
        result.get("sameShape") and result.get("sameDtype") and result.get("sameValues")
        for result in equivalence.values()
    )


def measured_step_records_present(gpu):
    for run in gpu.get("runs", []):
        for backend in ("RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"):
            if not run.get("backends", {}).get(backend, {}).get("steps"):
                return False
    return bool(gpu.get("runs"))


def utilization_accounted_for(gpu):
    for backend in gpu.get("backends", {}).values():
        sampling = backend.get("gpu", {}).get("sampling", {})
        statuses = sampling.get("statuses")
        status = sampling.get("status")
        if statuses:
            if not set(statuses).issubset({"SAMPLED", "UNAVAILABLE"}):
                return False
        elif status not in {"SAMPLED", "UNAVAILABLE"}:
            return False
    return bool(gpu.get("backends"))


def process_metrics_present(gpu):
    for backend in gpu.get("backends", {}).values():
        process = backend.get("process", {})
        if process.get("cpuUtilizationMean") is None:
            return False
        if "rssPeakBytes" not in process or "diskReadBytes" not in process or "diskWriteBytes" not in process:
            return False
    return bool(gpu.get("backends"))


def cache_dynamics_present(gpu):
    dynamics = gpu.get("cacheDynamics") or gpu.get("runAggregate", {}).get("cacheDynamics", {})
    required = ("hitRatio", "missRatio", "recomputedSamples", "publishedSamples", "publishCostMs", "lookupCostMs")
    return all(field in dynamics for field in required)


def outcome_present(gpu):
    outcome = gpu.get("outcome", {})
    return "statuses" in outcome and "flagPassRates" in outcome and "samplesPerSecondRatio" in outcome


def key_stats(report_data):
    benchmark = report_data.get("benchmark_aetherml", {})
    oct_workload = report_data.get("oct_segmentation_workload", {})
    crash = report_data.get("aetherml_crash_recovery", {})
    gpu_training = report_data.get("benchmark_gpu_segmentation", {})
    return {
        "aethermlBenchmark": {
            "baselines": {
                name: {
                    "samplesPerSecond": payload.get("samplesPerSecond"),
                    "inputWaitNanos": payload.get("inputWaitNanos"),
                    "simulatedGpuUtilization": payload.get("simulatedGpuUtilization"),
                }
                for name, payload in benchmark.get("baselines", {}).items()
            },
            "coldSamplesPerSecond": benchmark.get("coldTraining", {}).get("samplesPerSecond"),
            "warmSamplesPerSecond": benchmark.get("warmTraining", {}).get("samplesPerSecond"),
            "partialCacheSamplesPerSecond": benchmark.get("partialCacheTraining", {}).get("samplesPerSecond"),
            "operationMetrics": benchmark.get("operationMetrics"),
            "storage": benchmark.get("storage"),
            "snapshotReproducible": benchmark.get("snapshotVerification", {}).get("isReproducible"),
            "recoveryConsistent": benchmark.get("recovery", {}).get("isConsistent"),
        },
        "octSegmentation": {
            "dataset": oct_workload.get("configuration", {}).get("dataset"),
            "datasetVersion": oct_workload.get("environment", {}).get("datasetVersion"),
            "coldMetrics": oct_workload.get("coldTraining", {}).get("metrics"),
            "warmMetrics": oct_workload.get("warmTraining", {}).get("metrics"),
            "snapshotReproducible": oct_workload.get("datasetSnapshotVerification", {}).get("isReproducible"),
            "storage": oct_workload.get("storage"),
        },
        "crashRecovery": {
            "trials": crash.get("trials"),
            "faultCounts": crash.get("faultCounts"),
            "postRecoveryConsistent": crash.get("postRecoveryConsistent"),
            "acknowledgedArtifacts": crash.get("acknowledgedArtifacts"),
            "recoveredArtifacts": crash.get("recoveredArtifacts"),
            "acknowledgedArtifactsLoaded": crash.get("acknowledgedArtifactsLoaded"),
            "lostAcknowledgedArtifacts": crash.get("lostAcknowledgedArtifacts"),
            "successfulRecoveries": crash.get("successfulRecoveries"),
        },
        "gpuTraining": {
            "status": gpu_training.get("status"),
            "accelerator": gpu_training.get("accelerator"),
            "correctness": gpu_training.get("correctness"),
            "model": gpu_training.get("model"),
            "runs": gpu_training.get("runAggregate", {}).get("runs") or len(gpu_training.get("runs", [])),
            "validity": gpu_training.get("validity"),
            "aetherOperationMetrics": gpu_training.get("runAggregate", {}).get("aetherOperationMetrics"),
            "cacheDynamics": gpu_training.get("cacheDynamics") or gpu_training.get("runAggregate", {}).get("cacheDynamics"),
            "outcome": gpu_training.get("outcome") or gpu_training.get("runAggregate", {}).get("outcome"),
            "backends": {
                name: {
                    "samplesPerSecond": metric_value(payload.get("steadyState", {}).get("samplesPerSecond")),
                    "samplesPerSecondDistribution": payload.get("steadyState", {}).get("samplesPerSecond"),
                    "totalTrainingWallMs": metric_value(payload.get("lifecycle", {}).get("trainingMs")),
                    "inputWaitPercent": metric_value(payload.get("steadyState", {}).get("inputWaitPercent")),
                    "peakDeviceMemoryBytes": payload.get("gpu", {}).get("peakMemoryBytes"),
                    "gpuUtilizationMean": payload.get("gpu", {}).get("utilizationMean"),
                    "gpuUtilizationP95": payload.get("gpu", {}).get("utilizationP95"),
                    "gpuSampling": payload.get("gpu", {}).get("sampling"),
                    "cpuUtilizationMean": metric_value(payload.get("process", {}).get("cpuUtilizationMean")),
                    "rssPeakBytes": metric_value(payload.get("process", {}).get("rssPeakBytes")),
                    "diskReadBytes": metric_value(payload.get("process", {}).get("diskReadBytes")),
                    "diskWriteBytes": metric_value(payload.get("process", {}).get("diskWriteBytes")),
                }
                for name, payload in gpu_training.get("backends", {}).items()
            },
        },
    }


def metric_value(value):
    if isinstance(value, dict) and "mean" in value:
        return value["mean"]
    return value


def console_summary(summary):
    return {
        "benchmark": summary.get("benchmark"),
        "profile": summary.get("profile"),
        "allPassed": summary.get("allPassed"),
        "combinedReportPath": summary.get("combinedReportPath"),
        "keyStats": summary.get("keyStats"),
    }


def export_tidy_reports(summary, output_dir):
    report_data = summary.get("reportData", {})
    gpu = report_data.get("benchmark_gpu_segmentation", {})
    backends = gpu.get("backends", {})
    backend_rows = []
    step_rows = []
    run_rows = []
    cache_dynamics_rows = []
    for backend_name, backend in backends.items():
        steady = backend.get("steadyState", {})
        lifecycle = backend.get("lifecycle", {})
        timing = backend.get("timing", {})
        backend_rows.append({
            "profile": summary.get("profile"),
            "backend": backend_name,
            "runs": backend.get("runs"),
            "populateMsMean": metric_value(lifecycle.get("populateMs")),
            "trainingMsMean": metric_value(lifecycle.get("trainingMs")),
            "totalMsMean": metric_value(lifecycle.get("totalMs")),
            "samplesPerSecondMean": metric_value(steady.get("samplesPerSecond")),
            "samplesPerSecondP50": metric_field(steady.get("samplesPerSecond"), "p50"),
            "samplesPerSecondP95": metric_field(steady.get("samplesPerSecond"), "p95"),
            "samplesPerSecondStdev": metric_field(steady.get("samplesPerSecond"), "standardDeviation"),
            "samplesPerSecondConfidence95": metric_field(steady.get("samplesPerSecond"), "confidence95"),
            "stepsPerSecondMean": metric_value(steady.get("stepsPerSecond")),
            "pixelsPerSecondMean": metric_value(steady.get("pixelsPerSecond")),
            "inputWaitPercentMean": metric_value(steady.get("inputWaitPercent")),
            "hostToDeviceMsMean": metric_value(timing.get("hostToDeviceMs")),
            "prefetchWaitMsMean": metric_value(timing.get("prefetchWaitMs")),
            "gpuUtilizationMean": gpu_metric(backend, "utilizationMean"),
            "gpuUtilizationP95": gpu_metric(backend, "utilizationP95"),
            "gpuMemoryUtilizationMean": gpu_metric(backend, "memoryUtilizationMean"),
            "gpuSamplingStatus": ",".join(gpu_metric(backend, "sampling", {}).get("statuses", [])) if isinstance(gpu_metric(backend, "sampling"), dict) else gpu_metric(backend, "sampling", {}).get("status"),
            "gpuSamplingSource": ",".join(gpu_metric(backend, "sampling", {}).get("sources", [])) if isinstance(gpu_metric(backend, "sampling"), dict) else gpu_metric(backend, "sampling", {}).get("source"),
            "cpuUtilizationMean": metric_value(process_metric(backend, "cpuUtilizationMean")),
            "rssPeakBytes": metric_value(process_metric(backend, "rssPeakBytes")),
            "diskReadBytes": metric_value(process_metric(backend, "diskReadBytes")),
            "diskWriteBytes": metric_value(process_metric(backend, "diskWriteBytes")),
            "forwardMsMean": metric_value(timing.get("forwardMs")),
            "backwardMsMean": metric_value(timing.get("backwardMs")),
            "optimizerMsMean": metric_value(timing.get("optimizerMs")),
        })
        if not gpu.get("runs"):
            for step in backend.get("steps", []):
                step_rows.append(step_row(summary, None, None, backend_name, step))
    for run in gpu.get("runs", []):
        if run.get("cacheDynamics"):
            cache_dynamics_rows.append(cache_dynamics_row(summary, run.get("runIndex"), run.get("seed"), run.get("cacheDynamics")))
        for backend_name, backend in run.get("backends", {}).items():
            steady = backend.get("steadyState", {})
            lifecycle = backend.get("lifecycle", {})
            run_rows.append({
                "profile": summary.get("profile"),
                "runIndex": run.get("runIndex"),
                "seed": run.get("seed"),
                "backend": backend_name,
                "populateMs": lifecycle.get("populateMs"),
                "trainingMs": lifecycle.get("trainingMs"),
                "totalMs": lifecycle.get("totalMs"),
                "samplesPerSecond": steady.get("samplesPerSecond"),
                "stepsPerSecond": steady.get("stepsPerSecond"),
                "pixelsPerSecond": steady.get("pixelsPerSecond"),
                "inputWaitPercent": steady.get("inputWaitPercent"),
                "gpuUtilizationMean": backend.get("gpu", {}).get("utilizationMean"),
                "gpuUtilizationP95": backend.get("gpu", {}).get("utilizationP95"),
                "cpuUtilizationMean": backend.get("process", {}).get("cpuUtilizationMean"),
                "rssPeakBytes": backend.get("process", {}).get("rssPeakBytes"),
                "diskReadBytes": backend.get("process", {}).get("diskReadBytes"),
                "diskWriteBytes": backend.get("process", {}).get("diskWriteBytes"),
            })
            for step in backend.get("steps", []):
                step_rows.append(step_row(summary, run.get("runIndex"), run.get("seed"), backend_name, step))
    export_manifest = {
        "backendSummaryCsv": write_csv(output_dir / "tidy-gpu-backends.csv", backend_rows),
        "runSummaryCsv": write_csv(output_dir / "tidy-gpu-runs.csv", run_rows),
        "stepRecordsCsv": write_csv(output_dir / "tidy-gpu-steps.csv", step_rows),
        "cacheDynamicsCsv": write_csv(output_dir / "tidy-gpu-cache-dynamics.csv", cache_dynamics_rows),
    }
    oct5k_summary_rows = oct5k_summary(summary)
    if oct5k_summary_rows:
        export_manifest["oct5kSummaryCsv"] = write_csv(output_dir / "oct5k-summary.csv", oct5k_summary_rows)
    figure_exports = export_gpu_figures(summary, output_dir)
    if figure_exports:
        export_manifest["figures"] = figure_exports
    (output_dir / "exports.json").write_text(json.dumps(export_manifest, indent=2) + "\n", encoding="utf-8")
    summary["exports"] = export_manifest
    (output_dir / "summary.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    (output_dir / "aetherml-report.json").write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")


def write_csv(path, rows):
    if not rows:
        return None
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    return str(path)


def export_gpu_figures(summary, output_dir):
    gpu = summary.get("reportData", {}).get("benchmark_gpu_segmentation", {})
    backends = gpu.get("backends", {})
    if not backends:
        return {}
    try:
        import matplotlib.pyplot as plt
    except Exception:
        return {"status": "SKIPPED_MATPLOTLIB_UNAVAILABLE"}
    figures_dir = output_dir / "figures"
    figures_dir.mkdir(parents=True, exist_ok=True)
    exports = {}
    labels = [name for name in ("RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY") if name in backends]
    if labels:
        path = figures_dir / "warm-throughput.png"
        plot_bar(
            plt,
            labels,
            [metric_value(backends[name].get("steadyState", {}).get("samplesPerSecond")) for name in labels],
            "samples/s",
            path,
        )
        exports["warmThroughputPng"] = str(path)
        path = figures_dir / "gpu-utilization.png"
        plot_bar(
            plt,
            labels,
            [metric_value(backends[name].get("gpu", {}).get("utilizationMean")) or 0 for name in labels],
            "nvidia-smi sampled GPU utilization %",
            path,
        )
        exports["gpuUtilizationPng"] = str(path)
    raw = backends.get("RAW_RECOMPUTE")
    aether = backends.get("AETHER_CACHE")
    if raw and aether:
        raw_epoch = metric_value(raw.get("steadyState", {}).get("meanEpochMs")) or 0
        aether_epoch = metric_value(aether.get("steadyState", {}).get("meanEpochMs")) or 0
        aether_populate = metric_value(aether.get("lifecycle", {}).get("populateMs")) or 0
        epochs = [1, 2, 3, 4, 5, 10, 15, 18, 20, 25, 30, 40, 50]
        deltas = [(aether_populate + aether_epoch * epoch) - raw_epoch * epoch for epoch in epochs]
        path = figures_dir / "lifecycle-crossover.png"
        plot_line(plt, epochs, deltas, "epochs", "Aether - RAW lifecycle wall ms", path, zero_line=True)
        exports["lifecycleCrossoverPng"] = str(path)
        path = figures_dir / "cumulative-lifecycle.png"
        cumulative = aether.get("lifecycle", {}).get("cumulativeByEpochMs", [])
        if cumulative:
            plot_line(
                plt,
                [point["epoch"] for point in cumulative],
                [point["totalMs"] for point in cumulative],
                "epoch",
                "Aether cumulative lifecycle ms",
                path,
            )
            exports["cumulativeLifecyclePng"] = str(path)
        path = figures_dir / "publication-cost.png"
        plot_bar(
            plt,
            ["publish", "lookup", "avoided_preprocess"],
            [
                metric_value(aether.get("timing", {}).get("aetherPublishMs")) or 0,
                metric_value(aether.get("timing", {}).get("aetherLookupMs")) or 0,
                max(0, (metric_value(raw.get("timing", {}).get("preprocessMs")) or 0) - (metric_value(aether.get("timing", {}).get("artifactDecodeMs")) or 0)),
            ],
            "ms / batch",
            path,
        )
        exports["publicationCostPng"] = str(path)
    return exports


def plot_bar(plt, labels, values, ylabel, path):
    plt.figure(figsize=(8, 4.5))
    plt.bar(labels, values, color=["#4b5563", "#2563eb", "#059669", "#d97706"][:len(labels)])
    plt.ylabel(ylabel)
    plt.xticks(rotation=20, ha="right")
    plt.tight_layout()
    plt.savefig(path, dpi=160)
    plt.close()


def plot_line(plt, xs, ys, xlabel, ylabel, path, zero_line=False):
    plt.figure(figsize=(8, 4.5))
    plt.plot(xs, ys, marker="o", color="#2563eb")
    if zero_line:
        plt.axhline(0, color="#111827", linewidth=1)
    plt.xlabel(xlabel)
    plt.ylabel(ylabel)
    plt.tight_layout()
    plt.savefig(path, dpi=160)
    plt.close()


def oct5k_summary(summary):
    if summary.get("configuration", {}).get("datasetKind") != "oct5k":
        return []
    gpu = summary.get("reportData", {}).get("benchmark_gpu_segmentation", {})
    backends = gpu.get("backends", {})
    rows = []
    for backend_name, backend in backends.items():
        steady = backend.get("steadyState", {})
        lifecycle = backend.get("lifecycle", {})
        cold_start = backend.get("coldStart") or {}
        segmentation = backend.get("segmentationMetrics") or {}
        admission = gpu.get("admissionModel") or {}
        rows.append({
            "profile": summary.get("profile"),
            "backend": backend_name,
            "samples": summary.get("configuration", {}).get("samples"),
            "epochs": summary.get("configuration", {}).get("repeat"),
            "runs": summary.get("configuration", {}).get("runs"),
            "datasetManifest": summary.get("configuration", {}).get("datasetManifest"),
            "datasetSplit": summary.get("configuration", {}).get("datasetSplit"),
            "oct5kTransformVersion": summary.get("configuration", {}).get("oct5kTransformVersion"),
            "augmentationMode": summary.get("configuration", {}).get("augmentationMode"),
            "samplesPerSecondMean": metric_value(steady.get("samplesPerSecond")),
            "meanEpochMs": metric_value(steady.get("meanEpochMs")),
            "populateMsMean": metric_value(lifecycle.get("populateMs")),
            "trainingMsMean": metric_value(lifecycle.get("trainingMs")),
            "totalMsMean": metric_value(lifecycle.get("totalMs")),
            "inputWaitPercentMean": metric_value(steady.get("inputWaitPercent")),
            "segmentationLoss": segmentation.get("loss"),
            "dice": segmentation.get("dice"),
            "meanIoU": segmentation.get("meanIoU"),
            "coldStartPassed": cold_start.get("passed"),
            "measuredKeysAlreadyPresent": cold_start.get("measuredKeysAlreadyPresent"),
            "zeroMarginPredictedBreakEvenEpoch": metric_value(admission.get("zeroMarginPredictedBreakEvenEpoch")),
            "onePercentMarginPredictedBreakEvenEpoch": metric_value(admission.get("onePercentMarginPredictedBreakEvenEpoch")),
            "observedSampledCrossoverEpoch": metric_value(admission.get("observedSampledCrossoverEpoch")),
            "predictionErrorEpochs": metric_value(admission.get("predictionErrorEpochs")),
        })
    return rows


def step_row(summary, run_index, seed, backend_name, step):
    layout = step.get("tensorLayout", {})
    image_layout = layout.get("images", {})
    mask_layout = layout.get("masks", {})
    return {
        "profile": summary.get("profile"),
        "runIndex": run_index,
        "seed": seed,
        "backend": backend_name,
        "epoch": step.get("epoch"),
        "step": step.get("step"),
        "batchSize": step.get("batchSize"),
        "inputWaitMs": step.get("inputWaitMs"),
        "batchPrepareMs": step.get("batchPrepareMs"),
        "sourceLoadMs": step.get("sourceLoadMs"),
        "preprocessMs": step.get("preprocessMs"),
        "aetherLookupMs": step.get("aetherLookupMs"),
        "aetherPublishMs": step.get("aetherPublishMs"),
        "mmapReadMs": step.get("mmapReadMs"),
        "tensorBuildMs": step.get("tensorBuildMs"),
        "artifactDecodeMs": step.get("artifactDecodeMs"),
        "randomAugmentationMs": step.get("randomAugmentationMs"),
        "prefetchWaitMs": step.get("prefetchWaitMs"),
        "hostToDeviceMs": step.get("hostToDeviceMs"),
        "forwardMs": step.get("forwardMs"),
        "lossMs": step.get("lossMs"),
        "backwardMs": step.get("backwardMs"),
        "optimizerMs": step.get("optimizerMs"),
        "stepWallMs": step.get("stepWallMs"),
        "loss": step.get("loss"),
        "imageIsContiguous": image_layout.get("isContiguous"),
        "imageStride": image_layout.get("stride"),
        "imageIsPinned": image_layout.get("isPinned"),
        "imageStorageOffset": image_layout.get("storageOffset"),
        "imageMemoryFormat": image_layout.get("memoryFormat"),
        "imageDataPtrModulo64": image_layout.get("dataPtrModulo64"),
        "imageDataPtrModulo256": image_layout.get("dataPtrModulo256"),
        "maskIsContiguous": mask_layout.get("isContiguous"),
        "maskStride": mask_layout.get("stride"),
        "maskIsPinned": mask_layout.get("isPinned"),
        "maskStorageOffset": mask_layout.get("storageOffset"),
        "maskMemoryFormat": mask_layout.get("memoryFormat"),
        "maskDataPtrModulo64": mask_layout.get("dataPtrModulo64"),
        "maskDataPtrModulo256": mask_layout.get("dataPtrModulo256"),
    }


def cache_dynamics_row(summary, run_index, seed, dynamics):
    return {
        "profile": summary.get("profile"),
        "runIndex": run_index,
        "seed": seed,
        "configuredChangedPercent": dynamics.get("configuredChangedPercent"),
        "configuredInitialCacheHitRatio": dynamics.get("configuredInitialCacheHitRatio"),
        "prepopulatePreviousVersion": dynamics.get("prepopulatePreviousVersion"),
        "targetInitialCacheHitRatio": dynamics.get("targetInitialCacheHitRatio"),
        "prepopulatedEntries": dynamics.get("prepopulatedEntries"),
        "lookups": dynamics.get("lookups"),
        "hits": dynamics.get("hits"),
        "misses": dynamics.get("misses"),
        "hitRatio": dynamics.get("hitRatio"),
        "missRatio": dynamics.get("missRatio"),
        "recomputedSamples": dynamics.get("recomputedSamples"),
        "publishedSamples": dynamics.get("publishedSamples"),
        "publishCostMs": dynamics.get("publishCostMs"),
        "lookupCostMs": dynamics.get("lookupCostMs"),
        "bytesRead": dynamics.get("bytesRead"),
        "bytesWritten": dynamics.get("bytesWritten"),
        "writeBatchCount": dynamics.get("writeBatchCount"),
        "entriesPerWriteBatchMean": dynamics.get("entriesPerWriteBatchMean"),
        "invariantsPassed": (dynamics.get("invariants") or {}).get("passed"),
        "expectedLookups": (dynamics.get("invariants") or {}).get("expectedLookups"),
        "expectedHits": (dynamics.get("invariants") or {}).get("expectedHits"),
        "expectedMisses": (dynamics.get("invariants") or {}).get("expectedMisses"),
        "expectedPublishedSamples": (dynamics.get("invariants") or {}).get("expectedPublishedSamples"),
    }


def metric_field(value, field):
    if isinstance(value, dict):
        return value.get(field)
    return value if field == "mean" else None


def gpu_metric(backend, field, default=None):
    return backend.get("gpu", {}).get(field, default)


def process_metric(backend, field, default=None):
    return backend.get("process", {}).get(field, default)


def script_path(name):
    return str(Path(__file__).resolve().with_name(name))


if __name__ == "__main__":
    main()
