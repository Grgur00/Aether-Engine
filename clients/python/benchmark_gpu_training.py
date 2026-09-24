import argparse
import json
import statistics
import time
from pathlib import Path

from aether_training_cache import AetherTrainingCache, CacheKey, MappedSegmentRegistry, TransformationFingerprint
from aether_training_cache.real_tokenizer import RealTokenizer
from aether_training_cache.tokens import decode_tokens
from rocm_environment import inspect, smoke_test


class TinyDecoder:
    def __init__(self, vocab_size, sequence_length, embedding_size, layers, device):
        import torch
        import torch.nn as nn
        class Model(nn.Module):
            def __init__(self):
                super().__init__()
                self.embedding = nn.Embedding(vocab_size, embedding_size)
                self.position = nn.Embedding(sequence_length, embedding_size)
                layer = nn.TransformerEncoderLayer(embedding_size, 4, embedding_size * 4, batch_first=True)
                self.encoder = nn.TransformerEncoder(layer, layers)
                self.output = nn.Linear(embedding_size, vocab_size)
            def forward(self, tokens):
                positions = torch.arange(tokens.shape[1], device=tokens.device)
                hidden = self.embedding(tokens) + self.position(positions)
                return self.output(self.encoder(hidden))
        self.model = Model().to(device)


def main():
    parser = argparse.ArgumentParser(description="Compare Aether GPU training input backends.")
    parser.add_argument("--cache-dir", required=True)
    parser.add_argument("--segment-directory", required=True)
    parser.add_argument("--port", type=int, default=9484)
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--sequence-length", type=int, default=512)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--steps", type=int, default=20)
    parser.add_argument("--warmup-steps", type=int, default=5)
    parser.add_argument("--embedding-size", type=int, default=128)
    parser.add_argument("--layers", type=int, default=2)
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--output", default="build/gpu-training.json")
    parser.add_argument("--probe-only", action="store_true", help="Report device capability without running training")
    parser.add_argument("--require-rocm", action="store_true", help="Require torch.version.hip")
    args = parser.parse_args()
    if args.sequence_length < 1 or args.batch_size < 1 or args.steps < 1:
        raise ValueError("sequence-length, batch-size, and steps must be positive")
    import torch
    try:
        environment = inspect(args.device, require_rocm=args.require_rocm)
        smoke_test(args.device)
    except RuntimeError as failure:
        report = {"environment": {"available": False, "error": str(failure)},
                  "dataset": {"samples": args.samples, "sequenceLength": args.sequence_length},
                  "model": {"embeddingSize": args.embedding_size, "layers": args.layers},
                  "training": {"steps": args.steps, "warmupSteps": args.warmup_steps}, "backends": {}}
        output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(report, indent=2) + "\n")
        print(json.dumps(report, indent=2)); print(f"Report: {output}")
        if args.probe_only:
            return
        raise
    if args.probe_only:
        print(json.dumps({"environment": environment}, indent=2))
        return
    torch.manual_seed(1234)
    tokenizer = RealTokenizer(max_length=args.sequence_length)
    transform = TransformationFingerprint.from_mapping(tokenizer.fingerprint_descriptor)
    documents = [f"GPU training document {index}. Deterministic cached input for Aether validation." for index in range(args.samples)]
    keys = [CacheKey("gpu-training", f"sample-{index}", transform) for index in range(args.samples)]
    encoded = [tokenizer.tokenize(document) for document in documents]
    token_ids = [decode_tokens(value)[0] for value in encoded]
    token_ids = [ids + [0] * (args.sequence_length - len(ids)) for ids in token_ids]
    token_ids = [ids[:args.sequence_length] for ids in token_ids]
    labels = [ids[1:] + [0] for ids in token_ids]
    checksum = checksum_rows(token_ids)
    with AetherTrainingCache(port=args.port) as cache:
        cache.put_many(zip(keys, encoded))
        with MappedSegmentRegistry(args.segment_directory) as registry:
            model = TinyDecoder(100000, args.sequence_length, args.embedding_size, args.layers, args.device).model
            optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
            loss_function = torch.nn.CrossEntropyLoss()
            backends = {}
            for backend in ("TOKENIZE_EVERY_EPOCH", "AETHER_GET_MANY", "STATIC_MMAP", "RAM_READY"):
                measurements = []
                for step in range(args.warmup_steps + args.steps):
                    start = time.perf_counter_ns()
                    batch_start = (step * args.batch_size) % args.samples
                    indices = [(batch_start + offset) % args.samples for offset in range(args.batch_size)]
                    if backend == "TOKENIZE_EVERY_EPOCH":
                        batch = [decode_tokens(tokenizer.tokenize(documents[index]))[0] for index in indices]
                    elif backend == "AETHER_GET_MANY":
                        values = cache.get_many(keys[index] for index in indices)
                        batch = [decode_tokens(values[keys[index]])[0] for index in indices]
                    elif backend == "STATIC_MMAP":
                        batch = [token_ids[index] for index in indices]
                    else:
                        batch = [token_ids[index] for index in indices]
                    batch = torch.tensor(batch, dtype=torch.long)
                    targets = torch.tensor([labels[index] for index in indices], dtype=torch.long)
                    prepare_nanos = time.perf_counter_ns() - start
                    transfer_start = time.perf_counter_ns()
                    batch = batch.to(args.device)
                    targets = targets.to(args.device)
                    torch.cuda.synchronize(args.device)
                    transfer_nanos = time.perf_counter_ns() - transfer_start
                    forward_start = time.perf_counter_ns()
                    logits = model(batch)
                    torch.cuda.synchronize(args.device)
                    forward_nanos = time.perf_counter_ns() - forward_start
                    backward_start = time.perf_counter_ns()
                    loss = loss_function(logits.reshape(-1, logits.shape[-1]), targets.reshape(-1))
                    loss.backward()
                    torch.cuda.synchronize(args.device)
                    backward_nanos = time.perf_counter_ns() - backward_start
                    optimizer_start = time.perf_counter_ns()
                    optimizer.step(); optimizer.zero_grad(set_to_none=True)
                    torch.cuda.synchronize(args.device)
                    optimizer_nanos = time.perf_counter_ns() - optimizer_start
                    if step >= args.warmup_steps:
                        measurements.append({"step": step - args.warmup_steps, "prepareNanos": prepare_nanos,
                            "transferNanos": transfer_nanos, "forwardNanos": forward_nanos,
                            "backwardNanos": backward_nanos, "optimizerNanos": optimizer_nanos,
                            "stepNanos": time.perf_counter_ns() - start, "loss": float(loss.item())})
                backends[backend] = summarize(measurements, args.batch_size, args.sequence_length)
            report = {"environment": environment, "dataset": {"samples": args.samples, "sequenceLength": args.sequence_length,
                "batchSize": args.batch_size, "checksum": checksum}, "model": {"embeddingSize": args.embedding_size, "layers": args.layers},
                "training": {"steps": args.steps, "warmupSteps": args.warmup_steps}, "backends": backends}
    output = Path(args.output); output.parent.mkdir(parents=True, exist_ok=True); output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2)); print(f"Report: {output}")


def checksum_rows(rows):
    checksum = 0
    for row in rows:
        for value in row:
            checksum = (checksum * 1000003 + value + 0x9E3779B9) & 0xFFFFFFFFFFFFFFFF
    return checksum


def summarize(measurements, batch_size, sequence_length):
    seconds = sum(item["stepNanos"] for item in measurements) / 1_000_000_000
    return {"steps": len(measurements), "stepsPerSecond": len(measurements) / seconds,
        "tokensPerSecond": len(measurements) * batch_size * sequence_length / seconds,
        "prepareMeanMs": statistics.mean(item["prepareNanos"] for item in measurements) / 1e6,
        "transferMeanMs": statistics.mean(item["transferNanos"] for item in measurements) / 1e6,
        "forwardMeanMs": statistics.mean(item["forwardNanos"] for item in measurements) / 1e6,
        "backwardMeanMs": statistics.mean(item["backwardNanos"] for item in measurements) / 1e6,
        "optimizerMeanMs": statistics.mean(item["optimizerNanos"] for item in measurements) / 1e6,
        "stepMeanMs": statistics.mean(item["stepNanos"] for item in measurements) / 1e6,
        "rawSteps": measurements}


if __name__ == "__main__":
    main()