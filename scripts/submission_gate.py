"""Audit measurement coverage; fail closed when required evidence is absent."""
import argparse
import json
from collections import Counter
from pathlib import Path

from analyze import load_blocks, analyze_blocks
from paper_common import write_json, sha256
from fault_injection import POINTS


def validated_faults(root):
    root = Path(root)
    trials = json.loads((root / "summary.json").read_text())["trials"]
    seen = set()
    for trial in trials:
        identity = trial["trialId"]
        if identity in seen or Path(identity).name != identity or identity in {".", ".."}:
            raise ValueError("duplicate or invalid fault trial identifier")
        seen.add(identity)
        path = root / identity / "result.json"
        if sha256(path) != trial["resultSha256"]:
            raise ValueError("missing or altered individual fault result")
        saved = json.loads(path.read_text())
        if saved != {k: v for k, v in trial.items() if k != "resultSha256"}:
            raise ValueError("fault summary differs from individual trial")
        if not (trial.get("passed") is True and trial.get("boundaryReached") is True and
                trial["lostAcknowledgedWrites"] == 0 and trial["corruptArtifacts"] == 0 and
                trial.get("atomicBatchVisibility") is True):
            raise ValueError("fault recovery contract failed")
        if trial["writeMode"] == "batch" and trial.get("targetCount", 0) < 2:
            raise ValueError("batch fault probe needs multiple artifacts")
    return trials


def audit(root):
    root = Path(root)
    checks = {}
    try:
        blocks = load_blocks(root / "primary")
        groups = analyze_blocks(blocks)["groups"]
        checks["primary24PairedJavaRuns"] = bool(groups) and all(group["aetherOverRaw"]["n"] >= 24 for group in groups)
        checks["primaryExpectedEvolution"] = all(block["initialReusableEntries"] == 1003 and block["condition"]["samples"] == 1505 for block in blocks)
        checks["rawSuperiorityAfterHolm"] = all(group["aetherOverRaw"]["superiorAfterHolm"] for group in groups)
        checks["mmapEquivalenceAfterHolm"] = all(group["aetherOverMmap"]["equivalentAfterHolm"] for group in groups)
        protocol = json.loads((root / "primary/protocol.json").read_text())
        checks["confirmatoryProtocol"] = protocol.get("confirmatory") is True
    except (ValueError, KeyError, OSError) as error:
        checks["primary24PairedJavaRuns"] = False
        checks["primaryEvidenceError"] = str(error)
    for name, dimension, required, minimum in (
        ("cross-lifecycle", "dataset", {"coco", "imagenet"}, 12),
        ("cross-window", "dataset", {"oct5k", "coco", "imagenet"}, 24),
        ("workers", "workers", {0, 2, 4, 8}, 10),
        ("reuse", "reusePercent", {0, 25, 50, 66.6445, 75, 90, 100}, 10),
        ("size", "samples", {1505, 5000, 10000, 25000, 50000, 100000}, 10),
        ("cost", "preprocessPasses", {1, 2, 4, 8}, 10),
        ("gpu-scaling", "gpuCount", {1, 2}, 10),
    ):
        try:
            records = load_blocks(root / name)
            counts = Counter((b["protocolHash"], b["environmentId"], b["conditionId"]) for b in records)
            represented = {b["condition"][dimension] for b in records
                           if counts[(b["protocolHash"], b["environmentId"], b["conditionId"])] >= minimum}
            checks[name + "Coverage"] = required <= represented
            if name == "size":
                checks["realDatasetSizeScaling"] = all(b["condition"]["dataset"] in {"coco", "imagenet"} for b in records)
        except (ValueError, KeyError, OSError):
            checks[name + "Coverage"] = False
    try:
        trials = validated_faults(root / "durability")
        counts = Counter((t["faultPoint"], t["writeMode"]) for t in trials)
        checks["durability100PerPointAndMode"] = all(counts[(point, mode)] >= 100 for point in POINTS for mode in ("single", "batch"))
    except (ValueError, KeyError, OSError):
        checks["durability100PerPointAndMode"] = False
    concurrent = []
    try:
        for path in (root / "concurrency").glob("*.json"):
            value = json.loads(path.read_text())
            if "clients" in value:
                concurrent.append(value)
        identities = [(v["backend"], v["clients"], v["workerProcessesPerClient"], v["repeat"]) for v in concurrent]
        if len(set(identities)) != len(identities):
            raise ValueError("duplicate concurrency trial")
        counts = Counter(identity[:3] for identity in identities)
        checks["concurrencyFullMatrix10"] = bool(concurrent) and all(v.get("allPassed") is True and
            v.get("uniqueFinalKeysVerified", 0) > 0 and all(w.get("passed") is True for w in v["workers"])
            for v in concurrent) and all(counts[(backend, clients, workers)] >= 10
            for backend in ("aether", "mmap") for clients in (1, 2, 4) for workers in (0, 2, 4, 8))
    except (ValueError, KeyError, OSError):
        checks["concurrencyFullMatrix10"] = False
    try:
        evolution_root = root / "evolution"
        evolution = json.loads((evolution_root / "transform-evolution.json").read_text())
        trials = evolution["trials"]
        if len(trials) < 10 or len({trial["repeat"] for trial in trials}) != len(trials):
            raise ValueError("at least ten distinct evolution trials required")
        required = {"unchanged", "source-content", "normalize", "resize", "implementation-version", "artifact-codec"}
        for trial in trials:
            path = (evolution_root / trial["report"]).resolve()
            if not path.is_relative_to(evolution_root.resolve()) or sha256(path) != trial["sha256"]:
                raise ValueError("missing or changed evolution trial")
            report = json.loads(path.read_text())
            if report.get("allPassed") is not True or {s["scenario"] for s in report["scenarios"]} != required or not all(
                    s.get("passed") is True and s["misses"] == s["expectedMisses"] for s in report["scenarios"]):
                raise ValueError("incomplete or failed transformation scenarios")
        checks["transformationEvolution10"] = True
    except (ValueError, KeyError, OSError):
        checks["transformationEvolution10"] = False
    try:
        workflows = load_blocks(root / "workflow")
        counts = Counter((b["protocolHash"], b["environmentId"], b["conditionId"]) for b in workflows)
        checks["cumulativeWorkflow10"] = all(len(b["workflow"]) >= 5 for b in workflows) and all(n >= 10 for n in counts.values())
    except (ValueError, KeyError, OSError):
        checks["cumulativeWorkflow10"] = False
    # Independent reproduction needs a human reviewer; a local boolean is not evidence.
    try:
        from dali_analyze import load_dali_blocks
        dali = load_dali_blocks(root / "dali")
        counts = Counter((b["environmentId"], b["conditionId"]) for b in dali)
        represented = {b["condition"]["dataset"] for b in dali if counts[(b["environmentId"], b["conditionId"])] >= 12}
        checks["optimizedDaliComparison12"] = {"coco / DALI", "imagenet / DALI"} <= represented
    except (ValueError, KeyError, OSError):
        checks["optimizedDaliComparison12"] = False
    checks["independentCleanRoomReproduction"] = False
    scientific = all(value is True for value in checks.values())
    return {"schema": "aether-submission-gate-v1", "scientificEvidenceReady": scientific, "checks": checks,
            "submissionReady": False,
            "humanReviewRemaining": ["author names and ORCID", "current journal-specific submission limit",
                "citation verification and manuscript review", "dataset licensing", "AI disclosure reflecting actual usage",
                "independent reproduction attestation", "author approval and journal submission"],
            "note": "Failed equivalence is a scientific result; never tune or discard runs to force this planned claim."}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input", type=Path, default=Path("results"))
    parser.add_argument("--output", type=Path, default=Path("results/submission-gate.json"))
    args = parser.parse_args()
    report = audit(args.input)
    write_json(args.output, report)
    print(json.dumps(report, indent=2))
    raise SystemExit(0 if report["scientificEvidenceReady"] else 1)
