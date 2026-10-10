"""Predeclared 24-block H2 inference and figures; no pilot pooling or early tests."""
import math
from pathlib import Path

import numpy as np
from scipy import stats

from longitudinal_state import BACKENDS, cumulative


def ratios(values):
    values = np.asarray(values, dtype=float)
    if len(values) != 24 or not np.all(np.isfinite(values) & (values > 0)):
        raise ValueError("H2 analysis requires 24 positive finite paired observations")
    logs = np.log(values)
    mean = float(logs.mean())
    se = float(logs.std(ddof=1) / math.sqrt(24))
    radius = float(stats.t.ppf(.975, 23) * se)
    ratio = math.exp(mean)
    return dict(n=24, geometricMeanRatio=ratio, percentTimeReduction=100 * (1 - 1 / ratio),
                twoSidedCI95=[math.exp(mean - radius), math.exp(mean + radius)],
                pairedRatios=values.tolist(), pairedLogRatios=logs.tolist(),
                wins=int(np.sum(values > 1)), ties=int(np.sum(values == 1)),
                meanLogRatio=mean, standardError=se)


def analyze(blocks):
    if len(blocks) != 24 or {b["blockIndex"] for b in blocks} != set(range(24)):
        raise ValueError("H2 inference requires exactly 24 independent paired blocks")
    orders = [tuple(b["backendOrder"]) for b in blocks]
    if len(set(orders)) != 24 or any(set(o) != set(BACKENDS) or len(o) != 4 for o in orders):
        raise ValueError("H2 requires all 24 backend permutations exactly once")
    if any(b.get("measurementRole") != "confirmatory" or b.get("technicalFailure") is not False
           or b.get("correctnessPassed") is not True for b in blocks):
        raise ValueError("pilot, preflight or failed observations cannot enter H2 inference")
    for key in ("sourceHash", "frozenProtocolHash"):
        if len({b[key] for b in blocks}) != 1:
            raise ValueError("cannot pool different implementations or protocols")
    for block in blocks:
        for result in block["backendResults"].values():
            if result["cumulativeMs"] != cumulative(result["stages"]):
                raise ValueError("archived cumulative endpoint differs from stage sums")
    comparisons = {}
    for baseline in BACKENDS[1:]:
        by_version = {f"V{v}": ratios([b["backendResults"][baseline]["cumulativeMs"][f"V{v}"] /
                                       b["backendResults"]["aether"]["cumulativeMs"][f"V{v}"] for b in blocks])
                      for v in range(5)}
        comparisons[baseline] = dict(cumulative=by_version, primaryEndpoint=by_version["V4"],
            perBlockBreakEven=[dict(block=b["blockIndex"], firstVersion=next((v for v in range(5)
                if by_version[f"V{v}"]["pairedRatios"][i] >= 1), None),
                advantageLostLater=any(by_version[f"V{v}"]["pairedRatios"][i] >= 1 and
                                      any(by_version[f"V{w}"]["pairedRatios"][i] < 1 for w in range(v + 1, 5))
                                      for v in range(5))) for i, b in enumerate(blocks)],
            aggregateBreakEven=next((v for v in range(5) if by_version[f"V{v}"]["geometricMeanRatio"] > 1), None),
            aggregateAdvantageLostLater=any(by_version[f"V{v}"]["geometricMeanRatio"] > 1 and
                any(by_version[f"V{w}"]["geometricMeanRatio"] < 1 for w in range(v + 1, 5)) for v in range(5)),
            role="primary inferential comparison" if baseline == "mmap" else "descriptive secondary only")
    primary = comparisons["mmap"]["primaryEndpoint"]
    logs = np.asarray(primary["pairedLogRatios"])
    se, mean = primary["standardError"], primary["meanLogRatio"]
    # A zero-variance sample cannot estimate a t-test standard error.
    degenerate = bool(se <= np.finfo(float).eps)
    statistic = None if degenerate else mean / se
    p = None if degenerate else float(stats.t.sf(statistic, 23))
    nonzero = logs[logs != 0]
    sensitivity = dict(signTestOneSidedP=float(stats.binomtest(int(np.sum(nonzero > 0)), len(nonzero),
        .5, alternative="greater").pvalue) if len(nonzero) else 1.,
        wilcoxonOneSidedP=float(stats.wilcoxon(logs, alternative="greater", method="approx").pvalue) if len(nonzero) else 1.,
        shapiroP=float(stats.shapiro(logs).pvalue) if not degenerate else None,
        role="predeclared sensitivity only; never replaces the primary paired log-ratio t-test")
    primary.update(oneSidedP=p, tStatistic=statistic, degreesOfFreedom=23,
                   degenerateVariance=degenerate, superiority=p is not None and p < .05 and primary["geometricMeanRatio"] > 1,
                   sensitivity=sensitivity)
    conclusion = ("Aether superiority supported for the frozen tested workload" if primary["superiority"] else
                  "Aether superiority not established; report effect estimate and confidence interval")
    return dict(schema="aether-h2-analysis-v1", completePairedBlocks=24, primaryCheckpoint="V4",
        primaryMethod="paired one-sided t-test on log(mmap/Aether); alpha=0.05",
        ratioDirection="baseline/Aether; greater than one favors Aether", comparisons=comparisons,
        conclusion=conclusion, pilotIncluded=False, exclusions="technical whole-block attempts only; no performance exclusions")


def mean_ci(values):
    values = np.asarray(values, dtype=float)
    mean = values.mean(axis=0)
    radius = stats.t.ppf(.975, 23) * values.std(axis=0, ddof=1) / math.sqrt(24)
    return mean, mean - radius, mean + radius


def plot(blocks, directory):
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    summary = analyze(blocks)
    directory = Path(directory)
    directory.mkdir(parents=True, exist_ok=True)
    names = dict(aether="AetherML", mmap="Incremental mmap", monai_persistent="MONAI Persistent", monai_lmdb="MONAI LMDB")
    colors = dict(aether="#147d68", mmap="#5269b1", monai_persistent="#bb6940", monai_lmdb="#a14b83")
    x = np.arange(5)
    def finish(fig, axis, name, title):
        axis.set_title(title)
        axis.grid(axis="y", alpha=.2)
        fig.tight_layout()
        fig.savefig(directory / f"{name}.png", dpi=180)
        fig.savefig(directory / f"{name}.pdf")
        plt.close(fig)
    fig, axis = plt.subplots(figsize=(8, 5))
    for backend in BACKENDS:
        values = [[b["backendResults"][backend]["cumulativeMs"][f"V{v}"] / 1000 for v in x] for b in blocks]
        mean, low, high = mean_ci(values)
        axis.plot(x, mean, marker="o", label=names[backend], color=colors[backend])
        axis.fill_between(x, low, high, alpha=.12, color=colors[backend])
    axis.axvline(4, color="#444444", linestyle=":", linewidth=1)
    axis.set_xticks(x, [f"V{v}" for v in x])
    axis.set_ylabel("Cumulative seconds (mean, 95% CI)")
    axis.legend(fontsize=9)
    finish(fig, axis, "01-cumulative-lifecycle", "H2 lifecycle, V4 primary endpoint")
    fig, axis = plt.subplots(figsize=(8, 5))
    for backend in BACKENDS[1:]:
        rows = summary["comparisons"][backend]["cumulative"]
        axis.plot(x, [rows[f"V{v}"]["geometricMeanRatio"] for v in x], marker="o", label=names[backend], color=colors[backend])
        axis.fill_between(x, [rows[f"V{v}"]["twoSidedCI95"][0] for v in x],
                         [rows[f"V{v}"]["twoSidedCI95"][1] for v in x], alpha=.12, color=colors[backend])
    axis.axhline(1, color="#444444", linewidth=1)
    axis.set_xticks(x, [f"V{v}" for v in x])
    axis.set_ylabel("Baseline/Aether (geometric mean, 95% CI)")
    axis.legend(fontsize=9)
    finish(fig, axis, "02-relative-performance", "Paired cumulative performance")
    fig, axis = plt.subplots(figsize=(10, 4))
    pairs = summary["comparisons"]["mmap"]["primaryEndpoint"]["pairedRatios"]
    axis.scatter([b["blockIndex"] + 1 for b in blocks], pairs, color=colors["aether"])
    axis.axhline(1, color="#444444", linewidth=1)
    axis.set_xticks(range(1, 25))
    axis.set_xlabel("Paired block")
    axis.set_ylabel("V4 mmap/Aether ratio")
    finish(fig, axis, "03-paired-blocks", "All 24 observations; no performance exclusions")
    fig, axis = plt.subplots(figsize=(9, 5))
    bottom = np.zeros(4)
    categories = [("V0 preparation", lambda s: s[0]["timingsMs"]["scanAdmission"]),
        ("Update preparation", lambda s: sum(v["timingsMs"]["scanAdmission"] for v in s[1:])),
        ("Training", lambda s: sum(v["timingsMs"]["training"] for v in s)),
        ("Model setup", lambda s: sum(v["timingsMs"]["modelSetup"] for v in s)),
        ("Startup/drain/close", lambda s: sum(v["timingsMs"][k] for v in s for k in ("startup", "drain", "close")))]
    for label, measure in categories:
        values = np.array([np.mean([measure(b["backendResults"][backend]["stages"]) for b in blocks]) / 1000 for backend in BACKENDS])
        axis.bar(range(4), values, bottom=bottom, label=label)
        bottom += values
    axis.set_xticks(range(4), [names[b] for b in BACKENDS])
    axis.set_ylabel("Mean lifecycle seconds")
    axis.legend(fontsize=8)
    finish(fig, axis, "04-cost-breakdown", "Preparation, training and recorded overhead")
    fig, axis = plt.subplots(figsize=(8, 5))
    values = [[(b["backendResults"]["aether"]["cumulativeMs"][f"V{v}"] -
                b["backendResults"]["mmap"]["cumulativeMs"][f"V{v}"]) / 1000 for v in x] for b in blocks]
    mean, low, high = mean_ci(values)
    axis.plot(x, mean, marker="o", color=colors["aether"])
    axis.fill_between(x, low, high, alpha=.12, color=colors["aether"])
    axis.axhline(0, color="#444444", linewidth=1)
    axis.set_xticks(x, [f"V{v}" for v in x])
    axis.set_ylabel("Aether minus mmap seconds (mean, 95% CI)")
    finish(fig, axis, "05-break-even", "Negative values favor Aether; V0 is eligible")
