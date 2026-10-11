"""Predeclared H4 paired-log test policy and pilot-qualified design freezing."""
import math
from datetime import datetime, timezone

import numpy as np
from scipy import stats

from .analyze_h4 import paired_statistics
from .prepare_pcam_manifest import canonical_hash


def validate_design(design):
    body = {key: value for key, value in design.items() if key != "sha256"}
    if canonical_hash(body) != design["sha256"] or design["schema"] != "aether-h4-confirmatory-design-v1":
        raise ValueError("confirmatory design checksum/schema mismatch")
    n = design["sample_size"]
    if type(n) is not int or n < 6 or n % 2 or design["block_ids"] != list(range(n)):
        raise ValueError("even predeclared sample size of at least six complete independent paired blocks required")
    if (design["endpoint"] != "paired-block mean throughput log ratio: Aether/mmap"
            or design["condition"] != 4 or design["access_pattern"] != "shuffled"
            or design["memory_limit_bytes"] != 2 * 1024 ** 3
            or design["statistical_test"] != "one-sided paired-log Student t test"
            or design["alternative"] != "greater" or not 0 < design["alpha"] < .5):
        raise ValueError("unsupported confirmatory endpoint/test policy")
    claim, margin = design["claim"], design["null_ratio"]
    if claim not in ("superiority", "noninferiority") or not math.isfinite(margin):
        raise ValueError("invalid confirmatory claim/margin")
    if claim == "superiority" and margin != 1 or claim == "noninferiority" and not 0 < margin < 1:
        raise ValueError("superiority uses ratio 1; noninferiority requires a predeclared ratio between zero and one")
    schedule = design["ordering"]
    if set(schedule) != {str(index) for index in range(n)}:
        raise ValueError("incomplete frozen backend schedule")
    orders = [schedule[str(index)] for index in range(n)]
    if any(order not in (["aether", "mmap"], ["mmap", "aether"]) for order in orders):
        raise ValueError("invalid backend ordering")
    if sum(order[0] == "aether" for order in orders) != n // 2:
        raise ValueError("confirmatory ordering must balance backend first positions")
    if design["exclusion_policy"] != "report every assigned block; no silent replacement or post-hoc deletion":
        raise ValueError("unsupported confirmatory exclusion policy")
    return design


def make_design(loaded, *, sample_size, claim, null_ratio, alpha=.05):
    if type(sample_size) is not int or sample_size < 6 or sample_size % 2:
        raise ValueError("even predeclared sample size of at least six required")
    binding = loaded["binding"]
    pilot = [block for block in loaded["blocks"] if block["condition"] == 4]
    if (loaded["phase"] != "exploratory" or loaded["excluded"]
            or len(pilot) != 5 or {block["block_id"] for block in pilot} != set(range(5))
            or binding["protocol"]["memory_limit_bytes"] != 2 * 1024 ** 3
            or binding["manifest"]["official_samples"] != 262144):
        raise ValueError("five complete valid official-PCam 4x exploratory paired blocks are required before freezing")
    if any(set(block["results"]) != {"aether", "mmap"} or not all(record["patterns"]["shuffled"]["out_of_core_resource_evidence_verified"]
                   for record in block["results"].values()) for block in pilot):
        raise ValueError("pilot has not qualified genuine out-of-core reads for both backends in every block")
    design = dict(schema="aether-h4-confirmatory-design-v1", frozen_at=datetime.now(timezone.utc).isoformat(),
                  **loaded["identity"], sample_size=sample_size, block_ids=list(range(sample_size)),
                  ordering={str(index): ["aether", "mmap"] if index % 2 == 0 else ["mmap", "aether"] for index in range(sample_size)},
                  endpoint="paired-block mean throughput log ratio: Aether/mmap", condition=4, access_pattern="shuffled",
                  memory_limit_bytes=2 * 1024 ** 3, statistical_test="one-sided paired-log Student t test",
                  alternative="greater", claim=claim, null_ratio=null_ratio, alpha=alpha,
                  exclusion_policy="report every assigned block; no silent replacement or post-hoc deletion",
                  pilot_record_sha256=[block["block_record_sha256"] for block in sorted(pilot, key=lambda block: block["block_id"])],
                  pilot_archives=loaded["archives"], independence="fresh complete backend lifecycles; never reuse pilot blocks",
                  qualification="full correctness, clean restart, cgroup limits/membership, OOM-free reads and per-pass reclaim/physical I/O")
    design["sha256"] = canonical_hash(design)
    return validate_design(design)


def confirmatory_test(ratios, design):
    validate_design(design)
    if len(ratios) != design["sample_size"]:
        return dict(executed=False, reason="predeclared complete paired-block sample size not reached")
    summary = paired_statistics(ratios, confidence=1 - design["alpha"])
    logs = np.asarray(summary["log_ratios"])
    standard_error = float(stats.sem(logs))
    if not standard_error > 0:
        return dict(executed=False, reason="degenerate paired-log variance; t test not estimable", summary=summary)
    test = stats.ttest_1samp(logs, math.log(design["null_ratio"]), alternative="greater", nan_policy="raise")
    lower = float(logs.mean()) - float(stats.t.ppf(1 - design["alpha"], len(logs) - 1)) * standard_error
    return dict(executed=True, summary=summary, claim=design["claim"], null_ratio=design["null_ratio"],
                alpha=design["alpha"], statistic=float(test.statistic), degrees_of_freedom=int(test.df),
                pvalue=float(test.pvalue), rejected_null=bool(test.pvalue < design["alpha"]),
                one_sided_ratio_lower_bound=math.exp(lower), test=design["statistical_test"],
                design_sha256=design["sha256"])
