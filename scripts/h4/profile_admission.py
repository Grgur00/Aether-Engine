"""Summarize diagnostic JFR evidence; sampled weights are not exact allocations."""
import argparse
import json
import subprocess
from pathlib import Path

from .common import digest, write_json


def summarize(events):
    allocations = []
    heaps = []
    for event in events:
        values = event.get("values", {})
        if event.get("type") == "jdk.ObjectAllocationSample":
            frames = (values.get("stackTrace") or {}).get("frames", [])
            stack = []
            for frame in frames:
                method = frame.get("method", {})
                stack.append(method.get("type", {}).get("name", "").replace("/", ".") +
                             "." + method.get("name", ""))
            allocations.append((values.get("weight", 0), stack))
        elif event.get("type") == "jdk.GCHeapSummary":
            heaps.append(values.get("heapUsed", 0))

    def weighted(fragment):
        return sum(weight for weight, stack in allocations if any(fragment in frame for frame in stack))

    return {"allocation_sample_count": len(allocations),
            "sampled_allocation_weight_bytes": sum(weight for weight, _ in allocations),
            "verification_sampled_weight_bytes": weighted("SSTableReader.verify"),
            "restart_decode_sampled_weight_bytes": weighted("RestartBlock.decode"),
            "entry_value_sampled_weight_bytes": weighted("RestartBlock$Entry.value"),
            "compaction_sampled_weight_bytes": weighted("PersistentAetherDatabase.executeCompaction"),
            "gc_heap_summary_count": len(heaps),
            "maximum_observed_gc_heap_used_bytes": max(heaps, default=None),
            "limitations": ["JFR allocation weights are sampled estimates, not exact bytes",
                            "GC summaries are observations, not a continuously measured peak",
                            "stack truncation can undercount caller-attributed weights"],
            "primary_h4_eligible": False, "out_of_core_verified": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("recording", type=Path)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    result = subprocess.run(["jfr", "print", "--json", "--stack-depth", "128", "--events",
                             "jdk.ObjectAllocationSample,jdk.GCHeapSummary", str(args.recording)],
                            capture_output=True, text=True, check=True, timeout=120)
    report = summarize(json.loads(result.stdout)["recording"]["events"])
    report.update(recording_sha256=digest(args.recording), recording=str(args.recording.resolve()))
    write_json(args.output, report)
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
