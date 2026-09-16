import argparse
import json
import time
from pathlib import Path

from aether_training_cache import AetherDataLoader, AetherTrainingCache, CacheKey, MappedSegmentRegistry, TransformationFingerprint


def main():
    parser = argparse.ArgumentParser(description="Benchmark Aether batch loading with worker and prefetch sweeps.")
    parser.add_argument("--port", type=int, default=9484)
    parser.add_argument("--unix-socket")
    parser.add_argument("--segment-directory", required=True)
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--payload-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--batch-size", type=int, default=32)
    parser.add_argument("--workers", default="0,1,2,4,8")
    parser.add_argument("--prefetch", default="0,1,2,4")
    parser.add_argument("--output", default="build/dataloader-sweep.json")
    args = parser.parse_args()
    transform = TransformationFingerprint.from_descriptor("python-training-pipeline-v1")
    keys = [CacheKey("python-benchmark", f"sample-{index}", transform) for index in range(args.samples)]
    results = []
    with AetherTrainingCache(port=args.port, unix_socket=args.unix_socket) as cache:
        for workers in parse_values(args.workers):
            for prefetch in parse_values(args.prefetch):
                with MappedSegmentRegistry(args.segment_directory) as registry:
                    loader = AetherDataLoader(cache, keys, registry, args.batch_size,
                                              workers=workers, prefetch_batches=prefetch)
                    started = time.perf_counter_ns()
                    checksum = 0
                    batch_count = 0
                    for batch in loader:
                        batch_count += 1
                        checksum = (checksum + sum(sum(view) for view in batch)) & 0xFFFFFFFF
                    elapsed = time.perf_counter_ns() - started
                    metrics = loader.metrics()
                    seconds = max(elapsed, 1) / 1_000_000_000
                    results.append({
                        "workers": workers,
                        "prefetchBatches": prefetch,
                        "batchSize": args.batch_size,
                        "samples": args.samples,
                        "batches": batch_count,
                        "elapsedNanos": elapsed,
                        "samplesPerSecond": args.samples / seconds,
                        "batchesPerSecond": batch_count / seconds,
                        "payloadBytesPerSecond": args.samples * args.payload_bytes / seconds,
                        "checksum": checksum,
                        "mappingOpens": registry.map_open_count,
                        "mappingReuses": registry.map_reuse_count,
                        **metrics,
                    })
    report = {"samples": args.samples, "payloadBytes": args.payload_bytes, "results": results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def parse_values(value):
    values = [int(part) for part in value.split(",")]
    if any(item < 0 for item in values):
        raise ValueError("worker and prefetch values must be non-negative")
    return values


if __name__ == "__main__":
    main()
