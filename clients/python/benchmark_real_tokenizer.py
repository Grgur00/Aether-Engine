import argparse
import hashlib
import json
import mmap
import struct
import time
import unicodedata
from pathlib import Path

from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint
from aether_training_cache.real_tokenizer import RealTokenizer, document_checksum
from aether_training_cache.tokens import decode_tokens


def main():
    parser = argparse.ArgumentParser(description="Benchmark deterministic real tokenization and cache reuse.")
    parser.add_argument("--samples", type=int, default=1000)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--max-length", type=int, default=512)
    parser.add_argument("--encoding", default="cl100k_base")
    parser.add_argument("--aether-port", type=int)
    parser.add_argument("--output", default="build/real-tokenizer.json")
    args = parser.parse_args()
    tokenizer = RealTokenizer(args.encoding, args.max_length)
    documents = [document(index) for index in range(args.samples)]
    keys = [CacheKey("real-tokenizer", f"doc-{index}", TransformationFingerprint.from_mapping(tokenizer.fingerprint_descriptor))
            for index in range(args.samples)]
    results = {"TOKENIZE_EVERY_EPOCH": [], "STATIC_PRETOKENIZED_MMAP": []}

    static_values = [tokenizer.tokenize(value) for value in documents]
    static_path = Path(args.output).with_suffix(".tokens")
    static_path.parent.mkdir(parents=True, exist_ok=True)
    offsets = []
    with static_path.open("wb") as stream:
        for value in static_values:
            offsets.append((stream.tell(), len(value)))
            stream.write(value)
    with static_path.open("rb") as stream, mmap.mmap(stream.fileno(), 0, access=mmap.ACCESS_READ) as mapped:
        for epoch in range(args.epochs):
            started = time.perf_counter_ns()
            checksum = 0
            token_count = 0
            for index, (offset, length) in enumerate(offsets):
                value = bytes(mapped[offset:offset + length])
                tokens, mask = decode_tokens(value)
                checksum = rolling_checksum(checksum, index, tokens, mask)
                token_count += len(tokens)
            results["STATIC_PRETOKENIZED_MMAP"].append(epoch_result(epoch, started, args.samples, token_count, checksum))

    for epoch in range(args.epochs):
        started = time.perf_counter_ns()
        checksum = 0
        token_count = 0
        for index, document_value in enumerate(documents):
            value = tokenizer.tokenize(document_value)
            tokens, mask = decode_tokens(value)
            checksum = rolling_checksum(checksum, index, tokens, mask)
            token_count += len(tokens)
        results["TOKENIZE_EVERY_EPOCH"].append(epoch_result(epoch, started, args.samples, token_count, checksum))

    if args.aether_port:
        with AetherTrainingCache(port=args.aether_port) as cache:
            populate_started = time.perf_counter_ns()
            cache.put_many((key, tokenizer.tokenize(document_value))
                           for key, document_value in zip(keys, documents))
            populate_nanos = time.perf_counter_ns() - populate_started
            reuse = []
            for epoch in range(args.epochs):
                started = time.perf_counter_ns()
                checksum = 0
                token_count = 0
                values = cache.get_many(keys)
                for index, key in enumerate(keys):
                    value = values.get(key)
                    if value is None:
                        raise RuntimeError("Aether token cache miss")
                    tokens, mask = decode_tokens(value)
                    checksum = rolling_checksum(checksum, index, tokens, mask)
                    token_count += len(tokens)
                reuse.append(epoch_result(epoch, started, args.samples, token_count, checksum))
            results["AETHER_POPULATE_REUSE"] = {"populateNanos": populate_nanos, "epochs": reuse}

    report = {"samples": args.samples, "epochs": args.epochs, "tokenizer": tokenizer.fingerprint_descriptor,
              "documents": {"rawBytes": sum(len(value.encode("utf-8")) for value in documents),
                            "meanCharacters": sum(len(value) for value in documents) / args.samples},
              "results": results}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def document(index):
    return unicodedata.normalize("NFKC", f"Document {index}: Aether persists deterministic training transformations. "
                                 "This sentence supplies repeated vocabulary, punctuation, and a stable source identity.")


def rolling_checksum(current, index, tokens, mask):
    return ((current * 1_000_003) + sum(tokens) + sum(mask) + index + 0x9E3779B9) & 0xFFFFFFFFFFFFFFFF


def epoch_result(epoch, started, samples, token_count, checksum):
    elapsed = time.perf_counter_ns() - started
    seconds = max(elapsed, 1) / 1_000_000_000
    return {"epoch": epoch, "nanos": elapsed, "documentsPerSecond": samples / seconds,
            "tokensPerSecond": token_count / seconds, "tokenCount": token_count, "checksum": checksum}


if __name__ == "__main__":
    main()
