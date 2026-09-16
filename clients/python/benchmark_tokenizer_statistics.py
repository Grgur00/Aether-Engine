import argparse
import json
import random
import statistics
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint
from aether_training_cache.real_tokenizer import RealTokenizer
from aether_training_cache.tokens import decode_tokens


def main():
    parser = argparse.ArgumentParser(description="Independent multi-process tokenizer backend comparison.")
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--warmup-epochs", type=int, default=5)
    parser.add_argument("--epochs", type=int, default=10)
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--batch-size", type=int, default=128)
    parser.add_argument("--port", type=int, default=9484)
    parser.add_argument("--output", default="build/tokenizer-statistics.json")
    parser.add_argument("--_worker", action="store_true")
    args = parser.parse_args()
    if args._worker:
        print(json.dumps(run_worker(args)))
        return
    records = []
    for run in range(args.runs):
        command = [sys.executable, __file__, "--samples", str(args.samples),
                   "--warmup-epochs", str(args.warmup_epochs), "--epochs", str(args.epochs),
                   "--batch-size", str(args.batch_size), "--port", str(args.port), "--_worker"]
        completed = subprocess.run(command, check=True, capture_output=True, text=True)
        records.append(json.loads(completed.stdout.strip().splitlines()[-1]))
        print(f"run {run + 1}/{args.runs}: order={records[-1]['order']}")
    report = aggregate(args, records)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def run_worker(args):
    tokenizer = RealTokenizer(max_length=512)
    transform = TransformationFingerprint.from_mapping(tokenizer.fingerprint_descriptor)
    documents = [f"Document {index}: Aether persists deterministic training transformations for model input." for index in range(args.samples)]
    keys = [CacheKey("statistics-tokenizer", f"doc-{index}", transform) for index in range(args.samples)]
    values = [tokenizer.tokenize(document) for document in documents]
    order = ["TOKENIZE_EVERY_EPOCH", "STATIC_PRETOKENIZED_MMAP", "AETHER_GET_MANY"]
    random.Random(time.time_ns()).shuffle(order)
    descriptor, temporary_name = tempfile.mkstemp(suffix=".tokens")
    import os
    os.close(descriptor)
    static_path = Path(temporary_name)
    offsets = []
    with static_path.open("wb") as stream:
        for value in values:
            offsets.append((stream.tell(), len(value)))
            stream.write(value)
    try:
        aether = AetherTrainingCache(port=args.port)
        aether.put_many(zip(keys, values))
        results = {}
        for backend in order:
            for _ in range(args.warmup_epochs):
                consume(backend, args, documents, keys, values, offsets, static_path, aether)
            epochs = [consume(backend, args, documents, keys, values, offsets, static_path, aether)
                      for _ in range(args.epochs)]
            results[backend] = summarize(epochs)
        protocol = aether.protocol_metrics()
        aether.close()
        return {"order": order, "backends": results, "protocol": protocol}
    finally:
        static_path.unlink(missing_ok=True)


def consume(backend, args, documents, keys, values, offsets, static_path, aether):
    started = time.perf_counter_ns()
    checksum = 0
    token_count = 0
    if backend == "TOKENIZE_EVERY_EPOCH":
        payloads = [aether_tokenize(document) for document in documents]
    elif backend == "STATIC_PRETOKENIZED_MMAP":
        with static_path.open("rb") as stream:
            import mmap
            with mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
                payloads = [bytes(mapped[offset:offset + length]) for offset, length in offsets]
    else:
        payloads = []
        for start in range(0, len(keys), args.batch_size):
            payloads.extend(aether.get_many(keys[start:start + args.batch_size]).values())
    for index, value in enumerate(payloads):
        tokens, mask = decode_tokens(value)
        checksum = ((checksum * 1_000_003) + sum(tokens) + sum(mask) + index) & 0xFFFFFFFFFFFFFFFF
        token_count += len(tokens)
    if checksum == 0:
        raise RuntimeError("unexpected zero checksum")
    elapsed = max(time.perf_counter_ns() - started, 1)
    seconds = elapsed / 1_000_000_000
    return {"nanos": elapsed, "batchSize": args.batch_size, "documentsPerSecond": args.samples / seconds,
        "tokensPerSecond": token_count / seconds, "checksum": checksum, "tokenCount": token_count}


def aether_tokenize(document):
    return _worker_tokenizer.tokenize(document)


_worker_tokenizer = RealTokenizer(max_length=512)


def summarize(epochs):
    batch_values = [epoch["nanos"] / 1000 for epoch in epochs]
    values = [value / epochs[0]["batchSize"] for value in batch_values]
    docs = [epoch["documentsPerSecond"] for epoch in epochs]
    tokens = [epoch["tokensPerSecond"] for epoch in epochs]
    return {"medianMicrosecondsPerBatch": statistics.median(batch_values),
            "meanMicrosecondsPerBatch": statistics.mean(batch_values),
            "p95MicrosecondsPerBatch": percentile(batch_values, 95),
            "standardDeviationMicrosecondsPerBatch": statistics.stdev(batch_values) if len(batch_values) > 1 else 0,
            "confidence95MicrosecondsPerBatch": confidence(batch_values),
            "medianMicrosecondsPerDocument": statistics.median(values),
            "meanMicrosecondsPerDocument": statistics.mean(values),
            "p95MicrosecondsPerDocument": percentile(values, 95),
            "standardDeviationMicrosecondsPerDocument": statistics.stdev(values) if len(values) > 1 else 0,
            "confidence95MicrosecondsPerDocument": confidence(values),
            "documentsPerSecondMean": statistics.mean(docs),
            "tokensPerSecondMean": statistics.mean(tokens),
            "checksums": sorted({epoch["checksum"] for epoch in epochs})}


def aggregate(args, records):
    backends = {}
    for backend in records[0]["backends"]:
        values = [record["backends"][backend]["medianMicrosecondsPerDocument"] for record in records]
        batch_values = [record["backends"][backend]["medianMicrosecondsPerBatch"] for record in records]
        docs = [record["backends"][backend]["documentsPerSecondMean"] for record in records]
        tokens = [record["backends"][backend]["tokensPerSecondMean"] for record in records]
        backends[backend] = {"runs": len(values), "medianMicrosecondsPerBatch": statistics.median(batch_values),
                     "meanMicrosecondsPerBatch": statistics.mean(batch_values),
                     "p95MicrosecondsPerBatch": percentile(batch_values, 95),
                     "standardDeviationMicrosecondsPerBatch": statistics.stdev(batch_values),
                     "confidence95MicrosecondsPerBatch": confidence(batch_values),
                     "medianMicrosecondsPerDocument": statistics.median(values),
                             "meanMicrosecondsPerDocument": statistics.mean(values),
                             "p95MicrosecondsPerDocument": percentile(values, 95),
                             "standardDeviationMicrosecondsPerDocument": statistics.stdev(values),
                             "confidence95MicrosecondsPerDocument": confidence(values),
                             "documentsPerSecondMean": statistics.mean(docs),
                             "tokensPerSecondMean": statistics.mean(tokens),
                             "allChecksums": sorted({checksum for record in records for checksum in record["backends"][backend]["checksums"]})}
    return {"samples": args.samples, "batchSize": args.batch_size, "warmupEpochs": args.warmup_epochs,
            "measuredEpochs": args.epochs, "independentRuns": args.runs, "backends": backends,
            "protocol": records[-1]["protocol"]}


def percentile(values, value):
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int(value / 100 * len(ordered) + .999) - 1))]


def confidence(values):
    mean = statistics.mean(values)
    if len(values) < 2:
        return {"low": mean, "high": mean}
    margin = 1.96 * statistics.stdev(values) / len(values) ** .5
    return {"low": mean - margin, "high": mean + margin}


if __name__ == "__main__":
    main()
