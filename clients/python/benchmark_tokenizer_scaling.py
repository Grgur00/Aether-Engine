import argparse
import json
import time
from pathlib import Path

from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint
from aether_training_cache.real_tokenizer import RealTokenizer
from aether_training_cache.tokens import decode_tokens


def main():
    parser = argparse.ArgumentParser(description="Measure warm Aether tokenizer reuse across batch sizes.")
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--warmup-epochs", type=int, default=5)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--batch-sizes", default="1,8,16,32,64,128,256,512")
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--port", type=int, default=9484)
    parser.add_argument("--output", default="build/tokenizer-scaling.json")
    args = parser.parse_args()
    if args.samples < 1 or args.warmup_epochs < 0 or args.epochs < 1:
        raise ValueError("samples, warmup-epochs, and epochs are invalid")
    batch_sizes = [int(value) for value in args.batch_sizes.split(",")]
    tokenizer = RealTokenizer(max_length=args.max_length)
    transform = TransformationFingerprint.from_mapping(tokenizer.fingerprint_descriptor)
    documents = [f"Document {index}: Aether persists deterministic training transformations for model input." for index in range(args.samples)]
    keys = [CacheKey("real-tokenizer", f"doc-{index}", transform) for index in range(args.samples)]
    results = []
    with AetherTrainingCache(port=args.port) as cache:
        cache.put_many((key, tokenizer.tokenize(document)) for key, document in zip(keys, documents))
        population_protocol = cache.protocol_metrics()
        for batch_size in batch_sizes:
            before_protocol = cache.protocol_metrics()
            for _ in range(args.warmup_epochs):
                consume_batches(cache, keys, batch_size)
            epochs = []
            for epoch in range(args.epochs):
                started = time.perf_counter_ns()
                checksum, batches = consume_batches(cache, keys, batch_size)
                elapsed = time.perf_counter_ns() - started
                seconds = max(elapsed, 1) / 1_000_000_000
                epochs.append({
                    "epoch": epoch,
                    "nanos": elapsed,
                    "batches": batches,
                    "batchSize": batch_size,
                    "documentsPerSecond": args.samples / seconds,
                    "microsecondsPerDocument": elapsed / args.samples / 1000,
                    "checksum": checksum,
                })
            steady = [epoch["microsecondsPerDocument"] for epoch in epochs]
            ordered = sorted(steady)
            results.append({
                "batchSize": batch_size,
                "warmupEpochs": args.warmup_epochs,
                "measuredEpochs": args.epochs,
                "medianMicrosecondsPerDocument": percentile(ordered, 50),
                "meanMicrosecondsPerDocument": sum(steady) / len(steady),
                "p95MicrosecondsPerDocument": percentile(ordered, 95),
                "minMicrosecondsPerDocument": ordered[0],
                "maxMicrosecondsPerDocument": ordered[-1],
                "epochs": epochs,
                "protocol": protocol_delta(before_protocol, cache.protocol_metrics()),
                "populationProtocol": population_protocol,
            })
    report = {"samples": args.samples, "warmupEpochs": args.warmup_epochs,
              "measuredEpochs": args.epochs, "batchSizes": batch_sizes,
              "tokenizer": tokenizer.fingerprint_descriptor, "results": results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def consume_batches(cache, keys, batch_size):
    checksum = 0
    batches = 0
    for start in range(0, len(keys), batch_size):
        batch = keys[start:start + batch_size]
        values = cache.get_many(batch)
        if len(values) != len(batch):
            raise RuntimeError("token cache batch miss")
        for index, key in enumerate(batch):
            tokens, mask = decode_tokens(values[key])
            checksum = ((checksum * 1_000_003) + sum(tokens) + sum(mask) + start + index) & 0xFFFFFFFFFFFFFFFF
        batches += 1
    return checksum, batches


def percentile(ordered, value):
    index = min(len(ordered) - 1, max(0, int((value / 100) * len(ordered) + .999) - 1))
    return ordered[index]


def protocol_delta(before, after):
    counts = after["operationCounts"]
    previous = before["operationCounts"]
    return {
        "connectionsOpened": after["connectionsOpened"],
        "requestsSent": after["requestsSent"] - before["requestsSent"],
        "getManyRequests": counts.get(5, 0) - previous.get(5, 0),
        "singleGetRequests": counts.get(1, 0) - previous.get(1, 0),
        "putManyRequests": counts.get(6, 0) - previous.get(6, 0),
        "singlePutRequests": counts.get(2, 0) - previous.get(2, 0),
    }


if __name__ == "__main__":
    main()
