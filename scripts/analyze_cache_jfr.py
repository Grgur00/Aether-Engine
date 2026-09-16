"""Summarize exported cache JFR events without treating native waits as CPU time."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path


def summarize(folder, case):
    recording = folder / case["recording"]
    if hashlib.sha256(recording.read_bytes()).hexdigest() != case["sha256"]:
        raise ValueError(f"recording checksum mismatch: {recording}")
    events = json.loads((recording.parent / "events.json").read_text(encoding="utf-8"))["recording"]["events"]
    counts, java_leaf, java_aether, native_leaf = (Counter() for _ in range(4))
    alloc_leaf, alloc_aether, alloc_classes, raw_aether = (Counter() for _ in range(4))
    durations = Counter()
    first_samples = []
    seen_threads = set()
    retained_weight = 0
    for event in events:
        kind, values = event["type"], event["values"]
        counts[kind] += 1
        frames = (values.get("stackTrace") or {}).get("frames", [])
        methods = [f'{f["method"]["type"]["name"]}.{f["method"]["name"]}' for f in frames]
        leaf = methods[0] if methods else "<none>"
        aether = next((m for m in methods if m.startswith("io/aetherdb/")), "<none>")
        if kind == "jdk.ExecutionSample":
            java_leaf[leaf] += 1
            java_aether[aether] += 1
        elif kind == "jdk.NativeMethodSample":
            native_leaf[leaf] += 1
        elif kind == "jdk.ObjectAllocationSample":
            weight = values["weight"]
            raw_aether[aether] += weight
            thread = values.get("eventThread") or {}
            thread_id = thread.get("javaThreadId", thread.get("osThreadId", "unknown"))
            if thread_id not in seen_threads:
                seen_threads.add(thread_id)
                first_samples.append({"threadId": thread_id, "threadName": thread.get("javaName"),
                                      "time": values["startTime"], "weight": weight, "leaf": leaf,
                                      "firstAetherFrame": aether})
            else:
                retained_weight += weight
                alloc_leaf[leaf] += weight
                alloc_aether[aether] += weight
                alloc_classes[values["objectClass"]["name"]] += weight
        duration = values.get("duration")
        if duration is not None:
            # jfr print uses ISO-8601 durations; these short events use seconds.
            if not (duration.startswith("PT") and duration.endswith("S")):
                raise ValueError(f"unsupported event duration: {duration}")
            durations[kind] += float(duration[2:-1])
    result = {
        "case": case["case"], "baseline": case["baseline"], "recorded": case["recorded"],
        "eventCounts": dict(counts), "javaSampleLeaf": java_leaf.most_common(12),
        "javaSampleFirstAetherFrame": java_aether.most_common(12),
        "nativeSampleLeaf": native_leaf.most_common(12),
        "postFirstAllocationWeightBytes": retained_weight,
        "excludedFirstSampleWeightBytes": sum(s["weight"] for s in first_samples),
        "firstAllocationSamples": first_samples,
        "allocationFirstAetherFrameRaw": raw_aether.most_common(12),
        "allocationLeafAfterFirstSample": alloc_leaf.most_common(12),
        "allocationFirstAetherFrameAfterFirstSample": alloc_aether.most_common(12),
        "allocationClassesAfterFirstSample": alloc_classes.most_common(12),
        "eventDurationSeconds": dict(durations),
        "interpretation": "Allocation weights are estimates. Excluding each thread's first sample is a startup sensitivity analysis, not an exact correction. First Aether frame is the nearest application frame from the leaf, not an inclusive stack total. Native samples can include blocked threads. Event durations of different kinds may overlap. JFR interval includes start/stop margins around the client workload.",
    }
    (recording.parent / "analysis-summary.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f'{case["case"]}: {counts["jdk.ExecutionSample"]} Java execution samples; recording SHA-256 valid', flush=True)


def main(folder):
    runs = json.loads((folder / "runs.json").read_text(encoding="utf-8"))
    for case in runs["cases"]:
        summarize(folder, case)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("folder", type=Path)
    main(parser.parse_args().folder)
