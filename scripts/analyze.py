"""Predeclared paired log-ratio analysis. Input is validated matrix block JSON."""
import argparse
import collections
import csv
import json
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
            "pairedTTestGreaterP": float(stats.t.sf(center / se, n - 1)),
            "ratioLowerOneSided95": math.exp(center - stats.t.ppf(.95, n - 1) * se),
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


def analyze_blocks(blocks, *, confirmatory=False, confirmatory_design=None, **options):
    from confirmatory import DESIGN, known_design
    superiority = False
    if confirmatory:
        superiority = known_design(DESIGN if confirmatory_design is None else confirmatory_design) == DESIGN
    if confirmatory and (options.get("alpha", .05) != .05 or options.get("margin", .03) != .03):
        raise ValueError("confirmatory alpha and equivalence margin are frozen at .05 and .03")
    groups = collections.defaultdict(list)
    seen = set()
    for block in blocks:
        identity = (block["protocolHash"], block["conditionId"], block["blockIndex"])
        if identity in seen:
            raise ValueError(f"duplicate paired block {identity}")
        seen.add(identity)
        groups[(block["protocolHash"], block["conditionId"], block["environmentId"])].append(block)
    reports = []
    if confirmatory and (len(groups) != 1 or len(blocks) != 24 or
                         {b["blockIndex"] for b in blocks} != set(range(24))):
        raise ValueError("confirmatory analysis requires exactly 24 fresh paired blocks in one protocol/environment")
    for (protocol, condition, environment), group in sorted(groups.items()):
        aether = [b["throughput"]["aether"] for b in group]
        raw = paired_analysis(aether, [b["throughput"]["raw"] for b in group], **options)
        mmap = paired_analysis(aether, [b["throughput"]["mmap"] for b in group], **options)
        adjusted = holm([raw["pairedTTestTwoSidedP"], mmap["tost"]["p"]])
        raw["holmP"] = adjusted[0]
        raw["superiorAfterHolm"] = adjusted[0] < options.get("alpha", .05) and raw["geometricMeanRatio"] > 1
        mmap["holmP"] = adjusted[1]
        mmap["equivalentAfterHolm"] = adjusted[1] < options.get("alpha", .05)
        if confirmatory:
            # Secondary results never change the single predeclared primary test.
            for report in (raw, mmap):
                report.pop("holmP")
            raw.pop("superiorAfterHolm")
            mmap.pop("equivalentAfterHolm")
            raw["secondarySuperior"] = raw["pairedTTestGreaterP"] < .05
            if superiority:
                mmap["primarySuperior"] = mmap["pairedTTestGreaterP"] < .05
                mmap["secondaryEquivalent"] = mmap["tost"]["equivalentUnadjusted"]
            else:
                mmap["primaryEquivalent"] = mmap["tost"]["equivalentUnadjusted"]
        reports.append({"protocolHash": protocol, "conditionId": condition, "environmentId": environment,
                        "aetherOverRaw": raw, "aetherOverMmap": mmap,
                        "confirmatorySampleCountReached": len(group) >= 24})
    return {"schema": "aether-paper-analysis-v1", "groups": reports,
            "family": ("single primary Aether/incremental mmap one-sided superiority; equivalence and raw nominal secondary, RAM descriptive"
                       if superiority else "single primary Aether/mmap TOST; raw one-sided secondary, RAM descriptive"
                       if confirmatory else "two primary comparisons per predeclared condition; secondary conditions exploratory")}


def amortization(directory, *, identities=None):
    """Descriptive paired curves, derived only from already validated blocks."""
    from run_matrix import BACKEND_IDS
    runs = []
    for path in sorted(Path(directory).glob("*/block-*/block.json")):
        if identities is not None:
            block = json.loads(path.read_text(encoding="utf-8"))
            if (block["protocolHash"], block["conditionId"], block["environmentId"]) not in identities:
                continue
        runs.append(json.loads(path.with_name("training.json").read_text(encoding="utf-8"))["runs"][0])
    if not runs:
        raise ValueError("amortization requires completed paired blocks")
    lengths = set()
    for run in runs:
        for key in ("aether", "mmap"):
            backend = run["backends"][BACKEND_IDS[key]]
            walls = backend["epochWallMs"]
            lengths.add(len(walls))
            if not walls or any(not math.isfinite(wall) or wall <= 0 for wall in walls):
                raise ValueError("amortization requires positive finite epoch walls")
            setup = backend["lifecycle"]["populateMs"]
            if not math.isfinite(setup) or setup < 0:
                raise ValueError("amortization requires finite nonnegative preparation costs")
    if len(lengths) != 1:
        raise ValueError("amortization requires matching complete epoch counts")
    epochs = lengths.pop()
    protocol_path = Path(directory) / "protocol.json"
    if protocol_path.exists():
        protocol = json.loads(protocol_path.read_text(encoding="utf-8"))
        if protocol.get("measuredSteps", 0) or protocol["epochs"] != epochs:
            raise ValueError("amortization requires full lifecycle epochs matching the protocol")
    curves = []
    for epoch in range(1, epochs + 1):
        training, lifecycle = [], []
        for run in runs:
            a, m = (run["backends"][BACKEND_IDS[key]] for key in ("aether", "mmap"))
            a_wall, m_wall = sum(a["epochWallMs"][:epoch]), sum(m["epochWallMs"][:epoch])
            training.append(m_wall / a_wall)
            lifecycle.append((m_wall + m["lifecycle"]["populateMs"]) /
                             (a_wall + a["lifecycle"]["populateMs"]))
        curves.append({"epochs": epoch,
                       "n": len(runs), "pairedTrainingRatios": training,
                       "trainingRatioGeometricMean": float(np.exp(np.mean(np.log(training)))),
                       "includingV2PreparationRatioGeometricMean": float(np.exp(np.mean(np.log(lifecycle))))})
        if len(training) >= 2:
            logs = np.log(training)
            radius = stats.t.ppf(.95, len(training) - 1) * stats.sem(logs)
            curves[-1]["trainingRatioCI90"] = np.exp([np.mean(logs) - radius, np.mean(logs) + radius]).tolist()
    return {"scope": "secondary descriptive; cumulative epoch walls, optionally plus V2 preparation; excludes V1 population and inter-epoch overhead; not the primary effective-throughput endpoint",
            "curves": curves,
            "checkpoints": [row for row in curves if row["epochs"] in (1, 5, 10, 20)]}


def pilot_update_costs(directory, identities):
    """First-pass input timings include reuse checks; not isolated update latency."""
    from run_matrix import BACKEND_IDS
    costs = []
    for path in sorted(Path(directory).glob("*/block-*/block.json")):
        block = json.loads(path.read_text(encoding="utf-8"))
        if (block["protocolHash"], block["conditionId"], block["environmentId"]) not in identities:
            continue
        run = json.loads(path.with_name("training.json").read_text(encoding="utf-8"))["runs"][0]
        row = {"blockIndex": block["blockIndex"], "backends": {}}
        for name in ("aether", "mmap"):
            backend = run["backends"][BACKEND_IDS[name]]
            steps = [step for step in backend["steps"] if step["epoch"] == 0]
            row["backends"][name] = {
                "firstEpochWallMs": backend["epochWallMs"][0],
                "inputPreparationMs": sum(step["batchPrepareMs"] for step in steps),
                "sourceAndPreprocessMs": sum(step["sourceLoadMs"] + step["preprocessMs"] for step in steps),
                "initialReusableEntries": (run["cacheDynamics"]["prepopulatedEntries"] if name == "aether"
                                           else run["mmapDynamics"]["initialReusableEntries"]),
            }
        costs.append(row)
    return {"scope": "first-epoch input preparation includes lookup, reads, preprocessing and publication for incremental reuse; excludes GPU training, V1 population, preflight and post-training compaction drain; not standalone total dataset-update latency; sourceAndPreprocessMs is a component, not additive to inputPreparationMs",
            "blocks": costs}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, default=Path("results/processed"))
    parser.add_argument("--alpha", type=float, default=.05)
    parser.add_argument("--bootstrap-resamples", type=int, default=10000)
    parser.add_argument("--equivalence-margin", type=float, default=.03)
    parser.add_argument("--holm", action="store_true", help="Legacy exploratory two-comparison family; rejected for the frozen confirmatory design")
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--mixed-effects", action="store_true", help="Fit predeclared secondary model on a sufficiently varied matrix")
    parser.add_argument("--pilot", action="store_true", help="Label a separate >=10-block pilot; never confirmatory evidence")
    args = parser.parse_args(argv)
    protocol_path = args.input / "protocol.json"
    protocol = json.loads(protocol_path.read_text(encoding="utf-8")) if protocol_path.exists() else {}
    confirmatory = protocol.get("confirmatory") is True
    if confirmatory and (args.pilot or args.holm or args.mixed_effects):
        raise ValueError("confirmatory analysis uses the frozen single-primary design, without pilot/Holm/mixed-effects overrides")
    blocks = load_blocks(args.input)
    report = analyze_blocks(blocks, confirmatory=confirmatory, confirmatory_design=protocol.get("confirmatoryDesign"),
                            alpha=args.alpha, margin=args.equivalence_margin,
                            resamples=args.bootstrap_resamples, seed=args.seed)
    if confirmatory:
        report["measurementRole"] = f"confirmatory; fixed 24 blocks and {protocol['epochs']}-epoch endpoint"
        report["confirmatoryDesign"] = protocol["confirmatoryDesign"]
        report["secondaryAmortization"] = amortization(args.input)
        report["groups"][0]["secondaryAmortization"] = report["secondaryAmortization"]
    if args.pilot:
        if any(group["aetherOverMmap"]["n"] < 10 for group in report["groups"]):
            raise ValueError("pilot recalculation requires at least 10 complete paired blocks per condition")
        report["measurementRole"] = "separate pilot; freeze revised confirmatory n before new measurement"
        report["family"] = "exploratory pilot Aether/mmap and Aether/raw comparisons; no confirmatory claim"
        if protocol.get("epochs") and not protocol.get("measuredSteps", 0):
            report["mainEndpoint"] = f"effective throughput over all {protocol['epochs']} V2 training epochs"
            for group in report["groups"]:
                identities = {(group["protocolHash"], group["conditionId"], group["environmentId"])}
                group["secondaryAmortization"] = amortization(args.input, identities=identities)
                group["secondaryUpdateCosts"] = pilot_update_costs(args.input, identities)
    if args.mixed_effects:
        report["mixedEffects"] = mixed_effects(blocks)
    write_json(args.output / "analysis.json", report)
    if any("secondaryAmortization" in group for group in report["groups"]):
        for name, key in (("amortization.csv", "curves"), ("checkpoints.csv", "checkpoints")):
            rows = []
            for group in report["groups"]:
                for point in group["secondaryAmortization"][key]:
                    rows.append({field: group[field] for field in ("protocolHash", "conditionId", "environmentId")} |
                                {field: value for field, value in point.items() if field not in {"pairedTrainingRatios", "trainingRatioCI90"}} |
                                dict(ci90Low=point["trainingRatioCI90"][0], ci90High=point["trainingRatioCI90"][1]))
            with (args.output / name).open("w", newline="", encoding="utf-8") as stream:
                writer = csv.DictWriter(stream, fieldnames=list(rows[0]))
                writer.writeheader()
                writer.writerows(rows)
    print(f"Analyzed {len(report['groups'])} protocol/environment/condition groups")


if __name__ == "__main__":
    main()
