import argparse
import json
import random
import subprocess
import sys
import tempfile
from pathlib import Path


def main():
    parser = argparse.ArgumentParser(description="Repeat lifecycle bucket runs with randomized bucket order.")
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--runs", type=int, default=10)
    parser.add_argument("--buckets", default="25,64,128,256,512")
    parser.add_argument("--epochs", default="1,2,3,4,5,10,20,50,100,200")
    parser.add_argument("--aether-port", type=int, required=True)
    parser.add_argument("--output", default="build/tokenizer-lifecycle-statistics.json")
    args = parser.parse_args()
    buckets = [int(value) for value in args.buckets.split(",")]
    raw = {str(bucket): [] for bucket in buckets}
    for run in range(args.runs):
        order = buckets[:]
        random.SystemRandom().shuffle(order)
        for bucket in order:
            with tempfile.NamedTemporaryFile(suffix=".json", delete=False) as temporary:
                temporary_path = Path(temporary.name)
            command = [sys.executable, str(Path(__file__).with_name("benchmark_tokenizer_lifecycle_buckets.py")),
                       "--samples", str(args.samples), "--batch-size", str(args.samples),
                       "--buckets", str(bucket), "--epochs", args.epochs,
                       "--aether-port", str(args.aether_port), "--output", str(temporary_path)]
            subprocess.run(command, check=True, capture_output=True, text=True)
            report = json.loads(temporary_path.read_text())
            temporary_path.unlink(missing_ok=True)
            result = report["results"][0]
            raw[str(bucket)].append({
                "run": run,
                "order": order,
                "targetTokens": result["targetTokens"],
                "actualTokenP50": result["actualTokenP50"],
                "tokenizeOnceNanos": result["tokenizeOnceNanos"],
                "populateNanos": result["results"]["AETHER_POPULATE_REUSE"]["populateNanos"],
                "breakEvenEpoch": result["breakEvenEpoch"],
                "checksum": result["results"]["AETHER_POPULATE_REUSE"]["epochs"][0]["checksum"],
            })
            print(f"run {run + 1}/{args.runs}, bucket {bucket}")
    aggregate = {}
    for bucket, values in raw.items():
        populations = [value["populateNanos"] / 1e6 for value in values]
        valid_break_even = [value["breakEvenEpoch"] for value in values if value["breakEvenEpoch"] is not None]
        aggregate[bucket] = {
            "runs": len(values),
            "actualTokenP50": values[0]["actualTokenP50"],
            "populateMs": stats(populations),
            "breakEvenEpochMedian": median(valid_break_even) if valid_break_even else None,
            "breakEvenEpochValues": valid_break_even,
            "checksums": sorted({value["checksum"] for value in values}),
            "rawRuns": values,
        }
    report = {"samples": args.samples, "runs": args.runs, "buckets": buckets,
              "epochPoints": [int(value) for value in args.epochs.split(",")],
              "breakEvenMargin": 0.01, "aggregate": aggregate}
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def stats(values):
    import statistics
    mean = statistics.mean(values)
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0.0
    margin = 1.96 * standard_deviation / len(values) ** 0.5 if len(values) > 1 else 0.0
    return {"medianMs": median(values), "meanMs": mean, "stddevMs": standard_deviation,
            "confidence95Ms": {"low": mean - margin, "high": mean + margin},
            "minMs": min(values), "maxMs": max(values)}


def median(values):
    import statistics
    return statistics.median(values)


if __name__ == "__main__":
    main()
