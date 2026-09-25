"""Descriptive paired full-lifecycle analysis; never confirmatory significance."""
import math
from pathlib import Path

import numpy as np
from scipy.stats import t

from longitudinal_state import BACKENDS, cumulative


def ratio_summary(ratios):
    values = np.asarray(ratios, dtype=float)
    if not len(values) or not np.all(np.isfinite(values) & (values > 0)):
        raise ValueError("invalid paired lifecycle ratios")
    logs = np.log(values)
    mean = float(logs.mean())
    radius = float(t.ppf(.975, len(logs) - 1) * logs.std(ddof=1) / np.sqrt(len(logs))) if len(logs) > 1 else None
    return {"n": len(logs), "geometricMeanRatio": math.exp(mean), "pairedRatios": values.tolist(),
            "descriptiveCI95": [math.exp(mean - radius), math.exp(mean + radius)] if radius is not None else None}


def analyze(blocks, common_ms=0.):
    for block in blocks:
        for result in block["backendResults"].values():
            if cumulative(result["stages"]) != result["cumulativeMs"]:
                raise ValueError("saved cumulative times differ from stage sums")
    comparisons = {}
    for baseline in BACKENDS[1:]:
        checkpoints, updates, shared = {}, {}, {}
        observed = []
        for version in range(5):
            key = f"V{version}"
            pairs = [(b["backendResults"][baseline]["cumulativeMs"][key],
                      b["backendResults"]["aether"]["cumulativeMs"][key]) for b in blocks]
            checkpoints[key] = ratio_summary([a / b for a, b in pairs])
            # One shared identification cost per independent lifecycle, not once per week/backend.
            shared[key] = ratio_summary([(a + common_ms) / (b + common_ms) for a, b in pairs])
            if version:
                updates[key] = ratio_summary([b["backendResults"][baseline]["stages"][version]["fullLifecycleMs"] /
                                              b["backendResults"]["aether"]["stages"][version]["fullLifecycleMs"] for b in blocks])
        for index in range(len(blocks)):
            observed.append(next((v for v in range(5) if checkpoints[f"V{v}"]["pairedRatios"][index] >= 1), None))
        comparisons[baseline] = {"cumulative": checkpoints, "perUpdate": updates,
            "includingCommonIdentification": shared, "firstObservedBreakEvenVersionByBlock": observed,
            "firstObservedGeometricMeanBreakEvenVersion": next((v for v in range(5) if checkpoints[f"V{v}"]["geometricMeanRatio"] >= 1), None)}
    return {"schema": "aether-longitudinal-analysis-v1", "measurementRole": "exploratory; no confirmatory claim",
            "completePairedBlocks": len(blocks), "primaryCheckpoint": "V4", "primaryComparison": "mmap",
            "ratioDirection": "baseline cumulative time / Aether cumulative time; >1 favors Aether",
            "commonIdentificationMsAddedOnce": common_ms, "comparisons": comparisons}


def plot(blocks, directory, role="Exploratory longitudinal pilot"):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    summary = analyze(blocks)
    x = np.arange(5)
    fig, axes = plt.subplots(1, 2, figsize=(12, 4))
    for backend in BACKENDS:
        wall = [[b["backendResults"][backend]["cumulativeMs"][f"V{i}"] / 1000 for i in x] for b in blocks]
        axes[0].plot(x, np.mean(wall, axis=0), marker="o", label=backend)
    for backend in BACKENDS[1:]:
        values = summary["comparisons"][backend]["cumulative"]
        means = [values[f"V{i}"]["geometricMeanRatio"] for i in x]
        axes[1].plot(x, means, marker="o", label=backend)
        intervals = [values[f"V{i}"]["descriptiveCI95"] for i in x]
        if all(v is not None for v in intervals):
            axes[1].fill_between(x, [v[0] for v in intervals], [v[1] for v in intervals], alpha=.12)
    axes[1].axhline(1, color="black", linewidth=.8)
    axes[0].set_ylabel("Cumulative lifecycle time (seconds), mean")
    axes[1].set_ylabel("Paired time ratio, geometric mean" + (" and 95% CI" if len(blocks) > 1 else ""))
    for axis in axes:
        axis.set_xticks(x, [f"V{i}" for i in x])
        axis.legend(fontsize=8)
        axis.grid(axis="y", alpha=.2)
    fig.suptitle(role + "; initial population included")
    fig.tight_layout()
    fig.savefig(directory / "cumulative.png", dpi=160)
    plt.close(fig)
    fig, axis = plt.subplots(figsize=(13, 5))
    positions = np.arange(20)
    bottom = np.zeros(20)
    for component in ("startup", "scanAdmission", "modelSetup", "training", "drain", "close"):
        values = [np.mean([b["backendResults"][backend]["stages"][stage]["timingsMs"][component]
                           for b in blocks]) / 1000 for stage in range(5) for backend in BACKENDS]
        axis.bar(positions, values, bottom=bottom, label=component)
        bottom += values
    axis.set_xticks(positions, [f"V{stage} {name}" for stage in range(5) for name in BACKENDS], rotation=65, ha="right", fontsize=8)
    axis.set_ylabel("Measured stage time (seconds), mean")
    axis.legend(ncol=3, fontsize=8)
    fig.tight_layout()
    fig.savefig(directory / "phases.png", dpi=160)
    plt.close(fig)
