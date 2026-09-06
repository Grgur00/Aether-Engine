"""Export measured cache/resource tables, concurrency and process-crash figures."""
import argparse
import csv
import json
from collections import defaultdict
from pathlib import Path

from analyze import load_blocks
from figures import geometric_ci, save, plt
from paper_common import write_json
from submission_gate import validated_faults


def csv_table(path, rows):
    if not rows:
        raise ValueError(f"no measurements for {path.name}")
    with path.open("w", newline="", encoding="utf-8") as stream:
        fields = list(dict.fromkeys(key for row in rows for key in row))
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def training_tables(root, output):
    blocks = load_blocks(root)
    cache_rows, resource_rows = [], []
    for block in blocks:
        folder = root / block["conditionId"] / f"block-{block['blockIndex']:04d}"
        run = json.loads((folder / "training.json").read_text())["runs"][0]
        identity = {key: block[key] for key in ("protocolHash", "environmentId", "conditionId", "blockIndex")}
        for backend, dynamics in (("aether", block["aetherDynamics"]), ("mmap", block["mmapDynamics"])):
            cache_rows.append({**identity, "backend": backend, "initialReusableEntries": block["initialReusableEntries"],
                **{key: dynamics.get(key) for key in ("lookups", "hits", "misses", "bytesRead")},
                "publishedEntries": dynamics.get("publishedSamples") if backend == "aether" else dynamics.get("entriesAppended"),
                "bytesWritten": dynamics.get("bytesWritten") if backend == "aether" else dynamics.get("bytesAppended"),
                "writeByteScope": "logical artifact bytes" if backend == "aether" else "framed payload bytes; metadata excluded"})
        for backend, report in run["backends"].items():
            resources = report.get("resources", {})
            for process, usage in {"parent": resources.get("parent"), "java": resources.get("java"),
                                  **{"worker-" + key: value for key, value in resources.get("loaderWorkers", {}).items()}}.items():
                if not usage:
                    continue
                resource_rows.append({**identity, "backend": backend, "process": process,
                    **{key: usage.get(key) for key in ("pid", "available", "cpuSeconds", "minorFaults", "majorFaults",
                        "diskReadBytes", "diskWriteBytes", "rssBytesAtEnd", "lifetimePeakRssBytes")},
                    "measurementScope": resources.get("workerScope") if process.startswith("worker-") else usage.get("scope")})
    csv_table(output / "cache-dynamics.csv", cache_rows)
    if resource_rows:
        csv_table(output / "process-resources.csv", resource_rows)
    environments = {block["environmentId"] for block in blocks}
    write_json(output / "hardware-software.json", {env: json.loads((root / f"environment-{env}.json").read_text()) for env in environments})


def durability_figures(root, output):
    trials = validated_faults(root)
    groups = defaultdict(list)
    for trial in trials:
        groups[(trial["faultPoint"], trial["writeMode"])].append(trial)
    rows = []
    for (point, mode), group in sorted(groups.items()):
        center, low, high = geometric_ci([trial["recoveryMs"] for trial in group])
        rows.append(dict(faultPoint=point, writeMode=mode, n=len(group), recoveryGeometricMeanMs=center,
                         recoveryCI95Low=low, recoveryCI95High=high,
                         corruptArtifacts=sum(t["corruptArtifacts"] for t in group),
                         lostAcknowledgedWrites=sum(t["lostAcknowledgedWrites"] for t in group),
                         maximumOrphanBytes=max(t["orphanBytes"] for t in group)))
    csv_table(output / "durability.csv", rows)
    fig, axis = plt.subplots(figsize=(7, 4))
    for i, row in enumerate(rows):
        mean = row["recoveryGeometricMeanMs"]
        axis.errorbar([mean], [i], xerr=[[mean - row["recoveryCI95Low"]], [row["recoveryCI95High"] - mean]], fmt="o", capsize=3)
    axis.set_yticks(range(len(rows)), [r["faultPoint"] + " / " + r["writeMode"] for r in rows], fontsize=7)
    axis.set_xlabel("Process-crash recovery ms (geometric mean, 95% CI)")
    save(fig, output, "durability-recovery")


def concurrency_figures(root, output):
    groups, seen = defaultdict(list), set()
    for path in sorted(root.glob("*.json")):
        report = json.loads(path.read_text())
        if "clients" not in report:
            continue
        identity = (report["backend"], report["clients"], report["workerProcessesPerClient"], report["repeat"])
        if identity in seen or report.get("allPassed") is not True or not all(w.get("passed") is True for w in report["workers"]):
            raise ValueError("duplicate or failed concurrency measurement")
        seen.add(identity)
        groups[identity[:3]].append(report["aggregateSamplesPerSecond"])
    rows = []
    for (backend, clients, workers), values in sorted(groups.items()):
        center, low, high = geometric_ci(values)
        rows.append(dict(backend=backend, clients=clients, workers=workers, n=len(values), geometricMean=center, ci95Low=low, ci95High=high))
    csv_table(output / "concurrency.csv", rows)
    for workers in sorted({row["workers"] for row in rows}):
        fig, axis = plt.subplots(figsize=(5.2, 3.2))
        for backend in sorted({row["backend"] for row in rows}):
            selected = sorted((r for r in rows if r["workers"] == workers and r["backend"] == backend), key=lambda r: r["clients"])
            axis.errorbar([r["clients"] for r in selected], [r["geometricMean"] for r in selected],
                yerr=[[r["geometricMean"] - r["ci95Low"] for r in selected], [r["ci95High"] - r["geometricMean"] for r in selected]],
                marker="o", capsize=3, label=backend)
        axis.set_xlabel("Concurrent clients")
        axis.set_ylabel("Aggregate storage samples/s (95% CI)")
        axis.set_title(f"Synthetic storage workload; {workers} workers/client")
        axis.legend()
        save(fig, output, f"concurrency-w{workers}")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("kind", choices=["training", "durability", "concurrency"])
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    {"training": training_tables, "durability": durability_figures, "concurrency": concurrency_figures}[args.kind](args.input, args.output)
