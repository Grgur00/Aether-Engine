"""Generate standalone PDF/600-dpi PNG figures and CSV/LaTeX tables from real blocks."""
import argparse
import collections
import csv
import json
import math
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
from scipy import stats

from analyze import load_blocks, analyze_blocks
from paper_common import write_json


def geometric_ci(values):
    logs = np.log(np.asarray(values, dtype=float))
    if len(logs) < 2 or not np.all(np.isfinite(logs)):
        raise ValueError("figures require at least two valid independent measurements per point")
    center = float(np.mean(logs))
    radius = float(stats.t.ppf(.975, len(logs) - 1) * stats.sem(logs))
    return math.exp(center), math.exp(center - radius), math.exp(center + radius)


def save(figure, output, name):
    figure.tight_layout()
    figure.savefig(output / f"{name}.pdf", bbox_inches="tight")
    figure.savefig(output / f"{name}.png", dpi=600, bbox_inches="tight")
    plt.close(figure)


def generate(blocks, output, *, raw_label="raw"):
    output = Path(output)
    output.mkdir(parents=True, exist_ok=True)
    groups = collections.defaultdict(list)
    for block in blocks:
        groups[(block["protocolHash"], block["environmentId"], block["conditionId"])].append(block)
    rows = []
    for (protocol, env, condition), runs in sorted(groups.items()):
        labels = [name for name in ("raw", "aether", "mmap", "ram") if name in runs[0]["throughput"]]
        summaries = [geometric_ci([run["throughput"][label] for run in runs]) for label in labels]
        centers = np.array([value[0] for value in summaries])
        errors = np.array([[value[0] - value[1] for value in summaries], [value[2] - value[0] for value in summaries]])
        fig, axis = plt.subplots(figsize=(5.2, 3.1))
        axis.bar([raw_label if label == "raw" else label for label in labels], centers, yerr=errors, capsize=4, color=["#707c87", "#2176ae", "#3c9d75", "#a6a6a6"])
        axis.set_ylabel("Effective training samples/s")
        spec = runs[0]["condition"]
        axis.set_title(f"{spec['dataset']} · n={len(runs)} paired blocks")
        save(fig, output, f"throughput-{protocol[:10]}-{env[:10]}-{condition}")
        paired = geometric_ci([run["throughput"]["aether"] / run["throughput"]["mmap"] for run in runs])
        fig, axis = plt.subplots(figsize=(5.2, 2.2))
        axis.axvspan(.97, 1.03, color="#3c9d75", alpha=.18, label="Predeclared equivalence bounds")
        axis.axvline(1, color="#555555", linewidth=.8)
        axis.errorbar([paired[0]], [0], xerr=[[paired[0] - paired[1]], [paired[2] - paired[0]]], fmt="o", capsize=4)
        axis.set_yticks([0], [spec["dataset"]])
        axis.set_xlabel("Paired Aether/mmap ratio (geometric mean, 95% CI)")
        axis.set_xlim(min(.95, paired[1] * .99), max(1.05, paired[2] * 1.01))
        axis.legend(fontsize=7)
        save(fig, output, f"ratio-{protocol[:10]}-{env[:10]}-{condition}")
        if len(runs[0].get("workflow", [])) > 1:
            experiments = list(range(1, len(runs[0]["workflow"]) + 1))
            if any(len(run.get("workflow", [])) != len(experiments) for run in runs):
                raise ValueError("incomplete cumulative workflow group")
            fig, axis = plt.subplots(figsize=(5.2, 3.2))
            for backend in labels:
                costs = [geometric_ci([run["workflow"][i - 1]["cumulativeWorkflowMs"][backend] / 1000 for run in runs]) for i in experiments]
                axis.errorbar(experiments, [v[0] for v in costs],
                    yerr=[[v[0] - v[1] for v in costs], [v[2] - v[0] for v in costs]], marker="o", capsize=3, label=backend)
            axis.set_xlabel("Successive V2 experiments over persistent stores")
            axis.set_ylabel("Cumulative workflow seconds (95% CI)")
            axis.legend()
            save(fig, output, f"workflow-{protocol[:10]}-{env[:10]}-{condition}")
        for label, value in zip(labels, summaries):
            rows.append(dict(protocol=protocol, environment=env, condition=condition, dataset=spec["dataset"],
                             backend=label, n=len(runs), geometricMean=value[0], ci95Low=value[1], ci95High=value[2]))
    with (output / "throughput.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
        writer.writeheader()
        writer.writerows(rows)
    analysis = analyze_blocks(blocks)
    write_json(output / "analysis.json", analysis)
    latex = [r"\begin{tabular}{lrrrr}", r"Condition & $n$ & A/R & A/M & TOST Holm $p$ \\", r"\hline"]
    for group in analysis["groups"]:
        a, m = group["aetherOverRaw"], group["aetherOverMmap"]
        latex.append(f"{group['conditionId']} & {a['n']} & {a['geometricMeanRatio']:.4f} & {m['geometricMeanRatio']:.4f} & {m['holmP']:.4g} " + r"\\")
    latex.append(r"\end{tabular}")
    (output / "primary-table.tex").write_text("\n".join(latex) + "\n", encoding="utf-8")
    # Facet sweeps by every other experimental dimension and environment.
    for sweep in ("reusePercent", "samples", "workers", "preprocessPasses", "gpuCount"):
        facets = collections.defaultdict(list)
        for block in blocks:
            spec = block["condition"]
            if spec.get(sweep) is None:
                continue
            fixed = {key: spec[key] for key in ("dataset", "reusePercent", "samples", "workers", "preprocessPasses", "gpuCount") if key != sweep}
            facets[(block["protocolHash"], block["environmentId"], json.dumps(fixed, sort_keys=True))].append(block)
        for facet_index, (_, _, fixed) in enumerate(sorted(facets)):
            runs = facets[sorted(facets)[facet_index]]
            points = sorted({run["condition"][sweep] for run in runs})
            if len(points) < 2:
                continue
            fig, (axis, ratio_axis) = plt.subplots(2, 1, figsize=(5.2, 5.0), sharex=True)
            for backend in ("raw", "aether", "mmap"):
                summaries = [geometric_ci([run["throughput"][backend] for run in runs if run["condition"][sweep] == x]) for x in points]
                axis.errorbar(points, [v[0] for v in summaries],
                    yerr=[[v[0]-v[1] for v in summaries], [v[2]-v[0] for v in summaries]], marker="o", capsize=3, label=raw_label if backend == "raw" else backend)
            axis.set_xlabel(sweep)
            axis.set_ylabel("Effective training samples/s")
            axis.legend()
            paired = [geometric_ci([run["throughput"]["aether"] / run["throughput"]["mmap"]
                                   for run in runs if run["condition"][sweep] == x]) for x in points]
            ratio_axis.axhspan(.97, 1.03, color="#3c9d75", alpha=.18)
            ratio_axis.axhline(1, color="#555555", linewidth=.8)
            ratio_axis.errorbar(points, [v[0] for v in paired], yerr=[[v[0] - v[1] for v in paired],
                [v[2] - v[0] for v in paired]], marker="o", capsize=3)
            ratio_axis.set_xlabel(sweep)
            ratio_axis.set_ylabel("Paired Aether/mmap (95% CI)")
            save(fig, output, f"sweep-{sweep}-{facet_index}")
    return rows


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("figures"))
    args = parser.parse_args()
    generate(load_blocks(args.input), args.output)
