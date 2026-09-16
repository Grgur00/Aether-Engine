"""Exploratory client request timing; server attribution and mmap comparison remain pending."""
import argparse
import json
import random
import statistics
import time
from pathlib import Path

from paper_common import environment, java_daemon, write_json
from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint


def profile(output, *, windows=30, operations=10, payload_bytes=4096, batch_size=1, seed=42, server_trace=False):
    if min(windows, operations, payload_bytes, batch_size) < 1 or batch_size > 4096:
        raise ValueError("counts must be positive and batch size must be at most 4096")
    # Leave room for keys and framing below the protocol's 64 MiB limit.
    if batch_size * (payload_bytes + 256) >= 64 * 1024 * 1024:
        raise ValueError("batch exceeds the protocol frame budget")
    output = Path(output).resolve()
    output.mkdir(parents=True, exist_ok=False)
    fingerprint = TransformationFingerprint.from_descriptor("request-profile-v1")
    payload = random.Random(seed).randbytes(payload_bytes)
    def keys(prefix):
        return [CacheKey("request-profile", f"{prefix}-{index}", fingerprint) for index in range(batch_size)]
    warm = keys("warm")
    missing = keys("missing")
    rows = []
    rng = random.Random(seed)
    with java_daemon(output / "store") as daemon, (output / "traces.jsonl").open("w", encoding="utf-8") as trace_file:
        with AetherTrainingCache(port=daemon["port"]) as setup:
            setup.put_many([(key, payload) for key in warm])
        for window in range(windows):
            order = [(case, traced) for case in ("warm-hit", "miss", "publish") for traced in (False, True)]
            rng.shuffle(order)
            for order_index, (case, traced) in enumerate(order):
                traces = []
                with AetherTrainingCache(port=daemon["port"], trace_sink=traces.append if traced else None,
                                         server_trace=server_trace and traced) as client:
                    # Establish connection and exercise the operation before timing.
                    def invoke(index):
                        if case == "warm-hit":
                            return client.get_many(warm)
                        if case == "miss":
                            return client.get_many(missing)
                        client.put_many([(key, payload) for key in keys(f"publish-{window}-{traced}-{index}")])
                    for index in range(-3, 0):
                        invoke(index)
                    traces.clear()
                    wall_start = time.perf_counter_ns()
                    cpu_start = time.process_time_ns()
                    for index in range(operations):
                        result = invoke(index)
                        if case == "warm-hit" and result != {key: payload for key in warm}:
                            raise AssertionError("warm read differs from published data")
                        if case == "miss" and result:
                            raise AssertionError("unexpected hit")
                    cpu_ns = time.process_time_ns() - cpu_start
                    wall_ns = time.perf_counter_ns() - wall_start
                    if client.trace_errors:
                        raise RuntimeError("trace records were lost")
                    for trace in traces:
                        trace.update(window=window, case=case, orderIndex=order_index)
                        trace_file.write(json.dumps(trace) + "\n")
                    rows.append({"window": window, "orderIndex": order_index, "case": case,
                                 "instrumented": traced, "operations": operations, "wallNs": wall_ns,
                                 "clientCpuNs": cpu_ns, "requestMeanMs": wall_ns / operations / 1e6,
                                 "traceCount": len(traces), "protocol": client.protocol_metrics()})
    overhead = {}
    for case in ("warm-hit", "miss", "publish"):
        ratios = []
        for window in range(windows):
            pair = {r["instrumented"]: r["wallNs"] for r in rows if r["case"] == case and r["window"] == window}
            ratios.append(pair[True] / pair[False])
        overhead[case] = {"pairedWindowWallRatios": ratios, "medianRatio": statistics.median(ratios)}
    report = {"schema": "aether-client-profile-v1", "measurementRole": "exploratory client request timing",
              "stageAComplete": False, "serverAttribution": "correlated inclusive stages; transport and scheduler unresolved" if server_trace else "unavailable", "pageCacheCondition": "uncontrolled",
              "scope": "window includes public API calls, result checks, and in-memory trace collection; excludes startup, prepopulation, warmup, trace-file output",
              "protocolCounterScope": "includes three warmup operations per connection",
              "windowIndependence": "sequential randomized windows sharing one daemon and store; not independent process repetitions",
              "arguments": {"windows": windows, "operations": operations, "payloadBytes": payload_bytes,
                            "batchSize": batch_size, "seed": seed, "serverTrace": server_trace},
              "rows": rows, "instrumentationOverhead": overhead, "environment": environment()}
    write_json(output / "profile.json", report)
    print(f"Client timing report: {output / 'profile.json'}", flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--windows", type=int, default=30)
    parser.add_argument("--operations", type=int, default=10)
    parser.add_argument("--payload-bytes", type=int, default=4096)
    parser.add_argument("--batch-size", type=int, default=1)
    parser.add_argument("--seed", type=int, default=42)
    parser.add_argument("--server-trace", action="store_true", help="Request correlated server durations (requires protocol v2)")
    args = parser.parse_args()
    profile(args.output, windows=args.windows, operations=args.operations,
            payload_bytes=args.payload_bytes, batch_size=args.batch_size, seed=args.seed, server_trace=args.server_trace)


if __name__ == "__main__":
    main()
