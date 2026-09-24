import argparse
import json
import mmap
import time
from pathlib import Path

from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint
from aether_training_cache.real_tokenizer import RealTokenizer
from aether_training_cache.tokens import decode_tokens


def main():
    parser = argparse.ArgumentParser(description="Measure complete tokenizer cache lifecycle break-even.")
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--epochs", default="1,2,5,10,20,50,100,200")
    parser.add_argument("--aether-port", type=int)
    parser.add_argument("--output", default="build/tokenizer-lifecycle.json")
    args = parser.parse_args()
    epoch_points = [int(value) for value in args.epochs.split(",")]
    tokenizer = RealTokenizer(max_length=512)
    transform = TransformationFingerprint.from_mapping(tokenizer.fingerprint_descriptor)
    documents = [f"Document {index}: Aether deterministic training input for lifecycle measurement." for index in range(args.samples)]
    keys = [CacheKey("lifecycle-tokenizer", f"doc-{index}", transform) for index in range(args.samples)]
    token_values = [tokenizer.tokenize(document) for document in documents]
    static_path = Path(args.output).with_suffix(".tokens")
    static_path.parent.mkdir(parents=True, exist_ok=True)
    offsets = []
    with static_path.open("wb") as stream:
        for value in token_values:
            offsets.append((stream.tell(), len(value)))
            stream.write(value)
    results = {"TOKENIZE_EVERY_EPOCH": lifecycle(epoch_points, lambda: token_values, args.samples),
               "STATIC_PRETOKENIZED_MMAP": lifecycle(epoch_points, lambda: read_static(static_path, offsets), args.samples)}
    if args.aether_port:
        with AetherTrainingCache(port=args.aether_port) as cache:
            populate_started = time.perf_counter_ns()
            cache.put_many(zip(keys, token_values))
            populate_nanos = time.perf_counter_ns() - populate_started
            aether_epochs = lifecycle(epoch_points, lambda: cache.get_many(keys), args.samples, decode=True)
            results["AETHER_POPULATE_REUSE"] = {"populateNanos": populate_nanos, "epochs": aether_epochs}
    report = {"samples": args.samples, "batchSize": args.batch_size, "epochPoints": epoch_points,
              "tokenizer": tokenizer.fingerprint_descriptor, "results": results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")
    static_path.unlink(missing_ok=True)


def lifecycle(epoch_points, loader, samples, decode=False):
    all_epochs = []
    cumulative = 0
    for epoch in range(1, max(epoch_points) + 1):
        started = time.perf_counter_ns()
        values = loader()
        checksum = 0
        token_count = 0
        iterable = values.values() if isinstance(values, dict) else values
        for index, value in enumerate(iterable):
            tokens, mask = decode_tokens(value) if decode else decode_tokens(value)
            checksum = ((checksum * 1_000_003) + sum(tokens) + sum(mask) + index) & 0xFFFFFFFFFFFFFFFF
            token_count += len(tokens)
        elapsed = time.perf_counter_ns() - started
        cumulative += elapsed
        if epoch in epoch_points:
            seconds = max(cumulative, 1) / 1_000_000_000
            all_epochs.append({"epochs": epoch, "cumulativeNanos": cumulative,
                               "samplesPerSecond": samples * epoch / seconds,
                               "tokensPerSecond": token_count * epoch / seconds,
                               "checksum": checksum})
    return all_epochs


def read_static(path, offsets):
    with path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
        return [bytes(mapped[offset:offset + length]) for offset, length in offsets]


if __name__ == "__main__":
    main()
