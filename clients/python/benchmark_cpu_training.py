import argparse
import json
import time
from pathlib import Path

from aether_training_cache import AetherDataLoader, AetherTrainingCache, CacheKey, MappedSegmentRegistry, TransformationFingerprint


def main():
    parser = argparse.ArgumentParser(description="Measure CPU training input wait with Aether batches.")
    parser.add_argument("--port", type=int, default=9484)
    parser.add_argument("--segment-directory", required=True)
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--epochs", type=int, default=3)
    parser.add_argument("--compute-ms", type=float, default=5)
    parser.add_argument("--workers", type=int, default=0)
    parser.add_argument("--prefetch-batches", type=int, default=0)
    parser.add_argument("--output", default="build/cpu-training.json")
    args = parser.parse_args()
    transform = TransformationFingerprint.from_descriptor("python-training-pipeline-v1")
    keys = [CacheKey("python-benchmark", f"sample-{index}", transform) for index in range(args.samples)]
    results = []
    with AetherTrainingCache(port=args.port) as cache, MappedSegmentRegistry(args.segment_directory) as registry:
        for epoch in range(args.epochs):
            loader = AetherDataLoader(cache, keys, registry, args.batch_size,
                                      workers=args.workers, prefetch_batches=args.prefetch_batches)
            epoch_started = time.perf_counter_ns()
            steps = 0
            for batch in loader:
                steps += 1
                checksum = sum(sum(view) for view in batch) & 0xFFFFFFFF
                compute_until = time.perf_counter_ns() + int(args.compute_ms * 1_000_000)
                while time.perf_counter_ns() < compute_until:
                    checksum ^= steps
            epoch_nanos = time.perf_counter_ns() - epoch_started
            metrics = loader.metrics()
            seconds = max(epoch_nanos, 1) / 1_000_000_000
            results.append({
                "epoch": epoch,
                "workers": args.workers,
                "prefetchBatches": args.prefetch_batches,
                "batchSize": args.batch_size,
                "steps": steps,
                "samples": args.samples,
                "epochNanos": epoch_nanos,
                "samplesPerSecond": args.samples / seconds,
                "stepsPerSecond": steps / seconds,
                "inputWaitNanos": metrics["inputWaitNanos"],
                "inputWaitPercent": metrics["inputWaitPercent"],
                "batchLatencyP50Nanos": metrics["batchLatencyP50Nanos"],
                "batchLatencyP95Nanos": metrics["batchLatencyP95Nanos"],
                "batchLatencyP99Nanos": metrics["batchLatencyP99Nanos"],
            })
    report = {"backend": "AETHER_MAPPED", "results": results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


if __name__ == "__main__":
    main()
