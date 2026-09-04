import argparse
import copy
import csv
import hashlib
import json
import math
import mmap
import os
import platform
import queue
import random
import re
import shutil
import statistics
import struct
import subprocess
import sys
import tempfile
import threading
import time
from pathlib import Path

from aetherml import AetherMLStore, environment_report
from oct_segmentation_workload import discover_input_files, oct_sized_bytes, synthetic_oct_bytes


BACKENDS = ("RAW_RECOMPUTE", "AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY")
TIMING_MODE = "wall+synchronize"


def main():
    args = parse_args()
    report = run_benchmark(args)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({
        "benchmark": report["benchmark"],
        "profile": report["profile"],
        "status": report["status"],
        "allPassed": report["allPassed"],
        "output": str(output),
        "reason": report.get("skipReason"),
    }, indent=2))
    print(f"Report: {output}")


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Run CUDA/ROCm-gated OCT segmentation GPU training benchmark.")
    parser.add_argument("--profile", choices=["gpu-acceptance", "gpu-training"], default="gpu-training")
    parser.add_argument("--samples", type=int, default=1024)
    parser.add_argument("--height", type=int, default=256)
    parser.add_argument("--width", type=int, default=256)
    parser.add_argument("--resize", type=int, default=256)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--changed-percent", type=float, default=30.0)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--input-dir")
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
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--preprocess-passes", type=int, default=1)
    parser.add_argument("--initial-cache-hit-ratio", type=float, default=0.0)
    parser.add_argument("--aether-cache-dir")
    parser.add_argument("--aether-cache-mode", choices=["fresh", "reuse"], default="fresh")
    parser.add_argument("--aether-populate-only", action="store_true")
    parser.add_argument("--prepopulate-previous-version", action="store_true")
    parser.add_argument("--prefetch-batches", type=int, default=0)
    parser.add_argument("--augmentation-mode", choices=["auto", "none", "light"], default="auto")
    parser.add_argument("--gpu-sample-interval-ms", type=float, default=250.0)
    parser.add_argument("--output", default="build/gpu-training.json")
    args = parser.parse_args(argv)
    if args.oct5k_image_size is not None:
        args.resize = args.oct5k_image_size
    if args.augmentation_mode == "auto":
        args.augmentation_mode = "light" if args.dataset_kind == "oct5k" else "none"
    args.invocation_args = list(sys.argv[1:] if argv is None else argv)
    validate_args(args)
    return args


def validate_args(args):
    if args.samples < 1 or args.height < 4 or args.width < 4 or args.resize < 4:
        raise ValueError("samples, height, width, and resize must be positive and at least 4 where applicable")
    if args.batch_size < 1 or args.epochs < 1 or args.runs < 1 or args.warmup_steps < 0 or args.measured_steps < 0:
        raise ValueError("batch-size, epochs, and runs must be positive; warmup/measured steps must be non-negative")
    if args.preprocess_passes < 1:
        raise ValueError("preprocess-passes must be positive")
    if args.prefetch_batches < 0:
        raise ValueError("prefetch-batches must be non-negative")
    if args.gpu_sample_interval_ms < 0:
        raise ValueError("gpu-sample-interval-ms must be non-negative")
    if args.workers != 0:
        raise ValueError("gpu-training currently requires workers=0 until fair multiprocessing is implemented")
    if not 0 <= args.changed_percent <= 100:
        raise ValueError("changed-percent must be between 0 and 100")
    if not 0 <= args.initial_cache_hit_ratio <= 100:
        raise ValueError("initial-cache-hit-ratio must be between 0 and 100")
    if args.aether_cache_mode not in {"fresh", "reuse"}:
        raise ValueError("aether-cache-mode must be either fresh or reuse")
    if args.input_dir is not None and not Path(args.input_dir).is_dir():
        raise ValueError("input-dir must be an existing directory")
    if args.dataset_kind == "oct5k":
        if not args.dataset_manifest:
            raise ValueError("dataset-kind=oct5k requires --dataset-manifest")
        if not Path(args.dataset_manifest).is_file():
            raise ValueError("dataset-manifest must be an existing CSV file")
    if args.oct5k_image_size is not None and args.oct5k_image_size < 4:
        raise ValueError("oct5k-image-size must be at least 4")


def run_benchmark(args):
    started = time.time()
    numpy_status = import_numpy_status()
    accelerator = accelerator_report()
    base = {
        "benchmark": "aether-bench",
        "profile": args.profile,
        "dataset": "oct5k" if args.dataset_kind == "oct5k" else "oct",
        "pipeline": "segmentation",
        "status": "UNKNOWN",
        "startedAt": started,
        "endedAt": None,
        "command": [sys.executable, str(Path(__file__).resolve()), *args.invocation_args],
        "environment": environment_report(dataset_version=dataset_version(args), code_commit="gpu-training", root=Path.cwd()),
        "accelerator": accelerator,
        "configuration": configuration(args),
        "numpy": numpy_status,
        "storage": storage_locations(args),
        "model": {},
        "backends": {},
        "protocol": {},
        "correctness": {"allChecksumsEqual": False},
        "validity": validity_checks(args, accelerator, numpy_status),
        "runs": [],
        "runAggregate": {},
        "allPassed": False,
    }
    skip_reason = unsupported_reason(numpy_status, accelerator, args.accelerator_backend, args.expected_gpu)
    if skip_reason:
        base.update({
            "status": "SKIPPED_UNSUPPORTED_ACCELERATOR",
            "skipReason": skip_reason,
            "endedAt": time.time(),
        })
        return base

    import torch
    import numpy as np

    if args.aether_populate_only:
        context = BackendContext(args, [artifact_to_tensor_sample(preprocess_sample(sample, args, np), np) for sample in load_sources(args)], load_sources(args), np)
        populate_summary = context.populate_aether_dataset()
        base.update({
            "status": "PASSED",
            "endedAt": time.time(),
            "populateOnly": populate_summary,
            "allPassed": True,
        })
        return base

    device = torch.device("cuda")
    device_smoke = gpu_smoke_test(torch, device)
    runs = [run_training_once(args, torch, np, device, run_index) for run_index in range(args.runs)]
    aggregate = aggregate_runs(runs)
    representative = runs[0]
    crash_reference = {"report": "aetherml_crash_recovery", "successCriterion": "lostAcknowledgedArtifacts = 0"}
    base.update({
        "status": "PASSED",
        "endedAt": time.time(),
        "deviceSmokeTestPassed": device_smoke,
        "model": representative["model"],
        "backendOrder": representative["backendOrder"],
        "backends": aggregate["backends"],
        "protocol": aggregate["protocol"],
        "comparisons": aggregate["comparisons"],
        "trainingBreakEvenEpoch": aggregate["trainingBreakEvenEpoch"],
        "cacheDynamics": aggregate["cacheDynamics"],
        "outcome": aggregate["outcome"],
        "breakEvenMargin": 0.01,
        "admissionModel": aggregate["admissionModel"],
        "correctness": {
            "allChecksumsEqual": True,
            "checksums": representative["checksums"],
            "samplesCheckedElementwise": representative["samplesCheckedElementwise"],
            "backendEquivalence": representative["backendEquivalence"],
        },
        "datasetIntegrity": representative["datasetIntegrity"],
        "runs": runs,
        "runAggregate": aggregate,
        "crashRecoveryReference": crash_reference,
        "allPassed": True,
    })
    return base


def run_training_once(args, torch, np, device, run_index):
    run_seed = args.seed + run_index
    samples = load_sources(args)
    reference = [artifact_to_tensor_sample(preprocess_sample(sample, args, np), np) for sample in samples]
    checksums = dataset_checksums(reference)
    assert_equivalent_inputs(reference, checksums)
    measured_steps = effective_measured_steps(args)
    set_seed(torch, run_seed)
    model = create_model(args.model_tier, torch).to(device)
    initial_state = copy.deepcopy(model.state_dict())
    model_info = model_metadata(model, args, torch)
    backend_order = list(BACKENDS)
    random.Random(run_seed).shuffle(backend_order)
    context = BackendContext(args, reference, samples, np)
    context.run_seed = run_seed
    validation_args = copy.copy(args)
    validation_args.aether_cache_dir = None
    validation_args.aether_cache_mode = "fresh"
    validation_args.initial_cache_hit_ratio = 0.0
    validation_context = BackendContext(validation_args, reference, samples, np)
    try:
        validation_context.run_seed = run_seed
        equivalence = validate_backend_equivalence(validation_context, reference)
    finally:
        validation_context.close()

    try:
        results = {}
        for backend in backend_order:
            set_seed(torch, run_seed)
            model = create_model(args.model_tier, torch).to(device)
            model.load_state_dict(initial_state)
            optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
            loss_function = torch.nn.BCEWithLogitsLoss()
            warm_backend(context, backend)
            run_model_warmup(torch, model, loss_function, optimizer, device, args)
            results[backend] = run_backend(torch, model, optimizer, loss_function, device, backend, context, measured_steps)
        protocol = context.protocol_counters()
        dynamics = cache_dynamics(protocol, args)
        if not dynamics["invariants"]["passed"]:
            raise RuntimeError(f"Aether cache dynamics invariant failed: {dynamics['invariants']}")
        return {
            "runIndex": run_index,
            "seed": run_seed,
            "backendOrder": backend_order,
            "model": model_info,
            "backends": results,
            "protocol": protocol,
            "cacheDynamics": dynamics,
            "aetherOperationMetrics": context.store.operation_metrics(),
            "comparisons": compare_backends(results),
            "trainingBreakEvenEpoch": training_break_even_epoch(results),
            "admissionModel": admission_model(results),
            "outcome": training_outcome(results),
            "checksums": checksums,
            "samplesCheckedElementwise": min(8, len(reference)),
            "backendEquivalence": equivalence,
            "datasetIntegrity": dataset_integrity_summary(args, samples),
        }
    finally:
        context.close()


def validity_checks(args, accelerator, numpy_status):
    return {
        "noCpuFallback": {
            "passed": True,
            "detail": "Benchmark skips instead of running performance measurements without CUDA-compatible device support.",
        },
        "requiresAcceleratorBackend": {
            "passed": accelerator_backend_matches(accelerator, args.accelerator_backend),
            "expectedBackend": args.accelerator_backend,
            "actualBackend": accelerator.get("backend"),
            "cudaVersion": accelerator.get("cudaVersion"),
            "hipVersion": accelerator.get("hipVersion"),
        },
        "expectedGpu": {
            "passed": gpu_name_matches(accelerator.get("deviceName") or "", args.expected_gpu),
            "expectedGpu": args.expected_gpu,
            "deviceName": accelerator.get("deviceName"),
        },
        "numpyAvailableInInterpreter": {
            "passed": bool(numpy_status.get("available")),
            "version": numpy_status.get("version"),
            "error": numpy_status.get("error"),
        },
        "deterministicCacheInvalidation": cache_invalidation_self_test(args),
        "timing": {
            "mode": TIMING_MODE,
            "synchronizesDeviceBoundaries": True,
            "hostToDeviceSeparated": True,
            "inputPipelineSeparated": True,
        },
    }


def cache_invalidation_self_test(args):
    base_params = deterministic_parameters(args)
    resize_args = copy.copy(args)
    resize_args.resize = args.resize + 1
    resize_params = deterministic_parameters(resize_args)
    normalization_params = dict(base_params)
    normalization_params["normalizationVersion"] = "zscore-v2"
    implementation_params = dict(base_params)
    implementation_params["implementationOutputVersion"] = "gpu-segmentation-v2"
    transform_version_params = dict(base_params)
    transform_version_params["transformImplementationVersion"] = "oct5k-v2"
    denoise_params = dict(base_params)
    denoise_params["denoiseRadius"] = 2.0
    artifact_params = dict(base_params)
    artifact_params["artifactEncodingVersion"] = "nchw-fp32-binary-v2"
    base_key = test_cache_key(base_params, source_identity="synthetic:42:0", source_hash="raw-a")
    result = {
        "sameInputSameTransformStable": base_key == test_cache_key(base_params, source_identity="synthetic:42:0", source_hash="raw-a"),
        "sourceIdentityChangeMisses": base_key != test_cache_key(base_params, source_identity="synthetic:42:1", source_hash="raw-a"),
        "sourceHashChangeMisses": base_key != test_cache_key(base_params, source_identity="synthetic:42:0", source_hash="raw-b"),
        "resizeChangeMisses": base_key != test_cache_key(resize_params, source_identity="synthetic:42:0", source_hash="raw-a"),
        "normalizationChangeMisses": base_key != test_cache_key(normalization_params, source_identity="synthetic:42:0", source_hash="raw-a"),
        "denoiseConfigChangeMisses": base_key != test_cache_key(denoise_params, source_identity="synthetic:42:0", source_hash="raw-a"),
        "transformVersionChangeMisses": base_key != test_cache_key(transform_version_params, source_identity="synthetic:42:0", source_hash="raw-a"),
        "artifactEncodingChangeMisses": base_key != test_cache_key(artifact_params, source_identity="synthetic:42:0", source_hash="raw-a"),
        "implementationVersionChangeMisses": base_key != test_cache_key(implementation_params, source_identity="synthetic:42:0", source_hash="raw-a"),
    }
    result["passed"] = all(result.values())
    return result


def test_cache_key(parameters, *, source_identity, source_hash):
    descriptor = json.dumps({
        "sample_id": "oct-validation-sample",
        "source_identity": source_identity,
        "source_hash": source_hash,
        "deterministic_parameters": parameters,
    }, sort_keys=True)
    return hashlib.sha256(descriptor.encode("utf-8")).hexdigest()


def aggregate_runs(runs):
    aggregate_backends = {}
    for backend in BACKENDS:
        backend_runs = [run["backends"][backend] for run in runs if backend in run.get("backends", {})]
        if backend_runs:
            aggregate_backends[backend] = aggregate_backend_runs(backend, backend_runs)
    return {
        "runs": len(runs),
        "backends": aggregate_backends,
        "protocol": aggregate_protocol([run.get("protocol", {}) for run in runs]),
        "aetherOperationMetrics": aggregate_operation_metrics([run.get("aetherOperationMetrics", {}) for run in runs]),
        "cacheDynamics": aggregate_cache_dynamics([run.get("cacheDynamics", {}) for run in runs]),
        "comparisons": aggregate_comparisons([run.get("comparisons", {}) for run in runs]),
        "trainingBreakEvenEpoch": aggregate_break_even([run.get("trainingBreakEvenEpoch") for run in runs]),
        "admissionModel": aggregate_admission_models([run.get("admissionModel") for run in runs]),
        "outcome": aggregate_outcomes([run.get("outcome", {}) for run in runs]),
    }


def aggregate_backend_runs(backend, backend_runs):
    representative = copy.deepcopy(backend_runs[0])
    representative["runs"] = len(backend_runs)
    representative["lifecycle"] = {
        "populateMs": distribution([run["lifecycle"]["populateMs"] for run in backend_runs]),
        "trainingMs": distribution([run["lifecycle"]["trainingMs"] for run in backend_runs]),
        "totalTrainingWallMs": distribution([run["lifecycle"]["totalTrainingWallMs"] for run in backend_runs]),
        "totalMs": distribution([run["lifecycle"]["totalMs"] for run in backend_runs]),
    }
    representative["steadyState"] = {
        "meanEpochMs": distribution([run["steadyState"]["meanEpochMs"] for run in backend_runs]),
        "samplesPerSecond": distribution([run["steadyState"]["samplesPerSecond"] for run in backend_runs]),
        "effectiveSamplesPerSecond": distribution([run["steadyState"]["effectiveSamplesPerSecond"] for run in backend_runs]),
        "stepsPerSecond": distribution([run["steadyState"]["stepsPerSecond"] for run in backend_runs]),
        "pixelsPerSecond": distribution([run["steadyState"]["pixelsPerSecond"] for run in backend_runs]),
        "effectiveMegapixelsPerSecond": distribution([run["steadyState"]["effectiveMegapixelsPerSecond"] for run in backend_runs]),
        "inputWaitPercent": distribution([run["steadyState"]["inputWaitPercent"] for run in backend_runs]),
    }
    timing_fields = set()
    for run in backend_runs:
        timing_fields.update(run.get("timing", {}))
    representative["timing"] = {
        field: distribution([run.get("timing", {}).get(field, 0) for run in backend_runs])
        for field in sorted(timing_fields)
    }
    utilization_means = [run.get("gpu", {}).get("utilizationMean") for run in backend_runs if run.get("gpu", {}).get("utilizationMean") is not None]
    utilization_p50s = [run.get("gpu", {}).get("utilizationP50") for run in backend_runs if run.get("gpu", {}).get("utilizationP50") is not None]
    utilization_p95s = [run.get("gpu", {}).get("utilizationP95") for run in backend_runs if run.get("gpu", {}).get("utilizationP95") is not None]
    memory_utilization_means = [
        run.get("gpu", {}).get("memoryUtilizationMean")
        for run in backend_runs
        if run.get("gpu", {}).get("memoryUtilizationMean") is not None
    ]
    representative["gpu"] = {
        "utilizationMean": mean(utilization_means) if utilization_means else None,
        "utilizationP50": mean(utilization_p50s) if utilization_p50s else None,
        "utilizationP95": mean(utilization_p95s) if utilization_p95s else None,
        "utilizationMeanDistribution": distribution(utilization_means),
        "utilizationP95Distribution": distribution(utilization_p95s),
        "memoryUtilizationMean": mean(memory_utilization_means) if memory_utilization_means else None,
        "memoryUtilizationMeanDistribution": distribution(memory_utilization_means),
        "peakMemoryBytes": max(run.get("gpu", {}).get("peakMemoryBytes", 0) for run in backend_runs),
        "peakMemoryBytesDistribution": distribution([run.get("gpu", {}).get("peakMemoryBytes", 0) for run in backend_runs]),
        "sampling": {
            "statuses": sorted({run.get("gpu", {}).get("sampling", {}).get("status") for run in backend_runs if run.get("gpu", {}).get("sampling")}),
            "sources": sorted({run.get("gpu", {}).get("sampling", {}).get("source") for run in backend_runs if run.get("gpu", {}).get("sampling", {}).get("source")}),
            "sampleCount": sum(run.get("gpu", {}).get("sampling", {}).get("sampleCount", 0) for run in backend_runs),
            "errors": sorted({
                run.get("gpu", {}).get("sampling", {}).get("error")
                for run in backend_runs
                if run.get("gpu", {}).get("sampling", {}).get("error")
            }),
        },
    }
    process_fields = sorted({field for run in backend_runs for field in run.get("process", {}) if isinstance(run.get("process", {}).get(field), (int, float))})
    representative["process"] = {
        field: distribution([run.get("process", {}).get(field) for run in backend_runs if run.get("process", {}).get(field) is not None])
        for field in process_fields
    }
    representative["process"]["sources"] = sorted({run.get("process", {}).get("source") for run in backend_runs if run.get("process", {}).get("source")})
    representative["runSummaries"] = [
        {
            "runIndex": index,
            "samplesPerSecond": run["steadyState"]["samplesPerSecond"],
            "trainingMs": run["lifecycle"]["trainingMs"],
            "totalMs": run["lifecycle"]["totalMs"],
            "inputWaitPercent": run["steadyState"]["inputWaitPercent"],
            "peakMemoryBytes": run.get("gpu", {}).get("peakMemoryBytes"),
            "gpuUtilizationMean": run.get("gpu", {}).get("utilizationMean"),
            "gpuUtilizationP95": run.get("gpu", {}).get("utilizationP95"),
            "cpuUtilizationMean": run.get("process", {}).get("cpuUtilizationMean"),
            "rssPeakBytes": run.get("process", {}).get("rssPeakBytes"),
        }
        for index, run in enumerate(backend_runs)
    ]
    return representative


def aggregate_protocol(protocols):
    summed = {
        "connectionsOpened": 0,
        "getManyRequests": 0,
        "singleGetRequests": 0,
        "putManyRequests": 0,
        "singlePutRequests": 0,
        "cacheHits": 0,
        "cacheMisses": 0,
        "valuesReturned": 0,
        "bytesReturned": 0,
        "bytesPublished": 0,
        "lookupNanos": 0,
        "publishNanos": 0,
        "writeBatchCount": 0,
        "entriesPerWriteBatch": [],
    }
    for protocol in protocols:
        for key in summed:
            if key == "entriesPerWriteBatch":
                summed[key].extend(protocol.get(key, []))
            else:
                summed[key] += protocol.get(key, 0) or 0
    summed["entriesPerWriteBatchMean"] = mean(summed["entriesPerWriteBatch"])
    summed["cacheHitCount"] = summed["cacheHits"]
    summed["cacheMissCount"] = summed["cacheMisses"]
    summed["bytesRead"] = summed["bytesReturned"]
    summed["bytesWritten"] = summed["bytesPublished"]
    summed["walForceCount"] = 0
    return summed


def cache_dynamics(protocol, args):
    lookups = (protocol.get("cacheHits") or 0) + (protocol.get("cacheMisses") or 0)
    misses = protocol.get("cacheMisses") or 0
    published = protocol.get("bytesPublished") or 0
    dynamics = {
        "configuredChangedPercent": args.changed_percent,
        "configuredInitialCacheHitRatio": args.initial_cache_hit_ratio,
        "prepopulatePreviousVersion": args.prepopulate_previous_version,
        "targetInitialCacheHitRatio": protocol.get("targetInitialCacheHitRatio", 0.0),
        "prepopulatedEntries": protocol.get("prepopulatedEntries", 0),
        "initialPresentIndices": protocol.get("initialPresentIndices", []),
        "lookups": lookups,
        "hits": protocol.get("cacheHits") or 0,
        "misses": misses,
        "hitRatio": ratio(protocol.get("cacheHits") or 0, lookups),
        "missRatio": ratio(misses, lookups),
        "recomputedSamples": misses,
        "publishedSamples": sum(protocol.get("entriesPerWriteBatch", [])),
        "publishCostMs": (protocol.get("publishNanos") or 0) / 1e6,
        "lookupCostMs": (protocol.get("lookupNanos") or 0) / 1e6,
        "bytesRead": protocol.get("bytesReturned") or 0,
        "bytesWritten": published,
        "writeBatchCount": protocol.get("writeBatchCount") or 0,
        "entriesPerWriteBatchMean": protocol.get("entriesPerWriteBatchMean") or 0,
    }
    dynamics["invariants"] = cache_invariants(dynamics, args)
    return dynamics


def cache_invariants(dynamics, args):
    initial_present = set(dynamics.get("initialPresentIndices", []))
    expected = expected_cache_counts(args, initial_present)
    expected_lookups = expected["lookups"]
    expected_misses = expected["misses"]
    expected_published = expected_misses
    expected_hits = expected["hits"]
    passed = (
        dynamics["lookups"] == expected_lookups
        and dynamics["prepopulatedEntries"] == len(initial_present)
        and dynamics["misses"] == expected_misses
        and dynamics["publishedSamples"] == expected_published
        and dynamics["hits"] == expected_hits
    )
    return {
        "expectedLookups": expected_lookups,
        "expectedPrepopulatedEntries": len(initial_present),
        "expectedHits": expected_hits,
        "expectedMisses": expected_misses,
        "expectedPublishedSamples": expected_published,
        "passed": passed,
    }


def expected_cache_counts(args, initial_present_indices):
    seen = set(initial_present_indices)
    lookups = 0
    misses = 0
    for batch_indices, _ in scheduled_batches(args, effective_measured_steps(args)):
        for index in batch_indices:
            lookups += 1
            if index not in seen:
                seen.add(index)
                misses += 1
    return {"lookups": lookups, "misses": misses, "hits": lookups - misses}


def aggregate_cache_dynamics(dynamics_by_run):
    fields = [
        "targetInitialCacheHitRatio",
        "prepopulatedEntries",
        "lookups",
        "hits",
        "misses",
        "hitRatio",
        "missRatio",
        "recomputedSamples",
        "publishedSamples",
        "publishCostMs",
        "lookupCostMs",
        "bytesRead",
        "bytesWritten",
        "writeBatchCount",
        "entriesPerWriteBatchMean",
    ]
    return {
        field: distribution([dynamics.get(field) for dynamics in dynamics_by_run if dynamics.get(field) is not None])
        for field in fields
    }


def aggregate_operation_metrics(metrics_by_run):
    operations = sorted({operation for metrics in metrics_by_run for operation in metrics})
    result = {}
    for operation in operations:
        result[operation] = {
            "count": sum(metrics.get(operation, {}).get("count", 0) for metrics in metrics_by_run),
            "mean": distribution([
                metrics.get(operation, {}).get("mean")
                for metrics in metrics_by_run
                if metrics.get(operation, {}).get("count", 0) > 0
            ]),
            "p95": distribution([
                metrics.get(operation, {}).get("p95")
                for metrics in metrics_by_run
                if metrics.get(operation, {}).get("count", 0) > 0
            ]),
            "max": max(
                [metrics.get(operation, {}).get("max", 0) for metrics in metrics_by_run if metrics.get(operation, {}).get("count", 0) > 0],
                default=0,
            ),
        }
    return result


def aggregate_comparisons(comparisons):
    result = {}
    keys = sorted({key for comparison in comparisons for key in comparison})
    for key in keys:
        fields = sorted({field for comparison in comparisons for field in comparison.get(key, {})})
        result[key] = {
            field: distribution([comparison.get(key, {}).get(field) for comparison in comparisons if comparison.get(key, {}).get(field) is not None])
            for field in fields
        }
    return result


def aggregate_break_even(values):
    epochs = [value["epoch"] for value in values if value and value.get("epoch") is not None]
    if not epochs:
        return None
    return {
        "epoch": distribution(epochs),
        "rawRuns": values,
    }


def aggregate_admission_models(models):
    present = [model for model in models if model]
    if not present:
        return None
    fields = [
        "zeroMarginPredictedBreakEvenEpoch",
        "onePercentMarginPredictedBreakEvenEpoch",
        "observedSampledCrossoverEpoch",
        "predictionErrorEpochs",
        "rawEpochMs",
        "aetherWarmEpochMs",
        "aetherPopulateMs",
    ]
    return {
        "runs": len(present),
        **{
            field: distribution([model[field] for model in present if model.get(field) is not None])
            for field in fields
        },
        "margin": 0.01,
        "equation": present[0].get("equation"),
        "rawRuns": present,
    }


def training_outcome(results):
    aether = results.get("AETHER_CACHE")
    raw = results.get("RAW_RECOMPUTE")
    if not aether or not raw:
        return {"status": "MISSING_BASELINE"}
    input_wait_delta = aether["steadyState"]["inputWaitPercent"] - raw["steadyState"]["inputWaitPercent"]
    samples_ratio = ratio(aether["steadyState"]["samplesPerSecond"], raw["steadyState"]["samplesPerSecond"])
    epoch_delta = aether["steadyState"]["meanEpochMs"] - raw["steadyState"]["meanEpochMs"]
    total_delta = aether["lifecycle"]["totalMs"] - raw["lifecycle"]["totalMs"]
    training_delta = aether["lifecycle"]["trainingMs"] - raw["lifecycle"]["trainingMs"]
    pipeline_preprocess_delta = aether["timing"].get("preprocessMs", 0) - raw["timing"].get("preprocessMs", 0)
    flags = {
        "inputWaitReduced": input_wait_delta < 0,
        "samplesPerSecondImproved": samples_ratio is not None and samples_ratio > 1,
        "epochWallReduced": epoch_delta < 0,
        "trainingWallReduced": training_delta < 0,
        "totalWallReduced": total_delta < 0,
        "preprocessTimeReduced": pipeline_preprocess_delta < 0,
    }
    if flags["samplesPerSecondImproved"] and flags["totalWallReduced"]:
        status = "TRAINING_THROUGHPUT_AND_TOTAL_WALL_IMPROVED"
    elif flags["samplesPerSecondImproved"] or flags["trainingWallReduced"] or flags["epochWallReduced"]:
        status = "TRAINING_THROUGHPUT_SIGNAL"
    elif flags["inputWaitReduced"] or flags["preprocessTimeReduced"]:
        status = "PIPELINE_ONLY_SIGNAL"
    else:
        status = "NO_AETHER_TRAINING_SIGNAL"
    return {
        "status": status,
        "inputWaitPercentDelta": input_wait_delta,
        "samplesPerSecondRatio": samples_ratio,
        "meanEpochMsDelta": epoch_delta,
        "trainingWallMsDelta": training_delta,
        "totalWallMsDelta": total_delta,
        "preprocessMsDelta": pipeline_preprocess_delta,
        "flags": flags,
        "interpretation": outcome_interpretation(status),
    }


def outcome_interpretation(status):
    return {
        "TRAINING_THROUGHPUT_AND_TOTAL_WALL_IMPROVED": "Aether improved throughput and total measured lifecycle wall time versus RAW_RECOMPUTE for this run.",
        "TRAINING_THROUGHPUT_SIGNAL": "Aether improved at least one measured end-to-end training metric, but total lifecycle improvement must be checked separately.",
        "PIPELINE_ONLY_SIGNAL": "Aether improved input/pipeline behavior, but this run does not show end-to-end training throughput improvement.",
        "NO_AETHER_TRAINING_SIGNAL": "This run does not show an Aether training or input-wait advantage versus RAW_RECOMPUTE.",
        "MISSING_BASELINE": "Aether and RAW_RECOMPUTE were not both present.",
    }.get(status, "Unknown outcome.")


def aggregate_outcomes(outcomes):
    statuses = [outcome.get("status") for outcome in outcomes if outcome.get("status")]
    flag_names = sorted({flag for outcome in outcomes for flag in outcome.get("flags", {})})
    return {
        "runs": len(outcomes),
        "statuses": {status: statuses.count(status) for status in sorted(set(statuses))},
        "inputWaitPercentDelta": distribution([outcome.get("inputWaitPercentDelta") for outcome in outcomes if outcome.get("inputWaitPercentDelta") is not None]),
        "samplesPerSecondRatio": distribution([outcome.get("samplesPerSecondRatio") for outcome in outcomes if outcome.get("samplesPerSecondRatio") is not None]),
        "meanEpochMsDelta": distribution([outcome.get("meanEpochMsDelta") for outcome in outcomes if outcome.get("meanEpochMsDelta") is not None]),
        "trainingWallMsDelta": distribution([outcome.get("trainingWallMsDelta") for outcome in outcomes if outcome.get("trainingWallMsDelta") is not None]),
        "totalWallMsDelta": distribution([outcome.get("totalWallMsDelta") for outcome in outcomes if outcome.get("totalWallMsDelta") is not None]),
        "preprocessMsDelta": distribution([outcome.get("preprocessMsDelta") for outcome in outcomes if outcome.get("preprocessMsDelta") is not None]),
        "flagPassRates": {
            flag: ratio(sum(1 for outcome in outcomes if outcome.get("flags", {}).get(flag)), len(outcomes))
            for flag in flag_names
        },
    }


def import_numpy_status():
    try:
        import numpy
        return {"available": True, "version": numpy.__version__}
    except Exception as exc:
        return {"available": False, "error": str(exc)}


def accelerator_report():
    try:
        import torch
    except Exception as exc:
        return {"torchImportError": str(exc), "deviceAvailable": False, "deviceCount": 0, "backend": "unavailable", "hipVersion": None, "cudaVersion": None}
    available = torch.cuda.is_available()
    hip_version = getattr(torch.version, "hip", None)
    cuda_version = getattr(torch.version, "cuda", None)
    result = {
        "torchVersion": torch.__version__,
        "backend": "rocm" if hip_version else ("cuda" if cuda_version else "unknown"),
        "hipVersion": hip_version,
        "cudaVersion": cuda_version,
        "deviceAvailable": available,
        "deviceCount": torch.cuda.device_count() if available else 0,
        "deviceName": torch.cuda.get_device_name(0) if available else None,
        "vramBytes": torch.cuda.get_device_properties(0).total_memory if available else 0,
        "deviceArchitecture": None,
    }
    if available:
        properties = torch.cuda.get_device_properties(0)
        result["deviceArchitecture"] = getattr(properties, "gcnArchName", None) or getattr(properties, "major", None)
    return result


def unsupported_reason(numpy_status, accelerator, accelerator_backend, expected_gpu):
    if not numpy_status["available"]:
        return f"NumPy unavailable: {numpy_status.get('error')}"
    if not accelerator.get("deviceAvailable"):
        return "torch.cuda.is_available() is false"
    if not accelerator_backend_matches(accelerator, accelerator_backend):
        return f"PyTorch accelerator backend {accelerator.get('backend')!r} does not match required backend {accelerator_backend!r}"
    name = accelerator.get("deviceName") or ""
    if not gpu_name_matches(name, expected_gpu):
        return f"GPU name {name!r} does not contain expected device {expected_gpu!r}"
    return None


def accelerator_backend_matches(accelerator, accelerator_backend):
    if not accelerator.get("deviceAvailable"):
        return False
    if accelerator_backend == "auto":
        return bool(accelerator.get("cudaVersion") or accelerator.get("hipVersion"))
    if accelerator_backend == "rocm":
        return bool(accelerator.get("hipVersion"))
    if accelerator_backend == "cuda":
        return bool(accelerator.get("cudaVersion") and not accelerator.get("hipVersion"))
    return False


def gpu_name_matches(name, expected_gpu):
    if not expected_gpu:
        return True
    expected = expected_gpu.lower()
    actual = name.lower()
    return expected in actual or ("amd radeon rx 7900" in expected and "amd radeon rx 7900" in actual)


def gpu_smoke_test(torch, device):
    x = torch.randn(1024, 1024, device=device)
    y = x @ x
    torch.cuda.synchronize(device)
    if not torch.isfinite(y).all():
        raise RuntimeError("GPU matmul smoke test produced non-finite values")
    return True


def set_seed(torch, seed):
    random.seed(seed)
    torch.manual_seed(seed)
    torch.cuda.manual_seed_all(seed)


def create_model(tier, torch):
    import torch.nn as nn
    channels = {"small": 16, "medium": 32, "large": 64}[tier]
    depth = {"small": 2, "medium": 3, "large": 4}[tier]

    class Block(nn.Module):
        def __init__(self, source, target):
            super().__init__()
            self.layers = nn.Sequential(
                nn.Conv2d(source, target, 3, padding=1),
                nn.ReLU(inplace=True),
                nn.Conv2d(target, target, 3, padding=1),
                nn.ReLU(inplace=True),
            )

        def forward(self, value):
            return self.layers(value)

    class SmallUNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.blocks = nn.ModuleList()
            source = 1
            for level in range(depth):
                target = channels * (2 ** level)
                self.blocks.append(Block(source, target))
                source = target
            self.head = nn.Conv2d(source, 1, 1)

        def forward(self, value):
            for block in self.blocks:
                value = block(value)
            return self.head(value)

    return SmallUNet()


def model_metadata(model, args, torch):
    parameter_count = sum(parameter.numel() for parameter in model.parameters())
    return {
        "name": "small_unet",
        "tier": args.model_tier,
        "parameters": parameter_count,
        "precision": "fp32",
        "device": str(next(model.parameters()).device),
        "timingMode": TIMING_MODE,
        "deviceEventTimingValidated": validate_device_events(torch),
    }


def validate_device_events(torch):
    try:
        start = torch.cuda.Event(enable_timing=True)
        end = torch.cuda.Event(enable_timing=True)
        start.record()
        end.record()
        torch.cuda.synchronize()
        _ = start.elapsed_time(end)
        return True
    except Exception:
        return False


def run_model_warmup(torch, model, loss_function, optimizer, device, args):
    x = torch.zeros((args.batch_size, 1, args.resize, args.resize), device=device)
    y = torch.zeros((args.batch_size, 1, args.resize, args.resize), device=device)
    for _ in range(min(args.warmup_steps, 4)):
        optimizer.zero_grad(set_to_none=True)
        loss = loss_function(model(x), y)
        loss.backward()
        optimizer.step()
    torch.cuda.synchronize(device)


def run_backend(torch, model, optimizer, loss_function, device, backend, context, measured_steps):
    steps = []
    epoch_walls = []
    current_epoch = None
    segmentation_metrics = None

    if context.args.warmup_steps:
        for step_index, scheduled in enumerate(prepared_batches(context, backend, scheduled_batches(context.args, context.args.warmup_steps))):
            batch_indices, epoch, prepared, prefetch_wait_ms = scheduled
            train_step(
                torch,
                model,
                optimizer,
                loss_function,
                device,
                backend,
                context,
                batch_indices,
                step_index,
                epoch,
                prepared=prepared,
                prefetch_wait_ms=prefetch_wait_ms,
            )
        torch.cuda.synchronize(device)
        if backend == "AETHER_CACHE" and not context.external_aether_cache:
            context.reset_aether_cache()
    cold_start_gate = aether_cold_start_gate(context) if backend == "AETHER_CACHE" else None

    sampler = GpuUtilizationSampler(context.args.gpu_sample_interval_ms)
    sampler.start()
    process_start = process_metrics_snapshot()
    training_start = time.perf_counter()
    epoch_start = training_start
    training_ms = None
    try:
        for step_index, scheduled in enumerate(prepared_batches(context, backend, scheduled_batches(context.args, measured_steps))):
            batch_indices, epoch, prepared, prefetch_wait_ms = scheduled
            if current_epoch is None:
                current_epoch = epoch
                epoch_start = time.perf_counter()
            elif epoch != current_epoch:
                epoch_walls.append((time.perf_counter() - epoch_start) * 1000)
                current_epoch = epoch
                epoch_start = time.perf_counter()
            step = train_step(
                torch,
                model,
                optimizer,
                loss_function,
                device,
                backend,
                context,
                batch_indices,
                step_index,
                epoch,
                prepared=prepared,
                prefetch_wait_ms=prefetch_wait_ms,
            )
            steps.append(step)
        if measured_steps > 0:
            epoch_walls.append((time.perf_counter() - epoch_start) * 1000)
        torch.cuda.synchronize(device)
        training_ms = (time.perf_counter() - training_start) * 1000
    finally:
        process_end = process_metrics_snapshot()
        gpu_samples = sampler.stop()
    segmentation_metrics = segmentation_sanity_metrics(torch, model, loss_function, device, context)
    return summarize_backend(
        backend,
        steps,
        epoch_walls,
        training_ms,
        context,
        gpu_samples,
        process_metrics_delta(process_start, process_end, training_ms),
        cold_start_gate,
        segmentation_metrics,
    )


def scheduled_batches(args, total_steps):
    produced = 0
    epoch = 0
    while produced < total_steps:
        for batch_indices in batch_plan(args):
            if produced >= total_steps:
                break
            yield batch_indices, epoch
            produced += 1
        epoch += 1


def segmentation_sanity_metrics(torch, model, loss_function, device, context):
    if not context.reference:
        return None
    indices = list(range(min(context.args.batch_size, len(context.reference))))
    batch = stack_values([context.reference[index] for index in indices], context.np)
    cpu_tensors, _ = normalize_cpu_batch(torch, batch)
    images = cpu_tensors["images"].to(device, non_blocking=False)
    masks = cpu_tensors["masks"].to(device, non_blocking=False)
    with torch.no_grad():
        logits = model(images)
        loss = loss_function(logits, masks)
        predictions = torch.sigmoid(logits) >= 0.5
        targets = masks >= 0.5
        intersection = (predictions & targets).sum().float()
        prediction_total = predictions.sum().float()
        target_total = targets.sum().float()
        union = (predictions | targets).sum().float()
        dice = (2 * intersection) / torch.clamp(prediction_total + target_total, min=1)
        mean_iou = intersection / torch.clamp(union, min=1)
    torch.cuda.synchronize(device)
    return {
        "samplesChecked": len(indices),
        "loss": float(loss.item()),
        "dice": float(dice.item()),
        "meanIoU": float(mean_iou.item()),
    }


def prepared_batches(context, backend, schedule):
    if context.args.prefetch_batches <= 0:
        for batch_indices, epoch in schedule:
            yield batch_indices, epoch, None, 0.0
        return
    work_queue = queue.Queue(maxsize=context.args.prefetch_batches)

    def producer():
        for batch_indices, epoch in schedule:
            started = time.perf_counter()
            try:
                batch, counters = context.batch(backend, batch_indices)
                work_queue.put((batch_indices, epoch, (batch, counters, elapsed_ms(started)), None))
            except BaseException as error:
                work_queue.put((batch_indices, epoch, None, error))
                return

    thread = threading.Thread(target=producer, name=f"{backend.lower()}-prefetch", daemon=True)
    thread.start()
    while thread.is_alive() or not work_queue.empty():
        wait_started = time.perf_counter()
        try:
            batch_indices, epoch, prepared, error = work_queue.get(timeout=0.1)
        except queue.Empty:
            continue
        wait_ms = elapsed_ms(wait_started)
        if error is not None:
            raise error
        yield batch_indices, epoch, prepared, wait_ms
    thread.join()


def train_step(torch, model, optimizer, loss_function, device, backend, context, batch_indices, step, epoch, *, prepared=None, prefetch_wait_ms=0.0):
    step_start = time.perf_counter()
    if prepared is None:
        input_start = time.perf_counter()
        batch, counters = context.batch(backend, batch_indices)
        batch_prepare_ms = elapsed_ms(input_start)
        input_wait_ms = batch_prepare_ms
    else:
        batch, counters, batch_prepare_ms = prepared
        input_wait_ms = prefetch_wait_ms
    augmentation_start = time.perf_counter()
    batch = augment_batch(batch, context, batch_indices, step, epoch)
    augmentation_ms = elapsed_ms(augmentation_start)
    batch_checksum = batch_checksums(batch)
    cpu_tensors, tensor_layout = normalize_cpu_batch(torch, batch)
    transfer_start = time.perf_counter()
    images = cpu_tensors["images"].to(device, non_blocking=False)
    masks = cpu_tensors["masks"].to(device, non_blocking=False)
    torch.cuda.synchronize(device)
    host_to_device_ms = elapsed_ms(transfer_start)
    optimizer.zero_grad(set_to_none=True)
    forward_start = time.perf_counter()
    logits = model(images)
    torch.cuda.synchronize(device)
    forward_ms = elapsed_ms(forward_start)
    loss_start = time.perf_counter()
    loss = loss_function(logits, masks)
    torch.cuda.synchronize(device)
    loss_ms = elapsed_ms(loss_start)
    backward_start = time.perf_counter()
    loss.backward()
    torch.cuda.synchronize(device)
    backward_ms = elapsed_ms(backward_start)
    optimizer_start = time.perf_counter()
    optimizer.step()
    torch.cuda.synchronize(device)
    optimizer_ms = elapsed_ms(optimizer_start)
    return {
        "step": step,
        "epoch": epoch,
        "batchSize": len(batch_indices),
        "inputWaitMs": input_wait_ms,
        "batchPrepareMs": batch_prepare_ms,
        "prefetchWaitMs": prefetch_wait_ms,
        "sourceLoadMs": counters.get("sourceLoadMs", 0.0),
        "preprocessMs": counters.get("preprocessMs", 0.0),
        "aetherLookupMs": counters.get("aetherLookupMs", 0.0),
        "aetherPublishMs": counters.get("aetherPublishMs", 0.0),
        "mmapReadMs": counters.get("mmapReadMs", 0.0),
        "tensorBuildMs": counters.get("tensorBuildMs", 0.0),
        "artifactDecodeMs": counters.get("artifactDecodeMs", 0.0),
        "randomAugmentationMs": augmentation_ms,
        "hostToDeviceMs": host_to_device_ms,
        "forwardMs": forward_ms,
        "lossMs": loss_ms,
        "backwardMs": backward_ms,
        "optimizerMs": optimizer_ms,
        "stepWallMs": elapsed_ms(step_start),
        "tensorLayout": tensor_layout,
        "checksums": batch_checksum,
        "loss": float(loss.item()),
    }


def normalize_cpu_batch(torch, batch):
    images = torch.from_numpy(batch["images"]).contiguous()
    masks = torch.from_numpy(batch["masks"]).contiguous()
    if not images.is_contiguous() or not masks.is_contiguous():
        raise RuntimeError("final CPU tensor normalization failed to produce contiguous tensors")
    if images.stride() != expected_contiguous_stride(images.shape) or masks.stride() != expected_contiguous_stride(masks.shape):
        raise RuntimeError("final CPU tensor normalization produced unexpected contiguous strides")
    return {"images": images, "masks": masks}, {
        "images": tensor_layout_metadata(torch, images),
        "masks": tensor_layout_metadata(torch, masks),
    }


def augment_batch(batch, context, batch_indices, step, epoch):
    if context.args.augmentation_mode == "none":
        return batch
    np = context.np
    seed_material = json.dumps({
        "seed": getattr(context, "run_seed", context.args.seed),
        "epoch": epoch,
        "step": step,
        "indices": list(batch_indices),
        "mode": context.args.augmentation_mode,
    }, sort_keys=True).encode("utf-8")
    seed = int.from_bytes(hashlib.sha256(seed_material).digest()[:8], "little") & ((1 << 63) - 1)
    rng = np.random.default_rng(seed)
    images = batch["images"].copy()
    masks = batch["masks"].copy()
    if rng.random() < 0.5:
        images = images[:, :, :, ::-1].copy()
        masks = masks[:, :, :, ::-1].copy()
    scale = np.float32(rng.uniform(0.95, 1.05))
    offset = np.float32(rng.uniform(-0.025, 0.025))
    noise = rng.normal(0.0, 0.005, size=images.shape).astype(np.float32)
    images = np.clip(images * scale + offset + noise, -8.0, 8.0).astype(np.float32)
    return {"images": images, "masks": masks.astype(np.float32, copy=False)}


def expected_contiguous_stride(shape):
    stride = []
    running = 1
    for size in reversed(tuple(shape)):
        stride.insert(0, running)
        running *= int(size)
    return tuple(stride)


def tensor_layout_metadata(torch, tensor):
    pointer = int(tensor.data_ptr())
    channels_last = False
    if tensor.dim() == 4:
        try:
            channels_last = bool(tensor.is_contiguous(memory_format=torch.channels_last))
        except Exception:
            channels_last = False
    try:
        is_pinned = bool(tensor.is_pinned())
    except Exception:
        is_pinned = False
    return {
        "shape": list(tensor.shape),
        "dtype": str(tensor.dtype),
        "stride": list(tensor.stride()),
        "expectedStride": list(expected_contiguous_stride(tensor.shape)),
        "isContiguous": bool(tensor.is_contiguous()),
        "isChannelsLastContiguous": channels_last,
        "memoryFormat": "channels_last" if channels_last else ("contiguous" if tensor.is_contiguous() else "non_contiguous"),
        "isPinned": is_pinned,
        "storageOffset": int(tensor.storage_offset()),
        "dataPtrModulo64": pointer % 64,
        "dataPtrModulo256": pointer % 256,
    }


class GpuUtilizationSampler:
    def __init__(self, interval_ms):
        self.interval_seconds = interval_ms / 1000
        self.samples = []
        self.source = None
        self.error = None
        self._stop = threading.Event()
        self._thread = None

    def start(self):
        if self.interval_seconds <= 0:
            self.error = "disabled"
            return
        sample = self._sample_once()
        if sample is not None:
            self.samples.append(sample)
        elif self.source is None:
            return
        self._thread = threading.Thread(target=self._run, name="gpu-utilization-sampler", daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=max(1.0, self.interval_seconds * 4))
        return {
            "source": self.source,
            "status": "SAMPLED" if self.samples else "UNAVAILABLE",
            "error": self.error,
            "intervalMs": self.interval_seconds * 1000,
            "samples": self.samples,
        }

    def _run(self):
        while not self._stop.is_set():
            sample = self._sample_once()
            if sample is not None:
                self.samples.append(sample)
            elif self.source is None:
                break
            self._stop.wait(self.interval_seconds)

    def _sample_once(self):
        for command, source in (
            (["nvidia-smi", "--query-gpu=utilization.gpu,utilization.memory", "--format=csv,noheader,nounits"], "nvidia-smi utilization query"),
            (["amd-smi", "metric", "--usage", "--json"], "amd-smi metric --usage --json"),
            (["rocm-smi", "--showuse", "--showmemuse", "--json"], "rocm-smi --showuse --showmemuse --json"),
        ):
            try:
                completed = subprocess.run(command, capture_output=True, text=True, timeout=2)
            except FileNotFoundError:
                continue
            except Exception as exc:
                self.error = str(exc)
                continue
            if completed.returncode != 0:
                self.error = (completed.stderr or completed.stdout).strip().splitlines()[0] if (completed.stderr or completed.stdout).strip() else f"{command[0]} exited {completed.returncode}"
                continue
            if command[0] == "nvidia-smi":
                sample = self._sample_from_nvidia_smi(completed.stdout)
                if sample is None:
                    self.error = f"{source} returned unparsable output"
                    continue
                self.source = source
                return sample
            try:
                payload = json.loads(completed.stdout)
            except json.JSONDecodeError as exc:
                first_line = (completed.stderr or completed.stdout).strip().splitlines()[0] if (completed.stderr or completed.stdout).strip() else str(exc)
                self.error = f"{source} returned non-json output: {first_line}"
                continue
            self.source = source
            return self._sample_from_payload(payload)
        return None

    def _sample_from_payload(self, payload):
        fields = list(flatten_json(payload))
        gpu_values = [value for key, value in fields if is_gpu_utilization_key(key) and value is not None]
        memory_values = [value for key, value in fields if is_memory_utilization_key(key) and value is not None]
        return {
            "timestamp": time.time(),
            "gpuUtilizationPercent": mean(gpu_values) if gpu_values else None,
            "memoryUtilizationPercent": mean(memory_values) if memory_values else None,
        }

    def _sample_from_nvidia_smi(self, text):
        gpu_values = []
        memory_values = []
        for line in text.splitlines():
            parts = [part.strip() for part in line.split(",")]
            if len(parts) < 2:
                continue
            gpu = numeric_value(parts[0])
            memory = numeric_value(parts[1])
            if gpu is not None:
                gpu_values.append(gpu)
            if memory is not None:
                memory_values.append(memory)
        if not gpu_values and not memory_values:
            return None
        return {
            "timestamp": time.time(),
            "gpuUtilizationPercent": mean(gpu_values) if gpu_values else None,
            "memoryUtilizationPercent": mean(memory_values) if memory_values else None,
        }


def flatten_json(value, prefix=""):
    if isinstance(value, dict):
        for key, child in value.items():
            next_prefix = f"{prefix}.{key}" if prefix else str(key)
            yield from flatten_json(child, next_prefix)
    elif isinstance(value, list):
        for index, child in enumerate(value):
            yield from flatten_json(child, f"{prefix}[{index}]")
    else:
        yield prefix.lower(), numeric_value(value)


def numeric_value(value):
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        match = re.search(r"-?\d+(?:\.\d+)?", value)
        if match:
            return float(match.group(0))
    return None


def is_gpu_utilization_key(key):
    normalized = key.replace(" ", "").replace("_", "").replace("-", "")
    return any(token in normalized for token in ("gpuuse", "gpuusage", "gpuutilization", "gfxactivity", "gpubusypercent"))


def is_memory_utilization_key(key):
    normalized = key.replace(" ", "").replace("_", "").replace("-", "")
    return any(token in normalized for token in ("memoryuse", "memoryusage", "memoryutilization", "vramuse")) or (
        "vram" in normalized and "allocated" in normalized
    )


def process_metrics_snapshot():
    return {
        "processTimeSeconds": time.process_time(),
        "rssPeakBytes": rss_peak_bytes(),
        "io": proc_self_io(),
        "cpuCount": os.cpu_count() or 1,
    }


def process_metrics_delta(start, end, wall_ms):
    cpu_time = max(0.0, end["processTimeSeconds"] - start["processTimeSeconds"])
    wall_seconds = max(wall_ms / 1000, 1e-9)
    io_start = start.get("io") or {}
    io_end = end.get("io") or {}
    rss_values = [value for value in (start.get("rssPeakBytes"), end.get("rssPeakBytes")) if value is not None]
    return {
        "cpuTimeSeconds": cpu_time,
        "cpuUtilizationMean": cpu_time / wall_seconds / max(end.get("cpuCount") or 1, 1) * 100,
        "rssPeakBytes": max(rss_values) if rss_values else None,
        "diskReadBytes": max(0, io_end.get("read_bytes", 0) - io_start.get("read_bytes", 0)) if io_end and io_start else None,
        "diskWriteBytes": max(0, io_end.get("write_bytes", 0) - io_start.get("write_bytes", 0)) if io_end and io_start else None,
        "diskCancelledWriteBytes": max(0, io_end.get("cancelled_write_bytes", 0) - io_start.get("cancelled_write_bytes", 0)) if io_end and io_start else None,
        "source": "process_time+/proc/self/io" if io_end and io_start else "process_time",
    }


def rss_peak_bytes():
    try:
        import resource
    except Exception:
        return None
    try:
        value = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    except Exception:
        return None
    # Linux reports ru_maxrss in KiB, macOS in bytes.
    return int(value if sys.platform == "darwin" else value * 1024)


def proc_self_io():
    path = Path("/proc/self/io")
    if not path.exists():
        return None
    try:
        metrics = {}
        for line in path.read_text(encoding="utf-8").splitlines():
            key, value = line.split(":", 1)
            metrics[key.strip()] = int(value.strip())
        return metrics
    except Exception:
        return None


def summarize_backend(backend, steps, epoch_walls, training_ms, context, gpu_samples, process_metrics, cold_start_gate=None, segmentation_metrics=None):
    batch_size = context.args.batch_size
    resize = context.args.resize
    total_samples = sum(step["batchSize"] for step in steps)
    total_pixels = total_samples * resize * resize
    total_step_ms = sum(step["stepWallMs"] for step in steps)
    input_wait_ms = sum(step["inputWaitMs"] for step in steps)
    peak_memory = context.peak_memory_bytes()
    utilization_values = [sample["gpuUtilizationPercent"] for sample in gpu_samples["samples"] if sample.get("gpuUtilizationPercent") is not None]
    memory_utilization_values = [sample["memoryUtilizationPercent"] for sample in gpu_samples["samples"] if sample.get("memoryUtilizationPercent") is not None]
    return {
        "name": backend,
        "lifecycle": {
            "populateMs": context.populate_ms(backend),
            "trainingMs": training_ms,
            "totalTrainingWallMs": training_ms,
            "totalMs": training_ms + context.populate_ms(backend),
            "cumulativeByEpochMs": cumulative_lifecycle_by_epoch(epoch_walls, context.populate_ms(backend)),
        },
        "steadyState": {
            "meanEpochMs": mean(epoch_walls),
            "samplesPerSecond": total_samples / max(total_step_ms / 1000, 1e-9),
            "effectiveSamplesPerSecond": total_samples / max(training_ms / 1000, 1e-9),
            "stepsPerSecond": len(steps) / max(total_step_ms / 1000, 1e-9),
            "pixelsPerSecond": total_pixels / max(total_step_ms / 1000, 1e-9),
            "effectiveMegapixelsPerSecond": total_pixels / max(total_step_ms / 1000, 1e-9) / 1_000_000,
            "inputWaitPercent": input_wait_ms / max(total_step_ms, 1e-9) * 100,
        },
        "epochWallMs": epoch_walls,
        "epochChecksums": epoch_checksums(steps),
        "fullDatasetChecksum": context.checksums["combinedChecksum"],
        "timing": {field: mean([step[field] for step in steps]) for field in [
            "batchPrepareMs", "sourceLoadMs", "preprocessMs", "aetherLookupMs", "aetherPublishMs",
            "mmapReadMs", "tensorBuildMs", "artifactDecodeMs", "randomAugmentationMs", "prefetchWaitMs", "hostToDeviceMs", "forwardMs", "lossMs", "backwardMs", "optimizerMs",
        ]},
        "transferLabel": "host-to-device CPU->PyTorch cuda device",
        "tensorLayout": summarize_tensor_layout(steps),
        "coldStart": cold_start_gate,
        "segmentationMetrics": segmentation_metrics,
        "gpu": {
            "utilizationMean": mean(utilization_values) if utilization_values else None,
            "utilizationP50": percentile(utilization_values, 50) if utilization_values else None,
            "utilizationP95": percentile(utilization_values, 95) if utilization_values else None,
            "memoryUtilizationMean": mean(memory_utilization_values) if memory_utilization_values else None,
            "memoryUtilizationP95": percentile(memory_utilization_values, 95) if memory_utilization_values else None,
            "peakMemoryBytes": peak_memory,
            "sampling": {
                "status": gpu_samples["status"],
                "source": gpu_samples["source"],
                "error": gpu_samples["error"],
                "intervalMs": gpu_samples["intervalMs"],
                "sampleCount": len(gpu_samples["samples"]),
            },
            "samples": gpu_samples["samples"],
        },
        "process": process_metrics,
        "checksums": context.checksums,
        "summaryStats": summarize_steps(steps),
        "steps": steps,
    }


def summarize_steps(steps):
    return {
        field: distribution([step[field] for step in steps])
        for field in ("inputWaitMs", "batchPrepareMs", "prefetchWaitMs", "hostToDeviceMs", "forwardMs", "backwardMs", "optimizerMs", "stepWallMs")
    }


def cumulative_lifecycle_by_epoch(epoch_walls, populate_ms):
    cumulative = []
    total = populate_ms
    for index, epoch_ms in enumerate(epoch_walls, start=1):
        total += epoch_ms
        cumulative.append({"epoch": index, "totalMs": total})
    return cumulative


def summarize_tensor_layout(steps):
    if not steps:
        return {}
    first = steps[0].get("tensorLayout", {})
    image_layouts = [step.get("tensorLayout", {}).get("images", {}) for step in steps]
    mask_layouts = [step.get("tensorLayout", {}).get("masks", {}) for step in steps]
    return {
        "images": first.get("images", {}),
        "masks": first.get("masks", {}),
        "allImagesContiguous": all(layout.get("isContiguous") for layout in image_layouts),
        "allMasksContiguous": all(layout.get("isContiguous") for layout in mask_layouts),
        "imageStrideSignatures": sorted({tuple(layout.get("stride", [])) for layout in image_layouts}),
        "maskStrideSignatures": sorted({tuple(layout.get("stride", [])) for layout in mask_layouts}),
        "imagePinnedStates": sorted({layout.get("isPinned") for layout in image_layouts}),
        "maskPinnedStates": sorted({layout.get("isPinned") for layout in mask_layouts}),
        "imageDataPtrModulo64": sorted({layout.get("dataPtrModulo64") for layout in image_layouts}),
        "maskDataPtrModulo64": sorted({layout.get("dataPtrModulo64") for layout in mask_layouts}),
    }


def batch_checksums(batch):
    image = hashlib.sha256(batch["images"].tobytes()).hexdigest()
    mask = hashlib.sha256(batch["masks"].tobytes()).hexdigest()
    return {"imageChecksum": image, "maskChecksum": mask, "combinedChecksum": hashlib.sha256((image + mask).encode("ascii")).hexdigest()}


def epoch_checksums(steps):
    result = []
    epochs = sorted({step["epoch"] for step in steps})
    for epoch in epochs:
        group = [step for step in steps if step["epoch"] == epoch]
        digest = hashlib.sha256()
        for step in group:
            digest.update(step["checksums"]["combinedChecksum"].encode("ascii"))
        result.append({"epoch": epoch, "combinedChecksum": digest.hexdigest()})
    return result


def validate_backend_equivalence(context, reference):
    sample_count = min(8, len(reference))
    indices = list(range(sample_count))
    expected = stack_values([reference[index] for index in indices], context.np)
    expected_checksums = batch_checksums(expected)
    expected_augmented = augment_batch(expected, context, indices, step=0, epoch=0)
    expected_augmented_checksums = batch_checksums(expected_augmented)
    results = {}
    for backend in BACKENDS:
        batch, _ = context.batch(backend, indices)
        same_shape = batch["images"].shape == expected["images"].shape and batch["masks"].shape == expected["masks"].shape
        same_dtype = batch["images"].dtype == expected["images"].dtype and batch["masks"].dtype == expected["masks"].dtype
        same_values = bool(context.np.array_equal(batch["images"], expected["images"]) and context.np.array_equal(batch["masks"], expected["masks"]))
        checksums = batch_checksums(batch)
        augmented = augment_batch(batch, context, indices, step=0, epoch=0)
        same_augmented_values = bool(
            context.np.array_equal(augmented["images"], expected_augmented["images"])
            and context.np.array_equal(augmented["masks"], expected_augmented["masks"])
        )
        augmented_checksums = batch_checksums(augmented)
        results[backend] = {
            "samplesChecked": sample_count,
            "sameShape": same_shape,
            "sameDtype": same_dtype,
            "sameValues": same_values,
            "checksums": checksums,
            "sameAugmentedValues": same_augmented_values,
            "augmentedChecksums": augmented_checksums,
        }
        if not (
            same_shape
            and same_dtype
            and same_values
            and checksums == expected_checksums
            and same_augmented_values
            and augmented_checksums == expected_augmented_checksums
        ):
            raise RuntimeError(f"{backend} does not feed equivalent model input tensors")
    return results


def compare_backends(results):
    aether = results.get("AETHER_CACHE")
    raw = results.get("RAW_RECOMPUTE")
    static = results.get("STATIC_PREPROCESSED_MMAP")
    ram = results.get("RAM_READY")
    return {
        "primary": compare_pair(aether, raw),
        "secondaryStaticMmap": compare_pair(aether, static),
        "secondaryRamReady": compare_pair(aether, ram),
    }


def compare_pair(aether, baseline):
    if not aether or not baseline:
        return {}
    return {
        "aetherInputWaitPercentDelta": aether["steadyState"]["inputWaitPercent"] - baseline["steadyState"]["inputWaitPercent"],
        "aetherSamplesPerSecondRatio": ratio(aether["steadyState"]["samplesPerSecond"], baseline["steadyState"]["samplesPerSecond"]),
        "aetherEffectiveSamplesPerSecondRatio": ratio(
            aether["steadyState"]["effectiveSamplesPerSecond"],
            baseline["steadyState"]["effectiveSamplesPerSecond"],
        ),
        "aetherTrainingWallMsDelta": aether["lifecycle"]["trainingMs"] - baseline["lifecycle"]["trainingMs"],
        "aetherTotalMsDelta": aether["lifecycle"]["totalMs"] - baseline["lifecycle"]["totalMs"],
    }


def training_break_even_epoch(results):
    aether = results.get("AETHER_CACHE")
    raw = results.get("RAW_RECOMPUTE")
    if not aether or not raw:
        return None
    aether_epoch = aether["steadyState"]["meanEpochMs"]
    raw_epoch = raw["steadyState"]["meanEpochMs"]
    aether_populate = aether["lifecycle"]["populateMs"]
    margin = 0.01
    for epoch_count in (1, 2, 3, 4, 5, 10, 15, 18, 20, 25, 30, 50):
        aether_total = aether_populate + aether_epoch * epoch_count
        raw_total = raw_epoch * epoch_count
        if aether_total < raw_total * (1 - margin):
            return {
                "epoch": epoch_count,
                "aetherTotalMs": aether_total,
                "rawRecomputeTotalMs": raw_total,
                "margin": margin,
            }
    return None


def admission_model(results):
    aether = results.get("AETHER_CACHE")
    raw = results.get("RAW_RECOMPUTE")
    if not aether or not raw:
        return None
    raw_epoch = raw["steadyState"]["meanEpochMs"]
    aether_epoch = aether["steadyState"]["meanEpochMs"]
    aether_populate = aether["lifecycle"]["populateMs"]
    zero_margin = predicted_break_even_epoch(raw_epoch, aether_epoch, aether_populate, margin=0.0)
    one_percent = predicted_break_even_epoch(raw_epoch, aether_epoch, aether_populate, margin=0.01)
    observed = training_break_even_epoch(results)
    observed_epoch = observed.get("epoch") if observed else None
    return {
        "equation": "aether_populate_ms + aether_warm_epoch_ms * N <= (1 - margin) * raw_epoch_ms * N",
        "rawEpochMs": raw_epoch,
        "aetherWarmEpochMs": aether_epoch,
        "aetherPopulateMs": aether_populate,
        "zeroMarginPredictedBreakEvenEpoch": zero_margin,
        "onePercentMarginPredictedBreakEvenEpoch": one_percent,
        "observedSampledCrossoverEpoch": observed_epoch,
        "predictionErrorEpochs": None if observed_epoch is None or one_percent is None else observed_epoch - one_percent,
        "margin": 0.01,
    }


def predicted_break_even_epoch(raw_epoch_ms, aether_epoch_ms, aether_populate_ms, margin):
    effective_raw = raw_epoch_ms * (1 - margin)
    per_epoch_savings = effective_raw - aether_epoch_ms
    if per_epoch_savings <= 0:
        return None
    if aether_populate_ms <= 0:
        return 0.0
    return aether_populate_ms / per_epoch_savings


def ratio(left, right):
    return None if right == 0 else left / right


class BackendContext:
    def __init__(self, args, reference, sources, np):
        self.args = args
        self.reference = reference
        self.sources = sources
        self.np = np
        self.initial_present_indices = set()
        self.external_aether_cache = bool(args.aether_cache_dir)
        if self.external_aether_cache:
            self.directory = Path(args.aether_cache_dir)
            self.directory.mkdir(parents=True, exist_ok=True)
            if args.aether_cache_mode == "fresh":
                shutil.rmtree(self.directory, ignore_errors=True)
                self.directory.mkdir(parents=True, exist_ok=True)
            self.store = AetherMLStore(self.directory, code_commit="gpu-training")
        else:
            self.directory = Path(tempfile.mkdtemp(prefix="aether-gpu-seg-"))
            self.store = AetherMLStore(self.directory / "aether", code_commit="gpu-training")
        self.static_path = self.directory / "static-preprocessed.dat"
        self.static_mmap = None
        self.static_file = None
        self.static_offsets = []
        self.ram_ready = None
        self.populate_times = {}
        self.protocol = {
            "connectionsOpened": 1,
            "getManyRequests": 0,
            "singleGetRequests": 0,
            "putManyRequests": 0,
            "singlePutRequests": 0,
            "cacheHits": 0,
            "cacheMisses": 0,
            "valuesReturned": 0,
            "bytesReturned": 0,
            "bytesPublished": 0,
            "lookupNanos": 0,
            "publishNanos": 0,
            "writeBatchCount": 0,
            "entriesPerWriteBatch": [],
        }
        self.checksums = dataset_checksums(reference)
        self._build_static()
        self._build_ram()
        self._prepopulate_aether_cache()

    def reset_aether_cache(self):
        if self.external_aether_cache:
            self.store = AetherMLStore(self.directory, code_commit="gpu-training")
            self.reset_measured_counters()
            self._prepopulate_aether_cache()
            return
        shutil.rmtree(self.store.root, ignore_errors=True)
        self.store = AetherMLStore(self.directory / "aether", code_commit="gpu-training")
        self.reset_measured_counters()
        self._prepopulate_aether_cache()

    def reset_measured_counters(self):
        self.protocol.update({
            "connectionsOpened": 1,
            "getManyRequests": 0,
            "singleGetRequests": 0,
            "putManyRequests": 0,
            "singlePutRequests": 0,
            "cacheHits": 0,
            "cacheMisses": 0,
            "valuesReturned": 0,
            "bytesReturned": 0,
            "bytesPublished": 0,
            "lookupNanos": 0,
            "publishNanos": 0,
            "writeBatchCount": 0,
            "entriesPerWriteBatch": [],
        })
        self.store.reset_operation_metrics()

    def _build_static(self):
        started = time.perf_counter()
        self.static_offsets = []
        with self.static_path.open("wb") as stream:
            for sample in self.reference:
                payload = pack_payload(sample)
                self.static_offsets.append((stream.tell(), len(payload)))
                stream.write(struct.pack("<I", len(payload)))
                stream.write(payload)
        self.populate_times["STATIC_PREPROCESSED_MMAP"] = elapsed_ms(started)
        self.static_file = self.static_path.open("r+b")
        self.static_mmap = mmap.mmap(self.static_file.fileno(), 0)

    def _build_ram(self):
        started = time.perf_counter()
        self.ram_ready = [pack_payload(sample) for sample in self.reference]
        self.populate_times["RAM_READY"] = elapsed_ms(started)

    def _prepopulate_aether_cache(self):
        target_hit_ratio = self._target_initial_hit_ratio()
        if self.external_aether_cache and self.args.aether_cache_mode == "reuse":
            present_indices = self._existing_cache_indices()
            self.initial_present_indices = present_indices
            self.prepopulated_aether_entries = len(present_indices)
            self.target_initial_cache_hit_ratio = (len(present_indices) / max(len(self.sources), 1)) * 100.0
            self.populate_times["AETHER_CACHE"] = 0.0
            return

        hit_count = int(round(self.args.samples * target_hit_ratio / 100))
        if hit_count <= 0:
            self.initial_present_indices = set()
            self.populate_times["AETHER_CACHE"] = 0.0
            self.prepopulated_aether_entries = 0
            self.target_initial_cache_hit_ratio = target_hit_ratio
            return
        started = time.perf_counter()
        entries = [
            {
                "cache_key": self.cache_key(index),
                "data": pack_payload(self.reference[index]),
                "parameters": deterministic_parameters(self.args),
            }
            for index in range(hit_count)
        ]
        self.initial_present_indices = set(range(hit_count))
        self.store.commit_bytes_many(
            entries,
            transformation_name="oct_deterministic_preprocess",
            transformation_version="1",
            parameters=deterministic_parameters(self.args),
        )
        self.store.reset_operation_metrics()
        self.populate_times["AETHER_CACHE"] = elapsed_ms(started)
        self.prepopulated_aether_entries = len(entries)
        self.target_initial_cache_hit_ratio = target_hit_ratio

    def _existing_cache_indices(self):
        current_keys = [self.cache_key(index) for index in range(len(self.sources))]
        present = self.store.cached_artifact_ids(current_keys)
        return {index for index, key in enumerate(current_keys) if key in present}

    def _existing_cache_entries(self):
        return len(self._existing_cache_indices())

    def _target_initial_hit_ratio(self):
        if self.external_aether_cache and self.args.aether_cache_mode == "reuse":
            return (self._existing_cache_entries() / max(len(self.sources), 1)) * 100.0
        if self.args.initial_cache_hit_ratio > 0:
            return self.args.initial_cache_hit_ratio
        if self.args.prepopulate_previous_version:
            return max(0.0, 100.0 - self.args.changed_percent)
        return 0.0

    def batch(self, backend, indices):
        if backend == "RAW_RECOMPUTE":
            return self._raw_batch(indices)
        if backend == "AETHER_CACHE":
            return self._aether_batch(indices)
        if backend == "STATIC_PREPROCESSED_MMAP":
            return self._static_batch(indices)
        if backend == "RAM_READY":
            return self._ram_batch(indices)
        raise ValueError(f"unknown backend: {backend}")

    def _raw_batch(self, indices):
        values = []
        source_load_ms = 0.0
        preprocess_ms = 0.0
        artifact_decode_ms = 0.0
        for index in indices:
            sample, counters = preprocess_sample_with_timing(self.sources[index], self.args, self.np)
            source_load_ms += counters.get("sourceLoadMs", 0.0)
            preprocess_ms += counters.get("preprocessMs", 0.0)
            artifact_started = time.perf_counter()
            values.append(artifact_to_tensor_sample(sample, self.np))
            artifact_decode_ms += elapsed_ms(artifact_started)
        return stack_values(values, self.np), {
            "sourceLoadMs": source_load_ms,
            "preprocessMs": preprocess_ms,
            "artifactDecodeMs": artifact_decode_ms,
        }

    def _aether_batch(self, indices):
        lookup_started = time.perf_counter_ns()
        keys = [self.cache_key(index) for index in indices]
        cached = self.store.load_cached_bytes_many(keys)
        self.protocol["bytesReturned"] += sum(len(payload) for payload in cached.values())
        lookup_nanos = time.perf_counter_ns() - lookup_started
        self.protocol["getManyRequests"] += 1
        self.protocol["cacheHits"] += len(cached)
        self.protocol["cacheMisses"] += len(keys) - len(cached)
        self.protocol["valuesReturned"] += len(cached)
        self.protocol["lookupNanos"] += lookup_nanos
        values = []
        publish = []
        decode_ms = 0.0
        source_load_ms = 0.0
        preprocess_ms = 0.0
        for index, key in zip(indices, keys):
            if key in cached:
                decode_started = time.perf_counter()
                values.append(unpack_payload(cached[key], self.np))
                decode_ms += elapsed_ms(decode_started)
            else:
                sample, counters = preprocess_sample_with_timing(self.sources[index], self.args, self.np)
                preprocess_ms += counters.get("preprocessMs", 0.0)
                source_load_ms += counters.get("sourceLoadMs", 0.0)
                decode_started = time.perf_counter()
                sample = artifact_to_tensor_sample(sample, self.np)
                decode_ms += elapsed_ms(decode_started)
                values.append(sample)
                publish.append((key, sample))
        publish_started = time.perf_counter_ns()
        if publish:
            published = self.store.commit_bytes_many(
                [
                    {
                        "cache_key": key,
                        "data": pack_payload(sample),
                        "parameters": deterministic_parameters(self.args),
                    }
                    for key, sample in publish
                ],
                transformation_name="oct_deterministic_preprocess",
                transformation_version="1",
                parameters=deterministic_parameters(self.args),
            )
            for metadata in published:
                self.protocol["bytesPublished"] += metadata.size
        publish_nanos = time.perf_counter_ns() - publish_started
        if publish:
            self.protocol["putManyRequests"] += 1
            self.protocol["writeBatchCount"] += 1
            self.protocol["entriesPerWriteBatch"].append(len(publish))
        self.protocol["publishNanos"] += publish_nanos
        return stack_values(values, self.np), {
            "sourceLoadMs": source_load_ms,
            "preprocessMs": preprocess_ms,
            "aetherLookupMs": lookup_nanos / 1e6,
            "aetherPublishMs": publish_nanos / 1e6,
            "artifactDecodeMs": decode_ms,
        }

    def _static_batch(self, indices):
        started = time.perf_counter()
        values = []
        for index in indices:
            offset, payload_size = self.static_offsets[index]
            header_size = struct.unpack("<I", self.static_mmap[offset:offset + 4])[0]
            if header_size != payload_size:
                raise ValueError("static mmap artifact length prefix mismatch")
            start = offset + 4
            values.append(unpack_payload(self.static_mmap[start:start + payload_size], self.np))
        return stack_values(values, self.np), {"mmapReadMs": elapsed_ms(started), "tensorBuildMs": 0.0}

    def _ram_batch(self, indices):
        started = time.perf_counter()
        values = [unpack_payload(self.ram_ready[index], self.np) for index in indices]
        return stack_values(values, self.np), {"tensorBuildMs": elapsed_ms(started)}

    def cache_key(self, index):
        source = self.sources[index]
        source_hash = source.get("source_hash")
        if source_hash is None:
            source_hash = hashlib.sha256(source["raw"]).hexdigest()
        descriptor = json.dumps({
            "sample_id": source["sample_id"],
            "source_identity": source["source_identity"],
            "source_hash": source_hash,
            "deterministic_parameters": deterministic_parameters(self.args),
        }, sort_keys=True)
        return hashlib.sha256(descriptor.encode("utf-8")).hexdigest()

    def populate_ms(self, backend):
        return self.populate_times.get(backend, 0.0)

    def protocol_counters(self):
        protocol = dict(self.protocol)
        entries = protocol["entriesPerWriteBatch"]
        protocol["entriesPerWriteBatchMean"] = mean(entries)
        protocol["cacheHitCount"] = protocol["cacheHits"]
        protocol["cacheMissCount"] = protocol["cacheMisses"]
        protocol["bytesRead"] = protocol["bytesReturned"]
        protocol["bytesWritten"] = protocol["bytesPublished"]
        protocol["walForceCount"] = 0
        protocol["prepopulatedEntries"] = getattr(self, "prepopulated_aether_entries", 0)
        protocol["targetInitialCacheHitRatio"] = getattr(self, "target_initial_cache_hit_ratio", 0.0)
        protocol["initialPresentIndices"] = sorted(getattr(self, "initial_present_indices", set()))
        return protocol

    def peak_memory_bytes(self):
        try:
            import torch
            return torch.cuda.max_memory_allocated()
        except Exception:
            return 0

    def close(self):
        if self.static_mmap is not None:
            self.static_mmap.close()
        if self.static_file is not None:
            self.static_file.close()
        if not self.external_aether_cache:
            shutil.rmtree(self.directory, ignore_errors=True)

    def populate_aether_dataset(self):
        entries_requested = 0
        total_misses = 0
        total_entries_published = 0
        start_hits = self.protocol["cacheHits"]
        start_misses = self.protocol["cacheMisses"]
        start_publish_batches = len(self.protocol["entriesPerWriteBatch"])
        start_bytes_written = self.protocol["bytesPublished"]
        for batch_indices in batch_plan(self.args):
            entries_requested += len(batch_indices)
            self.batch("AETHER_CACHE", batch_indices)
        total_misses = self.protocol["cacheMisses"] - start_misses
        total_entries_published = sum(self.protocol["entriesPerWriteBatch"][start_publish_batches:])
        total_bytes_written = self.protocol["bytesPublished"] - start_bytes_written
        return {
            "entriesRequested": entries_requested,
            "misses": total_misses,
            "entriesPublished": total_entries_published,
            "bytesWritten": total_bytes_written,
            "lookupMs": (self.protocol.get("lookupNanos", 0) / 1e6),
            "publishMs": (self.protocol.get("publishNanos", 0) / 1e6),
            "initialReusableEntries": self.prepopulated_aether_entries,
            "initialHitRatio": self.target_initial_cache_hit_ratio,
            "cacheHits": self.protocol["cacheHits"] - start_hits,
        }


def aether_cold_start_gate(context):
    keys = [context.cache_key(index) for index in range(context.args.samples)]
    present = sum(1 for key in keys if (context.store.cache_dir / f"{key}.json").exists())
    target = getattr(context, "target_initial_cache_hit_ratio", 0.0)
    prepopulated = getattr(context, "prepopulated_aether_entries", 0)
    if target == 0:
        passed = present == 0 and prepopulated == 0
    else:
        passed = present == prepopulated
    return {
        "requestedInitialHitRatio": target,
        "entriesBeforeMeasuredStep0": prepopulated,
        "measuredKeysAlreadyPresent": present,
        "passed": passed,
    }


def warm_backend(context, backend):
    if backend in ("STATIC_PREPROCESSED_MMAP", "RAM_READY"):
        context.batch(backend, list(range(min(context.args.batch_size, context.args.samples))))


def load_sources(args):
    if args.dataset_kind == "oct5k":
        return load_oct5k_sources(args)
    if args.input_dir is None:
        sources = []
        for index in range(args.samples):
            raw = synthetic_oct_bytes(index, args.height, args.width, args.seed)
            sources.append({
                "kind": "synthetic",
                "sample_id": f"oct-{index:05d}",
                "raw": raw,
                "height": args.height,
                "width": args.width,
                "source_identity": f"synthetic:{args.seed}:{index}",
                "source_hash": hashlib.sha256(raw).hexdigest(),
            })
        return sources
    paths = discover_input_files(Path(args.input_dir), args.extensions)[:args.samples]
    if len(paths) < args.samples:
        raise ValueError("input-dir does not contain enough matching samples")
    sources = []
    for path in paths:
        raw = oct_sized_bytes(path.read_bytes(), args.height, args.width, path)
        sources.append({
            "kind": "synthetic",
            "sample_id": path.stem,
            "raw": raw,
            "height": args.height,
            "width": args.width,
            "source_identity": str(path),
            "source_hash": hashlib.sha256(raw).hexdigest(),
        })
    return sources


def load_oct5k_sources(args):
    manifest_path = Path(args.dataset_manifest).resolve()
    manifest_root = manifest_path.parent
    required = {"sample_id", "image_path", "mask_path", "disease", "image_sha256", "mask_sha256", "source_identity"}
    with manifest_path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        fields = set(reader.fieldnames or [])
        missing = required - fields
        if missing:
            raise ValueError(f"dataset-manifest is missing required columns: {sorted(missing)}")
        rows = [row for row in reader if not row.get("split") or row.get("split") == args.dataset_split]
    if len(rows) < args.samples:
        raise ValueError(f"dataset-manifest split {args.dataset_split!r} has {len(rows)} rows, fewer than --samples={args.samples}")
    seen_sample_ids = set()
    sources = []
    for row in rows[:args.samples]:
        sample_id = row["sample_id"].strip()
        if not sample_id:
            raise ValueError("dataset-manifest contains an empty sample_id")
        if sample_id in seen_sample_ids:
            raise ValueError(f"dataset-manifest contains duplicate sample_id: {sample_id}")
        seen_sample_ids.add(sample_id)
        image_path = resolve_manifest_path(row["image_path"], manifest_root)
        mask_path = resolve_manifest_path(row["mask_path"], manifest_root)
        if not image_path.is_file():
            raise ValueError(f"OCT5K image_path does not exist: {image_path}")
        if not mask_path.is_file():
            raise ValueError(f"OCT5K mask_path does not exist: {mask_path}")
        image_sha = row["image_sha256"].strip().lower()
        mask_sha = row["mask_sha256"].strip().lower()
        if not args.trust_manifest_hashes:
            actual_image_sha = file_sha256(image_path)
            actual_mask_sha = file_sha256(mask_path)
            if actual_image_sha != image_sha:
                raise ValueError(f"OCT5K image_sha256 mismatch for sample_id={sample_id}: {image_path}")
            if actual_mask_sha != mask_sha:
                raise ValueError(f"OCT5K mask_sha256 mismatch for sample_id={sample_id}: {mask_path}")
        expected_identity = hashlib.sha256(f"{image_sha}:{mask_sha}".encode("utf-8")).hexdigest()
        source_identity = row["source_identity"].strip().lower()
        if source_identity != expected_identity:
            raise ValueError(f"OCT5K source_identity mismatch for sample_id={sample_id}")
        sources.append({
            "kind": "oct5k",
            "sample_id": sample_id,
            "image_path": image_path,
            "mask_path": mask_path,
            "disease": row["disease"].strip(),
            "image_sha256": image_sha,
            "mask_sha256": mask_sha,
            "source_identity": source_identity,
            "source_hash": f"{image_sha}:{mask_sha}",
        })
    identities = [source["source_identity"] for source in sources]
    if len(set(identities)) != len(identities):
        raise ValueError("dataset-manifest contains duplicate OCT5K source identities")
    return sources


def resolve_manifest_path(value, manifest_root):
    path = Path(value)
    if not path.is_absolute():
        path = manifest_root / path
    return path.resolve()


def preprocess_sample(sample, args, np):
    value, _ = preprocess_sample_with_timing(sample, args, np)
    return value


def preprocess_sample_with_timing(sample, args, np):
    if sample.get("kind") == "oct5k":
        return preprocess_oct5k_sample(sample, args, np)
    resize = args.resize
    started = time.perf_counter()
    raw = np.frombuffer(sample["raw"], dtype=np.uint8).astype(np.float32).reshape(sample["height"], sample["width"])
    image = nearest_resize(raw, resize, np) / 255.0
    for _ in range(args.preprocess_passes - 1):
        image = deterministic_denoise_pass(image, np)
    mean = float(image.mean())
    std = float(image.std()) or 1.0
    normalized = ((image - mean) / std).astype(np.float32)
    mask = (image >= float(image.mean())).astype(np.float32)
    return (
        {"sample_id": sample["sample_id"], "image": normalized.reshape(1, resize, resize), "mask": mask.reshape(1, resize, resize)},
        {"sourceLoadMs": 0.0, "preprocessMs": elapsed_ms(started)},
    )


def preprocess_oct5k_sample(sample, args, np):
    try:
        from PIL import Image, ImageFilter
    except ImportError as error:
        raise RuntimeError("dataset-kind=oct5k requires Pillow; install the clients/python package dependencies") from error
    bilinear = getattr(getattr(Image, "Resampling", Image), "BILINEAR")
    nearest = getattr(getattr(Image, "Resampling", Image), "NEAREST")
    resize = args.resize
    source_started = time.perf_counter()
    with Image.open(sample["image_path"]) as image_stream:
        image = image_stream.convert("L").copy()
    with Image.open(sample["mask_path"]) as mask_stream:
        validate_semantic_mask_mode(mask_stream.mode, sample["mask_path"])
        mask = mask_stream.copy()
    source_load_ms = elapsed_ms(source_started)
    preprocess_started = time.perf_counter()
    image = image.resize((resize, resize), bilinear)
    image = image.filter(ImageFilter.GaussianBlur(radius=1.0))
    image_array = np.asarray(image, dtype=np.float32)
    mask = mask.resize((resize, resize), nearest)
    mask_array = np.asarray(mask, dtype=np.int64)
    lower, upper = np.percentile(image_array, [1, 99])
    if upper <= lower:
        upper = lower + 1.0
    clipped = np.clip(image_array, lower, upper)
    normalized = ((clipped - lower) / (upper - lower)).astype(np.float32)
    for _ in range(args.preprocess_passes - 1):
        normalized = deterministic_denoise_pass(normalized, np)
    mask_binary = (mask_array > 0).astype(np.float32)
    return {
        "sample_id": sample["sample_id"],
        "image": normalized.reshape(1, resize, resize),
        "mask": mask_binary.reshape(1, resize, resize),
    }, {"sourceLoadMs": source_load_ms, "preprocessMs": elapsed_ms(preprocess_started)}


def validate_semantic_mask_mode(mode, path):
    if mode in {"RGB", "RGBA", "CMYK", "HSV"}:
        raise ValueError(f"OCT5K mask appears to be an RGB visualization, not semantic labels: {path}")
    if mode not in {"1", "L", "P", "I", "I;16"}:
        raise ValueError(f"OCT5K mask uses unsupported semantic label mode {mode!r}: {path}")


def nearest_resize(image, target, np):
    row_indices = (np.arange(target) * image.shape[0] // target).clip(0, image.shape[0] - 1)
    col_indices = (np.arange(target) * image.shape[1] // target).clip(0, image.shape[1] - 1)
    return image[row_indices][:, col_indices]


def deterministic_denoise_pass(image, np):
    center = image
    north = np.roll(image, 1, axis=0)
    south = np.roll(image, -1, axis=0)
    west = np.roll(image, 1, axis=1)
    east = np.roll(image, -1, axis=1)
    return (center * 0.5 + (north + south + west + east) * 0.125).astype(np.float32)


def stack_values(values, np):
    return {
        "images": np.stack([value["image"] for value in values]).astype(np.float32),
        "masks": np.stack([value["mask"] for value in values]).astype(np.float32),
    }


def pack_payload(sample):
    header = json.dumps({
        "sample_id": sample["sample_id"],
        "imageShape": sample["image"].shape,
        "maskShape": sample["mask"].shape,
        "imageDtype": "float16",
        "maskDtype": "uint8",
        "artifactEncodingVersion": "nchw-float16-uint8-v1",
    }, separators=(",", ":"), sort_keys=True).encode("utf-8")
    image = sample["image"].astype("float16", copy=False).tobytes()
    mask = (sample["mask"] > 0).astype("uint8", copy=False).tobytes()
    return struct.pack("<I", len(header)) + header + image + mask


def unpack_payload(payload, np):
    if len(payload) < 4:
        raise ValueError("cached payload is too small")
    header_size = struct.unpack("<I", payload[:4])[0]
    header_end = 4 + header_size
    value = json.loads(payload[4:header_end].decode("utf-8"))
    image_count = element_count(value["imageShape"])
    mask_count = element_count(value["maskShape"])
    image_dtype = np.dtype(value.get("imageDtype", "float32"))
    mask_dtype = np.dtype(value.get("maskDtype", "float32"))
    image_bytes = image_count * image_dtype.itemsize
    mask_bytes = mask_count * mask_dtype.itemsize
    body = memoryview(payload)[header_end:]
    if len(body) != image_bytes + mask_bytes:
        raise ValueError("cached payload byte length does not match tensor shapes")
    return {
        "sample_id": value["sample_id"],
        "image": np.frombuffer(body[:image_bytes], dtype=image_dtype).astype(np.float32).reshape(value["imageShape"]),
        "mask": np.frombuffer(body[image_bytes:image_bytes + mask_bytes], dtype=mask_dtype).astype(np.float32).reshape(value["maskShape"]),
    }


def artifact_to_tensor_sample(sample, np):
    return unpack_payload(pack_payload(sample), np)


def element_count(shape):
    count = 1
    for dimension in shape:
        count *= int(dimension)
    return count


def dataset_checksums(samples):
    image = hashlib.sha256()
    mask = hashlib.sha256()
    for sample in samples:
        image.update(sample["sample_id"].encode("utf-8"))
        image.update(sample["image"].tobytes())
        mask.update(sample["sample_id"].encode("utf-8"))
        mask.update(sample["mask"].tobytes())
    combined = hashlib.sha256(image.digest() + mask.digest()).hexdigest()
    return {"imageChecksum": image.hexdigest(), "maskChecksum": mask.hexdigest(), "combinedChecksum": combined}


def assert_equivalent_inputs(reference, checksums):
    if dataset_checksums(reference) != checksums:
        raise RuntimeError("deterministic preprocessing checksum mismatch")


def dataset_integrity_summary(args, sources):
    diseases = {}
    for source in sources:
        disease = source.get("disease", "synthetic")
        diseases[disease] = diseases.get(disease, 0) + 1
    return {
        "datasetKind": args.dataset_kind,
        "manifestPath": str(Path(args.dataset_manifest).resolve()) if args.dataset_manifest else None,
        "manifestSha256": file_sha256(Path(args.dataset_manifest)) if args.dataset_manifest else None,
        "manifestHashesVerified": bool(args.dataset_manifest and not args.trust_manifest_hashes),
        "datasetSplit": args.dataset_split,
        "pairedSamplesUsed": len(sources),
        "diseaseDistribution": dict(sorted(diseases.items())),
        "sourceIdentityCount": len({source["source_identity"] for source in sources}),
        "sourceIdentityCollisions": len(sources) - len({source["source_identity"] for source in sources}),
        "passed": len(sources) == args.samples and len({source["source_identity"] for source in sources}) == len(sources),
    }


def batch_plan(args):
    for start in range(0, args.samples, args.batch_size):
        yield list(range(start, min(args.samples, start + args.batch_size)))


def effective_measured_steps(args):
    return args.measured_steps or max(1, (args.samples + args.batch_size - 1) // args.batch_size * args.epochs)


def deterministic_parameters(args):
    if args.dataset_kind == "oct5k":
        return {
            "dataset": "OCT5K",
            "pipeline": "segmentation",
            "transformImplementationVersion": args.oct5k_transform_version,
            "outputImageSize": args.resize,
            "decodeMode": "grayscale",
            "imageResizeAlgorithm": "bilinear",
            "maskResizeAlgorithm": "nearest",
            "percentileClipLower": 1,
            "percentileClipUpper": 99,
            "denoiseAlgorithm": "gaussian_blur",
            "denoiseRadius": 1.0,
            "normalizationVersion": "percentile-minmax-v1",
            "contrastNormalization": "fixed-percentile-minmax",
            "persistedImageDtype": "float16",
            "persistedMaskDtype": "uint8",
            "artifactEncodingVersion": "nchw-float16-uint8-v1",
            "preprocessPasses": args.preprocess_passes,
        }
    return {
        "dataset": "synthetic-oct",
        "pipeline": "segmentation",
        "height": args.height,
        "width": args.width,
        "resize": args.resize,
        "normalizationVersion": "zscore-v1",
        "channelLayoutEncoding": "nchw-fp32",
        "maskEncoding": "binary-fp32",
        "payloadEncoding": "nchw-float16-uint8-v1",
        "persistedImageDtype": "float16",
        "persistedMaskDtype": "uint8",
        "preprocessPasses": args.preprocess_passes,
        "implementationOutputVersion": "gpu-segmentation-v1",
    }


def configuration(args):
    return {
        "samples": args.samples,
        "height": args.height,
        "width": args.width,
        "resize": args.resize,
        "batchSize": args.batch_size,
        "epochs": args.epochs,
        "workers": args.workers,
        "changedPercent": args.changed_percent,
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
        "measuredStepsEffective": effective_measured_steps(args),
        "modelTier": args.model_tier,
        "preprocessPasses": args.preprocess_passes,
        "initialCacheHitRatio": args.initial_cache_hit_ratio,
        "aetherCacheDir": args.aether_cache_dir,
        "aetherCacheMode": args.aether_cache_mode,
        "aetherPopulateOnly": args.aether_populate_only,
        "prepopulatePreviousVersion": args.prepopulate_previous_version,
        "prefetchBatches": args.prefetch_batches,
        "augmentationMode": args.augmentation_mode,
        "gpuSampleIntervalMs": args.gpu_sample_interval_ms,
        "backends": list(BACKENDS),
    }


def storage_locations(args):
    return {
        "rawPath": args.dataset_manifest if args.dataset_kind == "oct5k" else (args.input_dir or "synthetic-generator"),
        "aetherPath": "temporary-per-run",
        "mmapPath": "temporary-per-run",
        "filesystem": filesystem_name(Path.cwd()),
        "deviceDescription": platform.platform(),
    }


def filesystem_name(path):
    if os.name != "nt":
        return "posix"
    try:
        completed = subprocess.run(["fsutil", "fsinfo", "volumeinfo", str(path.anchor)], capture_output=True, text=True)
        for line in completed.stdout.splitlines():
            if "File System Name" in line:
                return line.split(":", 1)[-1].strip()
    except Exception:
        pass
    return "unknown"


def dataset_version(args):
    if args.dataset_kind == "oct5k":
        manifest_hash = file_sha256(Path(args.dataset_manifest))[:12] if args.dataset_manifest else "missing-manifest"
        return f"oct5k-{args.dataset_split}-{args.samples}-manifest-{manifest_hash}-size-{args.resize}-transform-{args.oct5k_transform_version}"
    source = args.input_dir or "synthetic"
    return f"{source}-oct-{args.samples}x{args.height}x{args.width}-resize-{args.resize}-passes-{args.preprocess_passes}-seed-{args.seed}"


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def distribution(values):
    if not values:
        return {
            "count": 0,
            "mean": 0,
            "median": 0,
            "standardDeviation": 0,
            "confidence95": 0,
            "p50": 0,
            "p95": 0,
            "min": 0,
            "max": 0,
        }
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0
    return {
        "count": len(values),
        "mean": mean(values),
        "median": statistics.median(values),
        "standardDeviation": standard_deviation,
        "confidence95": 1.96 * standard_deviation / math.sqrt(len(values)) if len(values) > 1 else 0,
        "p50": percentile(values, 50),
        "p95": percentile(values, 95),
        "min": min(values),
        "max": max(values),
    }


def percentile(values, pct):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int(pct / 100 * len(ordered) + .999) - 1))]


def mean(values):
    return statistics.mean(values) if values else 0


def elapsed_ms(start):
    return (time.perf_counter() - start) * 1000


if __name__ == "__main__":
    main()
