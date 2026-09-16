import argparse
import hashlib
import json
import time
from pathlib import Path

from aether_training_cache.tokens import decode_tokens, encode_tokens


TARGETS_MS = (0.001, 0.002, 0.004, 0.006, 0.008, 0.01, 0.015, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2, 5, 10, 25, 50, 100)


def preprocess(sample, target_ms):
    deadline = time.perf_counter_ns() + int(target_ms * 1_000_000)
    digest = sample
    while time.perf_counter_ns() < deadline:
        digest = hashlib.sha256(digest).digest()
    return [byte for byte in digest], [1] * len(digest)


def timed(fn, count):
    started = time.perf_counter_ns()
    values = [fn(index) for index in range(count)]
    return time.perf_counter_ns() - started, values


def main():
    parser = argparse.ArgumentParser(description="Measure deterministic preprocessing cache break-even.")
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--targets-ms", default=','.join(str(value) for value in TARGETS_MS))
    parser.add_argument("--output", default="build/preprocessing-break-even.json")
    args = parser.parse_args()
    if args.samples < 1 or args.epochs < 1:
        raise ValueError("samples and epochs must be positive")
    source = [f"sample-{index}".encode() for index in range(args.samples)]
    results = []
    targets = tuple(float(value) for value in args.targets_ms.split(','))
    if any(value <= 0 for value in targets):
        raise ValueError("targets-ms must contain positive values")
    for target in targets:
        preprocess_started = time.perf_counter_ns()
        token_values = [preprocess(source[index], target) for index in range(args.samples)]
        preprocess_nanos = time.perf_counter_ns() - preprocess_started
        encode_started = time.perf_counter_ns()
        cached = [encode_tokens(tokens, mask) for tokens, mask in token_values]
        encode_nanos = time.perf_counter_ns() - encode_started
        populate_nanos = preprocess_nanos + encode_nanos
        populate_checksum = 0
        for index, value in enumerate(cached):
            tokens, mask = decode_tokens(value)
            populate_checksum = rolling_checksum(populate_checksum, index, tokens, mask)
        reuse_nanos = 0
        checksum = 0
        reuse_checksums = []
        reuse_epoch_nanos = []
        for _ in range(args.epochs - 1):
            started = time.perf_counter_ns()
            epoch_checksum = 0
            for index, value in enumerate(cached):
                tokens, mask = decode_tokens(value)
                epoch_checksum = rolling_checksum(epoch_checksum, index, tokens, mask)
            epoch_nanos = time.perf_counter_ns() - started
            reuse_epoch_nanos.append(epoch_nanos)
            reuse_checksums.append(epoch_checksum)
            checksum = epoch_checksum
            reuse_nanos += epoch_nanos
        recompute_nanos = timed(lambda index: preprocess(source[index], target), args.samples)[0] * args.epochs
        aether_total = populate_nanos + reuse_nanos
        recompute_epoch_nanos = recompute_nanos // args.epochs
        results.append({
            "backend": "AETHER_TOKEN_FORMAT",
            "preprocessCostTargetMs": target,
            "samples": args.samples,
            "epochs": args.epochs,
            "populateNanos": populate_nanos,
            "preprocessNanos": preprocess_nanos,
            "encodeNanos": encode_nanos,
            "encodePercentOfPopulate": encode_nanos / populate_nanos if populate_nanos else 0.0,
            "reuseNanos": reuse_nanos,
            "recomputeNanos": recompute_nanos,
            "cacheTotalNanos": aether_total,
            "recomputeEpochNanos": recompute_epoch_nanos,
            "reuseEpochMedianNanos": median(reuse_epoch_nanos),
            "reuseMicrosPerSample": median(reuse_epoch_nanos) / args.samples / 1000 if reuse_epoch_nanos else 0.0,
            "breakEvenEpoch": break_even(populate_nanos, median(reuse_epoch_nanos), recompute_epoch_nanos, args.epochs),
            "populateChecksum": populate_checksum,
            "reuseChecksum": checksum,
            "checksumMatches": all(value == populate_checksum for value in reuse_checksums) if reuse_checksums else True,
        })
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    report = {"samples": args.samples, "epochs": args.epochs, "breakEvenMargin": 0.01,
              "targetsMs": list(targets), "results": results}
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def rolling_checksum(current, index, tokens, mask):
    value = sum(tokens) + sum(mask)
    return ((current * 1_000_003) + value + index + 0x9E3779B9) & 0xFFFFFFFFFFFFFFFF


def median(values):
    if not values:
        return 0
    ordered = sorted(values)
    return ordered[len(ordered) // 2]


def break_even(populate, reuse_epoch, recompute_epoch, epochs):
    for epoch in range(1, epochs + 1):
        cached_total = populate + reuse_epoch * max(0, epoch - 1)
        if cached_total < recompute_epoch * epoch * 0.99:
            return epoch
    return None


if __name__ == "__main__":
    main()
