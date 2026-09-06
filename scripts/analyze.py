"""Predeclared paired log-ratio analysis. Input is validated matrix block JSON."""
import argparse
import collections
import math
from pathlib import Path

import numpy as np
from scipy import stats

from paper_common import write_json, sha256


def holm(pvalues):
    order = sorted(range(len(pvalues)), key=pvalues.__getitem__)
    adjusted = [0.0] * len(pvalues)
    previous = 0.0
    for rank, index in enumerate(order):
        previous = max(previous, min(1.0, (len(order) - rank) * pvalues[index]))
        adjusted[index] = previous
    return adjusted


def paired_analysis(aether, baseline, *, margin=.03, alpha=.05, resamples=10000, seed=20260904):
    aether, baseline = np.asarray(aether, dtype=float), np.asarray(baseline, dtype=float)
    if aether.shape != baseline.shape or aether.ndim != 1 or len(aether) < 2:
        raise ValueError("at least two complete paired blocks are required")
    if not np.all(np.isfinite(aether)) or not np.all(np.isfinite(baseline)) or np.any(aether <= 0) or np.any(baseline <= 0):
        raise ValueError("throughput must be positive and finite")
    if not 0 < margin < 1 or not 0 < alpha < .5 or resamples < 1:
        raise ValueError("invalid margin, alpha, or resample count")
    values = np.log(aether) - np.log(baseline)
    n, center = len(values), float(np.mean(values))
    sd = float(np.std(values, ddof=1))
    se = sd / math.sqrt(n)
    lower, upper = math.log1p(-margin), math.log1p(margin)
    if se == 0:
        # Degenerate samples give no estimate of between-run uncertainty.
        raise ValueError("zero paired variance; collect independent timing observations")
    ci95 = stats.t.ppf(.975, n - 1) * se
    ci_tost = stats.t.ppf(1 - alpha, n - 1) * se
    p_two = float(2 * stats.t.sf(abs(center / se), n - 1))
    p_low = float(stats.t.sf((center - lower) / se, n - 1))
    p_high = float(stats.t.cdf((center - upper) / se, n - 1))
    rng = np.random.default_rng(seed)
    bootstrap = np.empty(resamples)
    for start in range(0, resamples, 1000):
        count = min(1000, resamples - start)
        bootstrap[start:start + count] = np.mean(values[rng.integers(0, n, size=(count, n))], axis=1)
    boot_ci = np.exp(np.quantile(bootstrap, [.025, .975])).tolist()
    wilcoxon = float(stats.wilcoxon(values, alternative="two-sided").pvalue)
    equivalence_n = None
    for candidate in range(3, 100001):
        candidate_se = sd / math.sqrt(candidate)
        # Conservative alpha/2 for the two-comparison Holm family; centered
        # alternative is fixed in advance, never estimated from observed effect.
        critical = stats.t.ppf(1 - alpha / 2, candidate - 1)
        approximate_power = stats.norm.cdf(upper / candidate_se - critical) - stats.norm.cdf(lower / candidate_se + critical)
        if approximate_power >= .8:
            equivalence_n = candidate
            break
    return {"n": n, "geometricMeanRatio": math.exp(center), "pairedLogSd": sd,
            "ratioCI95": [math.exp(center - ci95), math.exp(center + ci95)],
            "pairedTTestTwoSidedP": p_two,
            "tost": {"lowerRatio": 1 - margin, "upperRatio": 1 + margin,
                     "pLower": p_low, "pUpper": p_high, "p": max(p_low, p_high),
                     "equivalentUnadjusted": max(p_low, p_high) < alpha,
                     "ciLevel": 1 - 2 * alpha,
                     "ratioCI": [math.exp(center - ci_tost), math.exp(center + ci_tost)]},
            "bootstrap": {"resamples": resamples, "seed": seed, "pairedRatioCI95": boot_ci},
            "wilcoxonSensitivityP": wilcoxon,
            "superiorityPlanningN": max(2, math.ceil(((stats.norm.ppf(.975) + stats.norm.ppf(.8)) * sd / math.log1p(margin)) ** 2)),
            "equivalencePlanningN": equivalence_n,
            "planningNote": "Separate-pilot planning only. TOST approximation assumes true log ratio zero, observed SD fixed, t critical value and conservative alpha/2; not guaranteed power."}


def mixed_effects(blocks):
    import pandas as pd
    import statsmodels.formula.api as smf
    rows = []
    for block in blocks:
        condition = block["condition"]
        reuse = block["initialReusableEntries"] / condition["samples"]
        group = ":".join(map(str, (block["environmentId"], block["protocolHash"], block["conditionId"], block["blockIndex"])))
        for backend, throughput in block["throughput"].items():
            rows.append(dict(logThroughput=math.log(throughput), backend=backend,
                dataset=condition["dataset"], reuse=reuse, workers=condition["workers"], RunBlock=group))
    frame = pd.DataFrame(rows)
    formula = "logThroughput ~ C(backend) * C(dataset) * reuse + workers"
    fit = smf.mixedlm(formula, frame, groups=frame["RunBlock"]).fit()
    if not fit.converged:
        raise ValueError("secondary mixed-effects model failed to converge")
    return {"formula": formula, "scope": "secondary exploratory analysis", "converged": bool(fit.converged),
            "coefficients": fit.params.to_dict(), "pvalues": fit.pvalues.to_dict(),
            "confidence95": fit.conf_int().to_dict(), "summary": fit.summary().as_text()}


def load_blocks(directory):
    from evidence import validate_block
    blocks = [validate_block(path) for path in sorted(Path(directory).glob("*/block-*/block.json"))]
    if not blocks:
        raise ValueError("no validated block.json measurements found")
    identities = [(b["protocolHash"], b["conditionId"], b["blockIndex"]) for b in blocks]
    if len(set(identities)) != len(identities):
        raise ValueError("duplicate paired block evidence")
    return blocks


def analyze_blocks(blocks, **options):
    groups = collections.defaultdict(list)
    seen = set()
    for block in blocks:
        identity = (block["protocolHash"], block["conditionId"], block["blockIndex"])
        if identity in seen:
            raise ValueError(f"duplicate paired block {identity}")
        seen.add(identity)
        groups[(block["protocolHash"], block["conditionId"], block["environmentId"])].append(block)
    reports = []
    for (protocol, condition, environment), group in sorted(groups.items()):
        aether = [b["throughput"]["aether"] for b in group]
        raw = paired_analysis(aether, [b["throughput"]["raw"] for b in group], **options)
        mmap = paired_analysis(aether, [b["throughput"]["mmap"] for b in group], **options)
        adjusted = holm([raw["pairedTTestTwoSidedP"], mmap["tost"]["p"]])
        raw["holmP"] = adjusted[0]
        raw["superiorAfterHolm"] = adjusted[0] < options.get("alpha", .05) and raw["geometricMeanRatio"] > 1
        mmap["holmP"] = adjusted[1]
        mmap["equivalentAfterHolm"] = adjusted[1] < options.get("alpha", .05)
        reports.append({"protocolHash": protocol, "conditionId": condition, "environmentId": environment,
                        "aetherOverRaw": raw, "aetherOverMmap": mmap,
                        "confirmatorySampleCountReached": len(group) >= 24})
    return {"schema": "aether-paper-analysis-v1", "groups": reports,
            "family": "two primary comparisons per predeclared condition; secondary conditions exploratory"}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("results/processed"))
    parser.add_argument("--alpha", type=float, default=.05)
    parser.add_argument("--bootstrap-resamples", type=int, default=10000)
    parser.add_argument("--equivalence-margin", type=float, default=.03)
    parser.add_argument("--holm", action="store_true", help="Always applied to the two primary comparisons")
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--mixed-effects", action="store_true", help="Fit predeclared secondary model on a sufficiently varied matrix")
    parser.add_argument("--pilot", action="store_true", help="Label a separate >=10-block pilot; never confirmatory evidence")
    args = parser.parse_args(argv)
    blocks = load_blocks(args.input)
    report = analyze_blocks(blocks, alpha=args.alpha, margin=args.equivalence_margin,
                            resamples=args.bootstrap_resamples, seed=args.seed)
    if args.pilot:
        if any(group["aetherOverMmap"]["n"] < 10 for group in report["groups"]):
            raise ValueError("pilot recalculation requires at least 10 complete paired blocks per condition")
        report["measurementRole"] = "separate pilot; freeze revised confirmatory n before new measurement"
    if args.mixed_effects:
        report["mixedEffects"] = mixed_effects(blocks)
    write_json(args.output / "analysis.json", report)
    print(f"Analyzed {len(report['groups'])} protocol/environment/condition groups")


if __name__ == "__main__":
    main()
