import argparse
import hashlib
import json
import os
import shutil
import statistics
import tempfile
import time
from dataclasses import asdict
from pathlib import Path

from aetherml import AetherDataset, AetherMLStore, ExperimentRecord, environment_report


def main():
    parser = argparse.ArgumentParser(description="Run a deterministic OCT segmentation-shaped AetherML workload.")
    parser.add_argument("--samples", type=int, default=64)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--height", type=int, default=64)
    parser.add_argument("--width", type=int, default=64)
    parser.add_argument("--resize", type=int, default=32)
    parser.add_argument("--batch-size", type=int, default=8)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--input-dir", default=None)
    parser.add_argument("--extensions", default=".bin,.raw,.dat,.png,.jpg,.jpeg,.tif,.tiff")
    parser.add_argument("--store", default=None)
    parser.add_argument("--reset-store", action="store_true")
    parser.add_argument("--output", default="build/oct-segmentation-workload.json")
    args = parser.parse_args()
    validate_args(args)

    temporary = None
    if args.store is None:
        temporary = tempfile.mkdtemp(prefix="aetherml-oct-")
        store_path = Path(temporary)
    else:
        store_path = Path(args.store)
        if store_path.exists() and args.reset_store:
            shutil.rmtree(store_path)
        elif store_path.exists() and any(store_path.iterdir()):
            raise ValueError("store exists and is not empty; pass --reset-store to replace it")

    try:
        report = run_workload(args, store_path)
    finally:
        if temporary is not None:
            shutil.rmtree(temporary, ignore_errors=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def validate_args(args):
    if args.samples < 1 or args.epochs < 1:
        raise ValueError("samples and epochs must be positive")
    if args.height < 4 or args.width < 4 or args.resize < 4:
        raise ValueError("height, width, and resize must be at least 4")
    if args.batch_size < 1:
        raise ValueError("batch-size must be positive")
    if getattr(args, "input_dir", None) is not None and not Path(args.input_dir).is_dir():
        raise ValueError("input-dir must be an existing directory")


def run_workload(args, store_path):
    store = AetherMLStore(store_path, code_commit=os.environ.get("AETHER_CODE_COMMIT", "synthetic-oct"))
    samples, dataset_version, dataset_name = load_samples(args)
    environment = environment_report(
        dataset_version=dataset_version,
        code_commit=store.code_commit,
        root=Path.cwd(),
    )
    transforms = build_transforms(store, args)
    dataset = AetherDataset(samples, transforms, return_artifact_ids=True)

    cold = train(dataset, args)
    final_ids = final_artifact_ids(dataset)
    snapshot = store.create_snapshot(
        f"{dataset_name}-train",
        final_ids,
        {
            "dataset": dataset_name,
            "samples": args.samples,
            "height": args.height,
            "width": args.width,
            "resize": args.resize,
            "seed": args.seed,
        },
    )
    warm = train(dataset, args)
    restored = store.restore_snapshot(snapshot.snapshot_id)
    restored_checksum = rolling_bytes_checksum(restored.values())
    raw_dataset_bytes = sum(len(sample["raw"]) for sample in samples)
    baseline_preprocessed_bytes = sum(len(json.dumps(sample, sort_keys=True).encode("utf-8")) for sample in restored.values())
    snapshot_verification = store.verify_snapshot(snapshot.snapshot_id)
    experiment = ExperimentRecord(
        experiment_id=f"synthetic-oct-{int(time.time())}",
        dataset_snapshot=snapshot.snapshot_id,
        model="synthetic-unet",
        training_parameters={
            "epochs": args.epochs,
            "batch_size": args.batch_size,
            "resize": args.resize,
            "seed": args.seed,
        },
        random_seed=args.seed,
        environment=environment,
        start_time=cold["startTime"],
        end_time=warm["endTime"],
        metrics={"cold": cold["metrics"], "warm": warm["metrics"]},
        output_artifacts=final_ids,
    )
    store.record_experiment(experiment)
    validation = store.validate()

    return {
        "backend": "AETHERML_OCT_SEGMENTATION",
        "environment": environment,
        "configuration": {
            "samples": args.samples,
            "epochs": args.epochs,
            "height": args.height,
            "width": args.width,
            "resize": args.resize,
            "batchSize": args.batch_size,
            "seed": args.seed,
            "inputDir": getattr(args, "input_dir", None),
            "dataset": dataset_name,
        },
        "coldTraining": cold,
        "warmTraining": warm,
        "datasetSnapshot": asdict(snapshot),
        "datasetSnapshotVerification": {
            **asdict(snapshot_verification),
            "isReproducible": snapshot_verification.is_reproducible,
        },
        "datasetSnapshotManifest": store.snapshot_manifest(snapshot.snapshot_id),
        "restoredArtifactCount": len(restored),
        "restoredChecksum": restored_checksum,
        "experiment": asdict(experiment),
        "validation": {**asdict(validation), "isConsistent": validation.is_consistent},
        "storage": store.storage_metrics(
            raw_dataset_bytes=raw_dataset_bytes,
            baseline_preprocessed_bytes=baseline_preprocessed_bytes,
        ),
    }


def build_transforms(store, args):
    return [
        store.cached_transform_function(
            decode_oct,
            version="1",
            name="oct_decode",
            parameters={"height": args.height, "width": args.width},
        ),
        store.cached_transform_function(
            lambda sample: resize_oct(sample, args.resize),
            version="1",
            name="oct_resize",
            parameters={"size": [args.resize, args.resize]},
        ),
        store.cached_transform_function(
            normalize_oct,
            version="1",
            name="oct_normalize",
            parameters={"method": "zscore"},
        ),
        store.cached_transform_function(
            prepare_mask,
            version="1",
            name="oct_mask_prepare",
            parameters={"threshold": "sample-mean"},
        ),
        store.cached_transform_function(
            lambda sample: augment_sample(sample, args.seed),
            version="1",
            name="oct_augment",
            parameters={"mode": "deterministic-horizontal-flip", "seed": args.seed},
        ),
    ]


def load_samples(args):
    input_dir = getattr(args, "input_dir", None)
    extensions = getattr(args, "extensions", ".bin,.raw,.dat,.png,.jpg,.jpeg,.tif,.tiff")
    if input_dir is None:
        dataset_version = f"synthetic-oct-{args.samples}x{args.height}x{args.width}-seed-{args.seed}"
        return [
            {
                "sample_id": f"oct-{index:05d}",
                "raw": synthetic_oct_bytes(index, args.height, args.width, args.seed),
                "height": args.height,
                "width": args.width,
            }
            for index in range(args.samples)
        ], dataset_version, "synthetic-oct"

    paths = discover_input_files(Path(input_dir), extensions)
    if not paths:
        raise ValueError("input-dir contains no files matching extensions")
    selected = paths[:args.samples]
    samples = []
    version_hash = hashlib.sha256()
    for index, path in enumerate(selected):
        raw_file = path.read_bytes()
        version_hash.update(path.name.encode("utf-8"))
        version_hash.update(hashlib.sha256(raw_file).digest())
        samples.append({
            "sample_id": path.stem or f"oct-file-{index:05d}",
            "raw": oct_sized_bytes(raw_file, args.height, args.width, path),
            "height": args.height,
            "width": args.width,
            "source_path": str(path),
        })
    dataset_version = f"local-oct-{len(selected)}-{version_hash.hexdigest()[:16]}"
    return samples, dataset_version, "local-oct-files"


def discover_input_files(directory, extensions):
    wanted = {extension.strip().lower() for extension in extensions.split(",") if extension.strip()}
    return sorted(
        path
        for path in directory.rglob("*")
        if path.is_file() and (not wanted or path.suffix.lower() in wanted)
    )


def oct_sized_bytes(raw_file, height, width, path):
    expected = height * width
    if len(raw_file) == expected:
        return raw_file
    decoded = try_decode_grayscale(path, height, width)
    if decoded is not None:
        return decoded
    values = bytearray()
    state = hashlib.sha256(raw_file).digest()
    while len(values) < expected:
        state = hashlib.sha256(state).digest()
        values.extend(state)
    return bytes(values[:expected])


def try_decode_grayscale(path, height, width):
    try:
        from PIL import Image
    except ImportError:
        return None
    try:
        with Image.open(path) as image:
            resized = image.convert("L").resize((width, height))
            return resized.tobytes()
    except Exception:
        return None


def decode_oct(sample):
    raw = sample["raw"]
    height = sample["height"]
    width = sample["width"]
    if len(raw) != height * width:
        raise ValueError("raw OCT sample size does not match height and width")
    image = [[raw[row * width + col] for col in range(width)] for row in range(height)]
    return {"sample_id": sample["sample_id"], "image": image}


def resize_oct(sample, target):
    image = sample["image"]
    source_h = len(image)
    source_w = len(image[0])
    resized = []
    for row in range(target):
        source_row = min(source_h - 1, row * source_h // target)
        resized.append([
            image[source_row][min(source_w - 1, col * source_w // target)]
            for col in range(target)
        ])
    return {"sample_id": sample["sample_id"], "image": resized}


def normalize_oct(sample):
    image = sample["image"]
    values = [pixel for row in image for pixel in row]
    mean = statistics.mean(values)
    stdev = statistics.pstdev(values) or 1.0
    normalized = [[round((pixel - mean) / stdev, 6) for pixel in row] for row in image]
    return {"sample_id": sample["sample_id"], "image": normalized}


def prepare_mask(sample):
    image = sample["image"]
    values = [pixel for row in image for pixel in row]
    threshold = statistics.mean(values)
    mask = [[1 if pixel >= threshold else 0 for pixel in row] for row in image]
    return {"sample_id": sample["sample_id"], "image": image, "mask": mask}


def augment_sample(sample, seed):
    flip = (stable_int(sample["sample_id"]) + seed) % 2 == 0
    if not flip:
        return sample
    return {
        "sample_id": sample["sample_id"],
        "image": [list(reversed(row)) for row in sample["image"]],
        "mask": [list(reversed(row)) for row in sample["mask"]],
    }


def train(dataset, args):
    start_time = time.time()
    epoch_nanos = []
    batch_latencies = []
    batch_preparation_latencies = []
    batch_compute_latencies = []
    input_wait_nanos = []
    model_compute_nanos = []
    checksums = []
    artifact_counts = []
    for epoch in range(args.epochs):
        started = time.perf_counter_ns()
        checksum = 0
        artifact_count = 0
        batch_started = time.perf_counter_ns()
        batch_preparation_nanos = 0
        batch_compute_nanos = 0
        batch_size = 0
        for index in range(len(dataset)):
            input_started = time.perf_counter_ns()
            sample, artifact_ids = dataset[index]
            input_elapsed = time.perf_counter_ns() - input_started
            compute_started = time.perf_counter_ns()
            checksum = synthetic_unet_step(sample, checksum + epoch + index)
            compute_elapsed = time.perf_counter_ns() - compute_started
            input_wait_nanos.append(input_elapsed)
            model_compute_nanos.append(compute_elapsed)
            batch_preparation_nanos += input_elapsed
            batch_compute_nanos += compute_elapsed
            artifact_count += len(artifact_ids)
            batch_size += 1
            if batch_size == args.batch_size or index == len(dataset) - 1:
                batch_latencies.append(time.perf_counter_ns() - batch_started)
                batch_preparation_latencies.append(batch_preparation_nanos)
                batch_compute_latencies.append(batch_compute_nanos)
                batch_started = time.perf_counter_ns()
                batch_preparation_nanos = 0
                batch_compute_nanos = 0
                batch_size = 0
        epoch_nanos.append(time.perf_counter_ns() - started)
        checksums.append(checksum)
        artifact_counts.append(artifact_count)
    end_time = time.time()
    total_epoch_nanos = sum(epoch_nanos)
    total_input_wait_nanos = sum(input_wait_nanos)
    total_compute_nanos = sum(model_compute_nanos)
    return {
        "startTime": start_time,
        "endTime": end_time,
        "metrics": {
            "epochs": args.epochs,
            "samples": len(dataset),
            "samplesPerSecond": 0.0 if total_epoch_nanos == 0 else (len(dataset) * args.epochs) / (total_epoch_nanos / 1_000_000_000),
            "artifactObservations": artifact_counts,
            "checksumSet": sorted(set(checksums)),
            "epochLatencyNanos": latency_summary(epoch_nanos),
            "batchLatencyNanos": latency_summary(batch_latencies),
            "batchPreparationLatencyNanos": latency_summary(batch_preparation_latencies),
            "batchModelComputeLatencyNanos": latency_summary(batch_compute_latencies),
            "timeToNextBatchNanos": latency_summary(batch_latencies),
            "inputWaitNanos": total_input_wait_nanos,
            "simulatedGpuActiveNanos": total_compute_nanos,
            "simulatedGpuIdleNanos": total_input_wait_nanos,
            "simulatedGpuUtilization": utilization(total_compute_nanos, total_input_wait_nanos),
        },
    }


def synthetic_unet_step(sample, seed):
    checksum = seed & 0xFFFFFFFFFFFFFFFF
    image = sample["image"]
    mask = sample["mask"]
    for row_index, row in enumerate(image):
        for col_index, value in enumerate(row):
            neighborhood = value
            if row_index:
                neighborhood += image[row_index - 1][col_index]
            if col_index:
                neighborhood += row[col_index - 1]
            scaled = int((neighborhood * 1000) + mask[row_index][col_index])
            checksum = ((checksum * 1_000_003) + scaled + 0x9E3779B9) & 0xFFFFFFFFFFFFFFFF
    return checksum


def final_artifact_ids(dataset):
    artifact_ids = []
    for index in range(len(dataset)):
        _, ids = dataset[index]
        artifact_ids.append(ids[-1])
    return artifact_ids


def synthetic_oct_bytes(index, height, width, seed):
    digest = hashlib.sha256(f"{seed}:{index}".encode("utf-8")).digest()
    values = bytearray()
    state = digest
    while len(values) < height * width:
        state = hashlib.sha256(state).digest()
        values.extend(state)
    return bytes(values[:height * width])


def stable_int(value):
    return int(hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:16], 16)


def rolling_bytes_checksum(values):
    checksum = 0
    for value in values:
        payload = json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")
        for byte in payload:
            checksum = ((checksum * 1_000_003) + byte + 0x9E3779B9) & 0xFFFFFFFFFFFFFFFF
    return checksum


def latency_summary(values):
    if not values:
        return {
            "min": 0,
            "mean": 0,
            "median": 0,
            "standardDeviation": 0,
            "confidence95": {"low": 0, "high": 0, "margin": 0},
            "p50": 0,
            "p95": 0,
            "p99": 0,
            "max": 0,
        }
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


def utilization(active_nanos, idle_nanos):
    total = active_nanos + idle_nanos
    return 0.0 if total == 0 else active_nanos / total


if __name__ == "__main__":
    main()
