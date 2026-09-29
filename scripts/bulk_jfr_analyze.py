"""Conservative JFR evidence summary; sampled allocations and methods are not exact CPU accounting."""
from collections import Counter
from datetime import datetime
import json
from pathlib import Path
import re


def seconds(value):
    if isinstance(value, (int, float)):
        return float(value)
    match = re.fullmatch(r"PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?", value or "PT0S")
    if not match:
        raise ValueError(f"unsupported JFR duration: {value}")
    return sum(float(v or 0) * scale for v, scale in zip(match.groups(), (3600, 60, 1)))


def instant(value):
    return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()


def analyze(events_file, output, reports):
    events = json.loads(Path(events_file).read_text(encoding="utf-8"))["recording"]["events"]
    populations = [e["values"] for e in events if e["type"] == "aether.BulkPopulation"]
    if len(populations) != 1 or not populations[0]["success"]:
        raise ValueError("exactly one successful bulk population interval required")
    population = populations[0]
    begin = instant(population["startTime"])
    end = begin + seconds(population["duration"])
    if population["artifactCount"] != reports[1]["samples"]:
        raise ValueError("JFR artifact count differs from correctness receipt")
    methods, stacks, allocations, phases, phase_samples = (Counter() for _ in range(5))
    work, counts = Counter(), Counter()
    byte_io = Counter()
    pauses, heap = [], []
    markers = [e["values"] for e in events if e["type"] == "aether.BulkPhase"]
    for event in events:
        kind, value = event["type"], event["values"]
        when = instant(value["startTime"])
        duration = seconds(value.get("duration", "PT0S"))
        if when > end or when + duration < begin:
            continue
        counts[kind] += 1
        overlap = max(0, min(when + duration, end) - max(when, begin))
        work[kind] += overlap
        if kind == "aether.BulkPhase":
            phases[value["phase"]] += overlap
        if kind in {"jdk.ExecutionSample", "jdk.NativeMethodSample"}:
            frames = (value.get("stackTrace") or {}).get("frames", [])
            names = [f["method"]["type"]["name"].replace("/", ".") + "." + f["method"]["name"] for f in frames]
            if names:
                methods[names[0]] += 1
                stacks[" <- ".join(names[:16])] += 1
            # Innermost marker on the sampled thread; inclusive phase counters remain separate.
            thread = (value.get("sampledThread") or {}).get("javaThreadId")
            active = [m for m in markers if (m.get("eventThread") or {}).get("javaThreadId") == thread
                      and instant(m["startTime"]) <= when <= instant(m["startTime"]) + seconds(m["duration"])]
            if active:
                phase_samples[min(active, key=lambda m: seconds(m["duration"]))["phase"]] += 1
        if kind == "jdk.ObjectAllocationSample":
            allocations[value["objectClass"]["name"]] += value.get("weight", 0)
        if kind == "jdk.GCPhasePause":
            pauses.append(overlap)
        if kind == "jdk.GCHeapSummary":
            heap.append(value["heapUsed"])
        if kind in {"jdk.FileRead", "jdk.FileWrite"}:
            byte_io[kind] += value.get("bytesRead", value.get("bytesWritten", 0))
    reference = (reports[0]["timingsMs"]["population"] + reports[2]["timingsMs"]["population"]) / 2
    ratio = reports[1]["timingsMs"]["population"] / reference
    samples = sum(methods.values())
    lines = ["# Bulk JFR Diagnostic", "", "Diagnostic only: not a benchmark or confirmatory result.", "",
        f"Population event interval: {end - begin:.3f} s (writer ready through durable finish; includes client/preprocessing waits).",
        f"Controls mean population: {reference / 1000:.3f} s; JFR/control ratio: {ratio:.4f}.",
        "The fixed A/B/C order is descriptive and does not remove machine drift.", "", "## Method Samples"]
    lines += [f"- `{name}`: {n} samples ({100*n/max(samples, 1):.1f}% of samples with stacks)" for name, n in methods.most_common(10)]
    lines += ["", "## Allocation Samples",
              "Weights estimate allocation volume; not an exact allocation total or proof of copied bytes."]
    lines += [f"- `{name}`: {n:,} weighted bytes" for name, n in allocations.most_common(10)]
    estimated = sum(allocations.values())
    lines += [f"Estimated allocation/payload ratio: {estimated / max(population['payloadBytes'], 1):.2f}.",
              "", "## GC and Blocking",
              f"Collections overlapping interval: {counts['jdk.GarbageCollection']}; recorded top-level pause sum: {sum(pauses)*1000:.1f} ms; longest: {max(pauses, default=0)*1000:.1f} ms.",
              f"Observed GC heap high-water: {max(heap, default=0):,} bytes (GC observations, not continuous peak)."]
    for kind in ("jdk.FileRead", "jdk.FileWrite", "jdk.FileForce", "jdk.JavaMonitorEnter", "jdk.ThreadPark", "jdk.SocketRead"):
        lines.append(f"- {kind}: {counts[kind]} events, {work[kind]*1000:.1f} ms overlapping duration; recorded bytes {byte_io[kind]:,}.")
    lines += ["Stock profile thresholds omit short events. Missing events do not establish zero cost.",
              "The offline transport is a stdin pipe, not a socket; client preprocessing waits can appear as input reads.",
              "", "## Bulk Phases"]
    lines += [f"- {phase}: {value*1000:.1f} ms elapsed work; {phase_samples[phase]} associated method samples." for phase, value in phases.most_common()]
    lines += ["SSTABLE_BUILD includes nested force/builder verification. INTEGRITY includes ownership/envelope/sorted admission; these are not SHA-only timings.",
              "SORT_OR_PARTITION marks sorted-map traversal into a builder; initial map insertion belongs to admission.",
              "Do not sum nested phases or interpret elapsed durations as CPU time or disk saturation.",
              "", "## Next Decision",
              "Inspect the top sampled stacks alongside existing counters before selecting ONE optimization.",
              "No automatic optimization is selected: method samples do not prove disk saturation, copy volume, or recoverable wall time.",
              "Use Mission Control on the population interval for thread/lock correlation and exact call-tree inspection."]
    details = dict(population=population, profilerRatio=ratio, methodSamples=methods, topStacks=stacks.most_common(30),
                   weightedAllocationBytes=allocations, phaseSeconds=phases, phaseSamples=phase_samples,
                   eventCounts=counts, eventOverlapSeconds=work,
                   dataLoss=counts["jdk.DataLoss"], interpretation="sampled estimates; inclusive/overlapping work, not additive CPU accounting")
    Path(output, "jfr-analysis.json").write_text(json.dumps(details, indent=2), encoding="utf-8")
    Path(output, "jfr-summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")
    return details
