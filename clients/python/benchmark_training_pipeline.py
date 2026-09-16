import argparse
import json
import time
from pathlib import Path

from aether_training_cache import (
    AetherTrainingCache,
    CacheKey,
    MappedSegmentRegistry,
    TransformationFingerprint,
    numpy_view,
    torch_view,
)


def main():
    parser = argparse.ArgumentParser(description="Benchmark Aether Python bytes, mapped views, and tensor preparation.")
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=9484)
    parser.add_argument("--unix-socket")
    parser.add_argument("--segment-directory", required=True)
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--payload-bytes", type=int, default=1024 * 1024)
    parser.add_argument("--dtype", default="int32")
    parser.add_argument("--output", default="python-training-pipeline.json")
    args = parser.parse_args()
    if args.payload_bytes % 4:
        raise ValueError("payload-bytes must be divisible by four for int32 views")

    transform = TransformationFingerprint.from_descriptor("python-training-pipeline-v1")
    keys = [CacheKey("python-benchmark", f"sample-{i}", transform) for i in range(args.samples)]
    with AetherTrainingCache(args.host, args.port, unix_socket=args.unix_socket) as cache:
        setup_start = time.perf_counter_ns()
        byte_values = [cache.get(key) for key in keys]
        if any(value is None for value in byte_values):
            raise RuntimeError("materialized benchmark read missed")
        refs_warmup = cache.get_many_refs(keys)
        setup_nanos = time.perf_counter_ns() - setup_start

        start = time.perf_counter_ns()
        byte_values = [cache.get(key) for key in keys]
        if any(value is None for value in byte_values):
            raise RuntimeError("materialized warmup read missed")
        materialized_checksum = sum(sum(value) for value in byte_values) & 0xFFFFFFFF
        bytes_nanos = time.perf_counter_ns() - start

        start = time.perf_counter_ns()
        references = cache.get_many_refs(keys)
        refs_nanos = time.perf_counter_ns() - start
        if len(references) != args.samples:
            raise RuntimeError("reference benchmark read missed")

        with MappedSegmentRegistry(args.segment_directory) as registry:
            warmup_views = [registry.view(references[key]) for key in keys]
            for view in warmup_views:
                view.release()
            mapped_start = time.perf_counter_ns()
            views = [registry.view(references[key]) for key in keys]
            mapped_nanos = time.perf_counter_ns() - mapped_start
            touch_start = time.perf_counter_ns()
            page_count = sum((len(view) + 4095) // 4096 for view in views)
            touch_checksum = sum(view[offset] for view in views for offset in range(0, len(view), 4096)) & 0xFFFFFFFF
            touch_nanos = time.perf_counter_ns() - touch_start
            reduction_start = time.perf_counter_ns()
            reduction_checksum = sum(sum(view) for view in views) & 0xFFFFFFFF
            reduction_nanos = time.perf_counter_ns() - reduction_start
            import numpy as np
            numpy_start = time.perf_counter_ns()
            arrays = [numpy_view(view, dtype=args.dtype) for view in views]
            numpy_nanos = time.perf_counter_ns() - numpy_start
            numpy_reduction_start = time.perf_counter_ns()
            numpy_checksum = sum(int(array.view(np.uint8).sum()) for array in arrays) & 0xFFFFFFFF
            numpy_reduction_nanos = time.perf_counter_ns() - numpy_reduction_start
            torch_nanos = None
            torch_full_nanos = None
            torch_checksum = None
            pinned_nanos = None
            gpu_nanos = None
            pinned_unavailable_reason = None
            cuda_available = False
            tensors = []
            try:
                import torch
                torch_start = time.perf_counter_ns()
                tensors = [torch_view(view, dtype=args.dtype) for view in views]
                torch_nanos = time.perf_counter_ns() - torch_start
                if len(tensors) != len(arrays):
                    raise RuntimeError("tensor conversion count mismatch")
                torch_full_start = time.perf_counter_ns()
                torch_checksum = sum(int(tensor.view(torch.uint8).to(torch.int64).sum()) for tensor in tensors) & 0xFFFFFFFF
                torch_full_nanos = time.perf_counter_ns() - torch_full_start
                cuda_available = torch.cuda.is_available()
                if cuda_available:
                    pinned_start = time.perf_counter_ns()
                    pinned_tensors = [torch.empty_like(tensor, pin_memory=True) for tensor in tensors]
                    for pinned, tensor in zip(pinned_tensors, tensors):
                        pinned.copy_(tensor)
                    pinned_nanos = time.perf_counter_ns() - pinned_start
                    gpu_start = time.perf_counter_ns()
                    gpu_tensors = [tensor.to("cuda", non_blocking=False) for tensor in tensors]
                    torch.cuda.synchronize()
                    gpu_nanos = time.perf_counter_ns() - gpu_start
                else:
                    pinned_unavailable_reason = "no CUDA or accelerator backend"
            except ImportError:
                pass
            mapped_bytes = sum(view.nbytes for view in views)
            del tensors
            del arrays
            del views

    report = {
        "samples": args.samples,
        "payloadBytes": args.payload_bytes,
        "totalPayloadBytes": args.samples * args.payload_bytes,
        "setupNanos": setup_nanos,
        "materializedFullReduction": phase(bytes_nanos, args.samples, mapped_bytes, materialized_checksum),
        "getManyRefs": phase(refs_nanos, args.samples, None),
        "mappedViewCreate": phase(mapped_nanos, args.samples, None),
        "mappedFullTouch": page_phase(touch_nanos, page_count, touch_checksum),
        "mappedFullReduction": phase(reduction_nanos, args.samples, mapped_bytes, reduction_checksum),
        "numpyViewCreate": phase(numpy_nanos, args.samples, None),
        "numpyFullReduction": phase(numpy_reduction_nanos, args.samples, mapped_bytes, numpy_checksum),
        "torchCpuView": None if torch_nanos is None else phase(torch_nanos, args.samples, None),
        "torchCpuFullConsume": None if torch_full_nanos is None else phase(torch_full_nanos, args.samples, mapped_bytes, torch_checksum),
        "torchPinnedStage": None if pinned_nanos is None else phase(pinned_nanos, args.samples, mapped_bytes),
        "torchGpuTransfer": None if gpu_nanos is None else phase(gpu_nanos, args.samples, mapped_bytes),
        "torchEnvironment": {"cudaAvailable": cuda_available, "pinnedStageUnavailable": pinned_unavailable_reason},
        "checksums": {"materializedFullReduction": materialized_checksum,
                  "mappedFullTouch": touch_checksum, "mappedFullReduction": reduction_checksum,
                  "numpyFullReduction": numpy_checksum, "torchFullReduction": torch_checksum},
    }
    Path(args.output).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Report: {args.output}")


def phase(nanos, samples, payload_bytes, checksum=None):
    seconds = max(nanos, 1) / 1_000_000_000
    result = {
        "nanos": nanos,
        "samplesPerSecond": samples / seconds,
        "bytesPerSecond": None if payload_bytes is None else payload_bytes / seconds,
    }
    if checksum is not None:
        result["checksum"] = checksum
    return result


def page_phase(nanos, pages, checksum):
    seconds = max(nanos, 1) / 1_000_000_000
    return {"nanos": nanos, "pagesTouched": pages, "pageTouchesPerSecond": pages / seconds,
            "bytesPerSecond": None, "checksum": checksum}


if __name__ == "__main__":
    main()
