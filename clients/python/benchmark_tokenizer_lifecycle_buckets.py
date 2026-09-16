import argparse
import json
import mmap
import time
from pathlib import Path

from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint
from aether_training_cache.real_tokenizer import RealTokenizer
from aether_training_cache.tokens import decode_tokens


DEFAULT_BUCKETS = (25, 64, 128, 256, 512)
DEFAULT_EPOCHS = (1, 2, 5, 10, 20, 50, 100, 200)


def main():
    parser = argparse.ArgumentParser(description="Measure tokenizer cache lifecycle break-even by token length.")
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--buckets", default=','.join(map(str, DEFAULT_BUCKETS)))
    parser.add_argument("--epochs", default=','.join(map(str, DEFAULT_EPOCHS)))
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--aether-port", type=int)
    parser.add_argument("--output", default="build/tokenizer-lifecycle-buckets.json")
    args = parser.parse_args()
    buckets = [int(value) for value in args.buckets.split(',')]
    epoch_points = [int(value) for value in args.epochs.split(',')]
    if args.samples < 1 or args.batch_size < 1 or any(value < 1 for value in buckets + epoch_points):
        raise ValueError("samples, batch-size, buckets, and epochs must be positive")
    tokenizer = RealTokenizer(max_length=args.max_length)
    transform = TransformationFingerprint.from_mapping(tokenizer.fingerprint_descriptor)
    results = []
    cache = AetherTrainingCache(port=args.aether_port) if args.aether_port else None
    try:
        for target in buckets:
            documents = [document(index, target) for index in range(args.samples)]
            keys = [CacheKey(f"lifecycle-{target}", f"doc-{index}", transform)
                    for index in range(args.samples)]
            tokenize_start = time.perf_counter_ns()
            token_values = [tokenizer.tokenize(value) for value in documents]
            tokenize_nanos = time.perf_counter_ns() - tokenize_start
            token_counts = [len(decode_tokens(value)[0]) for value in token_values]
            bucket_result = {"targetTokens": target, "actualTokenP50": percentile(token_counts, 50),
                             "actualTokenP95": percentile(token_counts, 95), "actualTokenP99": percentile(token_counts, 99),
                             "tokenizeOnceNanos": tokenize_nanos,
                             "results": {}}
            bucket_result["results"]["TOKENIZE_EVERY_EPOCH"] = run_epochs(
                epoch_points, lambda: token_values_from_documents(tokenizer, documents), args.samples, args.batch_size)
            static_path = Path(args.output).with_suffix(f".{target}.tokens")
            static_start = time.perf_counter_ns()
            write_static(static_path, token_values)
            static_population = time.perf_counter_ns() - static_start
            bucket_result["results"]["STATIC_PRETOKENIZED_MMAP"] = {
                "populateNanos": tokenize_nanos + static_population,
                "preprocessNanos": tokenize_nanos,
                "storageBuildNanos": static_population,
                "epochs": run_static_epochs(epoch_points, static_path, token_values, args.samples)}
            static_path.unlink(missing_ok=True)
            if cache:
                populate_start = time.perf_counter_ns()
                cache.put_many(zip(keys, token_values))
                publish_nanos = time.perf_counter_ns() - populate_start
                bucket_result["results"]["AETHER_POPULATE_REUSE"] = {
                    "populateNanos": tokenize_nanos + publish_nanos,
                    "preprocessNanos": tokenize_nanos,
                    "publishNanos": publish_nanos,
                    "epochs": run_epochs(epoch_points,
                        lambda: list(cache.get_many(keys).values()), args.samples, args.batch_size)}
            bucket_result["breakEvenEpoch"] = break_even(bucket_result["results"], epoch_points)
            results.append(bucket_result)
    finally:
        if cache:
            cache.close()
    report = {"samples": args.samples, "batchSize": args.batch_size, "epochPoints": epoch_points,
              "tokenBuckets": buckets, "tokenizer": tokenizer.fingerprint_descriptor, "results": results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def document(index, target_tokens):
    seed = "Aether deterministic preprocessing data for a training sample. "
    return f"sample={index} " + seed * max(1, target_tokens // 8)


def token_values_from_documents(tokenizer, documents):
    return [tokenizer.tokenize(document) for document in documents]


def run_epochs(epoch_points, loader, samples, batch_size):
    all_epochs = []
    cumulative = 0
    for epoch in range(1, max(epoch_points) + 1):
        started = time.perf_counter_ns()
        loaded = loader()
        values = []
        for start in range(0, samples, batch_size):
            values.extend(loaded[start:start + batch_size])
        checksum, token_count = consume(values)
        elapsed = time.perf_counter_ns() - started
        cumulative += elapsed
        if epoch in epoch_points:
            all_epochs.append(epoch_result(epoch, cumulative, samples, token_count, checksum))
    return all_epochs


def run_static_epochs(epoch_points, path, token_values, samples):
    offsets = []
    position = 0
    for value in token_values:
        offsets.append((position, len(value)))
        position += len(value)
    all_epochs = []
    cumulative = 0
    for epoch in range(1, max(epoch_points) + 1):
        started = time.perf_counter_ns()
        with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
            values = [bytes(mapped[offset:offset + length]) for offset, length in offsets]
        checksum, token_count = consume(values)
        cumulative += time.perf_counter_ns() - started
        if epoch in epoch_points:
            all_epochs.append(epoch_result(epoch, cumulative, samples, token_count, checksum))
    return all_epochs


def consume(values):
    checksum = 0
    token_count = 0
    for index, value in enumerate(values):
        tokens, mask = decode_tokens(value)
        checksum = ((checksum * 1_000_003) + sum(tokens) + sum(mask) + index) & 0xFFFFFFFFFFFFFFFF
        token_count += len(tokens)
    return checksum, token_count


def write_static(path, values):
    with path.open("wb") as stream:
        for value in values:
            stream.write(value)


def epoch_result(epoch, cumulative, samples, token_count, checksum):
    seconds = max(cumulative, 1) / 1_000_000_000
    return {"epochs": epoch, "cumulativeNanos": cumulative,
            "documentsPerSecond": samples * epoch / seconds,
            "tokensPerSecond": token_count * epoch / seconds, "checksum": checksum}


def break_even(results, epoch_points):
    baseline = {entry["epochs"]: entry["cumulativeNanos"] for entry in results["TOKENIZE_EVERY_EPOCH"]}
    aether = results.get("AETHER_POPULATE_REUSE")
    if not aether:
        return None
    populate = aether["populateNanos"]
    for entry in aether["epochs"]:
        epoch = entry["epochs"]
        reuse_only = entry["cumulativeNanos"]
        if populate + reuse_only < baseline[epoch] * 0.99:
            return epoch
    return None


def percentile(values, percent):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int(percent / 100 * len(ordered) + .999) - 1))]


if __name__ == "__main__":
    main()
