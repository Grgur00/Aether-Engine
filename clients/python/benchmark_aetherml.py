import argparse
import hashlib
import json
import shutil
import statistics
import tempfile
import time
from dataclasses import asdict
from pathlib import Path

from aetherml import AetherDataset, AetherMLStore, environment_report


def main():
    parser = argparse.ArgumentParser(description="Benchmark AetherML deterministic preprocessing reuse.")
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--repeat", type=int, default=5)
    parser.add_argument("--changed-percent", type=float, default=30.0)
    parser.add_argument("--work", type=int, default=1000)
    parser.add_argument("--store", default=None)
    parser.add_argument("--reset-store", action="store_true")
    parser.add_argument("--output", default="build/aetherml-benchmark.json")
    args = parser.parse_args()
    if args.samples < 1 or args.repeat < 1:
        raise ValueError("samples and repeat must be positive")
    if args.work < 1:
        raise ValueError("work must be positive")
    if not 0 <= args.changed_percent <= 100:
        raise ValueError("changed-percent must be between 0 and 100")

    temporary = None
    if args.store is None:
        temporary = tempfile.mkdtemp(prefix="aetherml-bench-")
        store_path = Path(temporary)
    else:
        store_path = Path(args.store)
        if store_path.exists() and args.reset_store:
            shutil.rmtree(store_path)
        elif store_path.exists() and any(store_path.iterdir()):
            raise ValueError("store exists and is not empty; pass --reset-store to replace it")

    try:
        report = run_benchmark(args, store_path)
    finally:
        if temporary is not None:
            shutil.rmtree(temporary, ignore_errors=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def run_benchmark(args, store_path):
    dataset_version = f"synthetic-oct-samples-{args.samples}"
    source = [f"oct-sample-{index}".encode("utf-8") for index in range(args.samples)]
    filesystem_recompute = consume_recompute_baseline(source, args.repeat, args.work)
    manual_preprocessed = consume_manual_preprocessed_baseline(source, args.repeat, args.work)
    packed_dataset = consume_packed_dataset_baseline(source, args.repeat, args.work)
    raw_dataset_bytes = sum(len(value) for value in source)
    baseline_preprocessed_bytes = args.samples * len(preprocess_pipeline(source[0], args.work))
    store = AetherMLStore(store_path, code_commit="benchmark")
    decode = store.cached_transform_function(
        lambda value: expensive_digest(value, args.work),
        version="1",
        name="decode",
        parameters={"work": args.work},
    )
    resize = store.cached_transform_function(
        lambda value: expensive_digest(value, args.work),
        version="1",
        name="resize",
        parameters={"size": [512, 512], "work": args.work},
    )
    normalize = store.cached_transform_function(
        lambda value: expensive_digest(value, args.work),
        version="1",
        name="normalize",
        parameters={"method": "zscore", "work": args.work},
    )
    dataset = AetherDataset(source, [decode, resize, normalize], return_artifact_ids=True)

    cold = consume_dataset(dataset, args.repeat)
    first_snapshot = store.create_snapshot("cold", final_artifacts(dataset), {"phase": "cold"})
    warm = consume_dataset(dataset, args.repeat)

    changed_count = round(args.samples * args.changed_percent / 100)
    partial_source = list(source)
    for index in range(changed_count):
        partial_source[index] = partial_source[index] + b"-changed"
    partial_dataset = AetherDataset(partial_source, [decode, resize, normalize], return_artifact_ids=True)
    partial = consume_dataset(partial_dataset, args.repeat)
    second_snapshot = store.create_snapshot("partial", final_artifacts(partial_dataset), {"phase": "partial"})

    restore_started = time.perf_counter_ns()
    restored = store.restore_snapshot(first_snapshot.snapshot_id)
    restore_nanos = time.perf_counter_ns() - restore_started
    restored_checksum = rolling_payload_checksum(restored.values())
    verification = store.verify_snapshot(first_snapshot.snapshot_id)

    return {
        "backend": "AETHERML_LOCAL_ARTIFACT_STORE",
        "samples": args.samples,
        "environment": environment_report(
            dataset_version=dataset_version,
            code_commit=store.code_commit,
            root=Path.cwd(),
        ),
        "repeat": args.repeat,
        "changedPercent": args.changed_percent,
        "changedSamples": changed_count,
        "work": args.work,
        "baselines": {
            "filesystemRecompute": filesystem_recompute,
            "manualPreprocessed": manual_preprocessed,
            "highPerformancePackedDataset": packed_dataset,
        },
        "coldTraining": cold,
        "warmTraining": warm,
        "partialCacheTraining": partial,
        "snapshotRestore": {
            "snapshotId": first_snapshot.snapshot_id,
            "artifactCount": len(restored),
            "nanos": restore_nanos,
            "checksum": restored_checksum,
        },
        "snapshotVerification": {**asdict(verification), "isReproducible": verification.is_reproducible},
        "snapshotManifest": store.snapshot_manifest(first_snapshot.snapshot_id),
        "snapshotDiff": store.compare_snapshots(first_snapshot.snapshot_id, second_snapshot.snapshot_id),
        "operationMetrics": store.operation_metrics(),
        "recovery": recovery_summary(store),
        "storage": store.storage_metrics(
            raw_dataset_bytes=raw_dataset_bytes,
            baseline_preprocessed_bytes=baseline_preprocessed_bytes,
        ),
    }


def consume_recompute_baseline(source, repeat, work):
    epoch_nanos = []
    input_wait_nanos = []
    model_compute_nanos = []
    checksums = []
    for _ in range(repeat):
        started = time.perf_counter_ns()
        checksum = 0
        for index, value in enumerate(source):
            input_started = time.perf_counter_ns()
            processed = preprocess_pipeline(value, work)
            input_elapsed = time.perf_counter_ns() - input_started
            compute_started = time.perf_counter_ns()
            checksum = rolling_payload_checksum([processed], checksum + index)
            compute_elapsed = time.perf_counter_ns() - compute_started
            input_wait_nanos.append(input_elapsed)
            model_compute_nanos.append(compute_elapsed)
        epoch_nanos.append(time.perf_counter_ns() - started)
        checksums.append(checksum)
    total_epoch_nanos = sum(epoch_nanos)
    total_input_wait_nanos = sum(input_wait_nanos)
    total_compute_nanos = sum(model_compute_nanos)
    return {
        "backend": "FILESYSTEM_RECOMPUTE",
        "epochs": repeat,
        "samplesPerSecond": samples_per_second(len(source), repeat, total_epoch_nanos),
        "checksumSet": sorted(set(checksums)),
        "epochLatencyNanos": latency_summary(epoch_nanos),
        "inputPreparationLatencyNanos": latency_summary(input_wait_nanos),
        "modelComputeLatencyNanos": latency_summary(model_compute_nanos),
        "inputWaitNanos": total_input_wait_nanos,
        "simulatedGpuActiveNanos": total_compute_nanos,
        "simulatedGpuIdleNanos": total_input_wait_nanos,
        "simulatedGpuUtilization": utilization(total_compute_nanos, total_input_wait_nanos),
    }


def consume_manual_preprocessed_baseline(source, repeat, work):
    preprocess_started = time.perf_counter_ns()
    preprocessed = [preprocess_pipeline(value, work) for value in source]
    preprocess_nanos = time.perf_counter_ns() - preprocess_started
    epoch_nanos = []
    input_wait_nanos = []
    model_compute_nanos = []
    checksums = []
    for _ in range(repeat):
        started = time.perf_counter_ns()
        checksum = 0
        for index, value in enumerate(preprocessed):
            input_started = time.perf_counter_ns()
            processed = value
            input_elapsed = time.perf_counter_ns() - input_started
            compute_started = time.perf_counter_ns()
            checksum = rolling_payload_checksum([processed], checksum + index)
            compute_elapsed = time.perf_counter_ns() - compute_started
            input_wait_nanos.append(input_elapsed)
            model_compute_nanos.append(compute_elapsed)
        epoch_nanos.append(time.perf_counter_ns() - started)
        checksums.append(checksum)
    total_epoch_nanos = sum(epoch_nanos)
    total_input_wait_nanos = sum(input_wait_nanos)
    total_compute_nanos = sum(model_compute_nanos)
    return {
        "backend": "FILESYSTEM_MANUAL_PREPROCESSED",
        "populateNanos": preprocess_nanos,
        "epochs": repeat,
        "samplesPerSecond": samples_per_second(len(source), repeat, total_epoch_nanos),
        "checksumSet": sorted(set(checksums)),
        "epochLatencyNanos": latency_summary(epoch_nanos),
        "inputPreparationLatencyNanos": latency_summary(input_wait_nanos),
        "modelComputeLatencyNanos": latency_summary(model_compute_nanos),
        "inputWaitNanos": total_input_wait_nanos,
        "simulatedGpuActiveNanos": total_compute_nanos,
        "simulatedGpuIdleNanos": total_input_wait_nanos,
        "simulatedGpuUtilization": utilization(total_compute_nanos, total_input_wait_nanos),
    }


def consume_packed_dataset_baseline(source, repeat, work):
    populate_started = time.perf_counter_ns()
    offsets = []
    lengths = []
    payload = bytearray()
    for value in source:
        processed = preprocess_pipeline(value, work)
        offsets.append(len(payload))
        lengths.append(len(processed))
        payload.extend(processed)
    populate_nanos = time.perf_counter_ns() - populate_started
    packed = bytes(payload)
    epoch_nanos = []
    input_wait_nanos = []
    model_compute_nanos = []
    checksums = []
    for _ in range(repeat):
        started = time.perf_counter_ns()
        checksum = 0
        for index, offset in enumerate(offsets):
            input_started = time.perf_counter_ns()
            processed = memoryview(packed)[offset:offset + lengths[index]]
            input_elapsed = time.perf_counter_ns() - input_started
            compute_started = time.perf_counter_ns()
            checksum = rolling_payload_checksum([processed], checksum + index)
            compute_elapsed = time.perf_counter_ns() - compute_started
            input_wait_nanos.append(input_elapsed)
            model_compute_nanos.append(compute_elapsed)
        epoch_nanos.append(time.perf_counter_ns() - started)
        checksums.append(checksum)
    total_epoch_nanos = sum(epoch_nanos)
    total_input_wait_nanos = sum(input_wait_nanos)
    total_compute_nanos = sum(model_compute_nanos)
    return {
        "backend": "HIGH_PERFORMANCE_PACKED_DATASET_EQUIVALENT",
        "description": "Contiguous preprocessed payloads with offset indexing, used as a LitData-style local reference baseline.",
        "populateNanos": populate_nanos,
        "packedBytes": len(packed),
        "samplesPerSecond": samples_per_second(len(source), repeat, total_epoch_nanos),
        "epochs": repeat,
        "checksumSet": sorted(set(checksums)),
        "epochLatencyNanos": latency_summary(epoch_nanos),
        "inputPreparationLatencyNanos": latency_summary(input_wait_nanos),
        "modelComputeLatencyNanos": latency_summary(model_compute_nanos),
        "inputWaitNanos": total_input_wait_nanos,
        "simulatedGpuActiveNanos": total_compute_nanos,
        "simulatedGpuIdleNanos": total_input_wait_nanos,
        "simulatedGpuUtilization": utilization(total_compute_nanos, total_input_wait_nanos),
    }


def consume_dataset(dataset, repeat):
    epoch_nanos = []
    input_wait_nanos = []
    model_compute_nanos = []
    checksums = []
    artifact_counts = []
    for _ in range(repeat):
        started = time.perf_counter_ns()
        checksum = 0
        artifact_count = 0
        for index in range(len(dataset)):
            input_started = time.perf_counter_ns()
            value, artifact_ids = dataset[index]
            input_elapsed = time.perf_counter_ns() - input_started
            compute_started = time.perf_counter_ns()
            checksum = rolling_payload_checksum([value], checksum + index)
            compute_elapsed = time.perf_counter_ns() - compute_started
            input_wait_nanos.append(input_elapsed)
            model_compute_nanos.append(compute_elapsed)
            artifact_count += len(artifact_ids)
        epoch_nanos.append(time.perf_counter_ns() - started)
        checksums.append(checksum)
        artifact_counts.append(artifact_count)
    total_epoch_nanos = sum(epoch_nanos)
    total_input_wait_nanos = sum(input_wait_nanos)
    total_compute_nanos = sum(model_compute_nanos)
    return {
        "epochs": repeat,
        "samplesPerSecond": samples_per_second(len(dataset), repeat, total_epoch_nanos),
        "artifactObservations": artifact_counts,
        "checksumSet": sorted(set(checksums)),
        "epochLatencyNanos": latency_summary(epoch_nanos),
        "inputPreparationLatencyNanos": latency_summary(input_wait_nanos),
        "modelComputeLatencyNanos": latency_summary(model_compute_nanos),
        "inputWaitNanos": total_input_wait_nanos,
        "simulatedGpuActiveNanos": total_compute_nanos,
        "simulatedGpuIdleNanos": total_input_wait_nanos,
        "simulatedGpuUtilization": utilization(total_compute_nanos, total_input_wait_nanos),
    }


def final_artifacts(dataset):
    artifact_ids = []
    for index in range(len(dataset)):
        _, ids = dataset[index]
        artifact_ids.append(ids[-1])
    return artifact_ids


def preprocess_pipeline(value, work):
    return expensive_digest(expensive_digest(expensive_digest(value, work), work), work)


def expensive_digest(value, rounds):
    digest = bytes(value)
    for _ in range(rounds):
        digest = hashlib.sha256(digest).digest()
    return digest


def rolling_payload_checksum(values, seed=0):
    checksum = seed & 0xFFFFFFFFFFFFFFFF
    for value in values:
        for byte in bytes(value):
            checksum = ((checksum * 1_000_003) + byte + 0x9E3779B9) & 0xFFFFFFFFFFFFFFFF
    return checksum


def latency_summary(values):
    mean = statistics.mean(values)
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0
    margin = 1.96 * standard_deviation / (len(values) ** 0.5) if len(values) > 1 else 0
    return {
        "min": min(values),
        "mean": mean,
        "median": statistics.median(values),
        "standardDeviation": standard_deviation,
        "confidence95": {"low": mean - margin, "high": mean + margin, "margin": margin},
        "p50": percentile(values, 50),
        "p95": percentile(values, 95),
        "p99": percentile(values, 99),
        "max": max(values),
    }


def percentile(values, value):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int(value / 100 * len(ordered) + .999) - 1))]


def samples_per_second(samples, repeat, total_nanos):
    return 0.0 if total_nanos == 0 else (samples * repeat) / (total_nanos / 1_000_000_000)


def utilization(active_nanos, idle_nanos):
    total = active_nanos + idle_nanos
    return 0.0 if total == 0 else active_nanos / total


def recovery_summary(store):
    report = store.validate()
    result = asdict(report)
    result["isConsistent"] = report.is_consistent
    return result


if __name__ == "__main__":
    main()
