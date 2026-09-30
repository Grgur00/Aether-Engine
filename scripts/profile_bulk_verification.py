"""Fresh paired Phase-1 controls versus candidate; no JFR, training, or automatic Phase 2."""
import argparse
import json
import math
import os
from pathlib import Path
import shutil
import statistics
import subprocess
import sys
import tempfile
import time

from paper_common import ROOT, sha256, write_json
from profile_population import base, workload_args
from longitudinal_manifests import verify
from longitudinal_state import check_capacity
from system_campaign import campaign, save_result
from package_artifact import DIRECTORIES, EXCLUDED, ROOT_FILES


def baseline_identity(root):
    root = Path(root).resolve()
    if root == ROOT.resolve():
        raise ValueError("baseline must be a separate frozen checkout")
    provenance = root / "artifact-provenance.json"
    files = json.loads(provenance.read_text(encoding="utf-8"))["files"]
    actual = {name for name in ROOT_FILES if (root / name).is_file()}
    for directory in DIRECTORIES:
        for path in (root / directory).rglob("*"):
            relative = path.relative_to(root)
            if not path.is_file() or any(part in EXCLUDED for part in relative.parts):
                continue
            if relative.as_posix().startswith("clients/python/") and path.suffix not in {".py", ".toml", ".md"}:
                continue
            actual.add(relative.as_posix())
    if actual != set(files):
        raise ValueError("frozen baseline source inventory changed")
    for name, expected in files.items():
        path = (root / name).resolve()
        if not path.is_relative_to(root) or not path.is_file() or sha256(path) != expected:
            raise ValueError(f"frozen baseline drift: {name}")
    # Both workers must feed and encode the same artifacts, not just use the same CSV.
    shared = ["scripts/bulk_population.py", "scripts/monai_comparison.py"]
    shared += [p.relative_to(ROOT).as_posix() for p in (ROOT / "clients/python").rglob("*.py")
               if not any(part in EXCLUDED for part in p.relative_to(ROOT).parts)]
    for name in shared:
        if files.get(name) != sha256(ROOT / name):
            raise ValueError(f"baseline preprocessing/client differs: {name}")
    return {"sourceManifestSha256": sha256(provenance), "sourceSha256": files}


def validate_report(report, arm, reference):
    if (report["samples"] != 1200 or report["preprocessCalls"] != 1200
            or report["trainingSampleRequests"] != 0 or report["model"] is not None
            or report["tensorSha256"] != reference or not report["restartValidation"]["passed"]):
        raise ValueError("population correctness or timing protocol mismatch")
    case = report["case"]
    if (case["backend"] != "aether_bulk" or case["putBatch"] != 16 or case["lookupBatch"] != 16
            or case["trace"] or case["targetSstableBytes"] != 32 * 1024 ** 2):
        raise ValueError("bulk case protocol mismatch")
    storage = report["bulkCommit"]["storage"]
    if arm == "candidate":
        expected = dict(inventoryCalls=1, tablesFullyVerified=storage["tables"],
                        bytesFullyVerified=sum(t["bytes"] for t in storage["sstableFinishes"]))
        if (storage.get("verificationPolicy") != "bulk-deferred-inventory-v2"
                or storage.get("verification") != expected):
            raise ValueError("candidate must fully verify each table exactly once")
    elif (storage["timingsNs"].get("verificationNs", 0) <= 0
          or not all(t["stagesNs"].get("verificationOpenAndRead", 0) > 0
                     for t in storage["sstableFinishes"])):
        raise ValueError("baseline is not the frozen immediate-plus-reopen implementation")
    for value in (report["timingsMs"]["population"], report["sourceLoadAndPreprocessMs"]):
        if not math.isfinite(value) or value < 0:
            raise ValueError("invalid population timing")


def summarize(rows, repetitions):
    def stats(values):
        return dict(n=len(values), median=statistics.median(values), mean=statistics.mean(values),
                    min=min(values), max=max(values), values=values)
    results = {}
    for arm in ("baseline", "candidate"):
        subset = [r for r in rows if r["arm"] == arm]
        if subset:
            results[arm] = {
                "populationMs": stats([r["timingsMs"]["population"] for r in subset]),
                "storageOverheadExcludingPreprocessingMs": stats([
                    r["timingsMs"]["population"] - r["sourceLoadAndPreprocessMs"] for r in subset]),
                "verification": [r["bulkCommit"]["storage"].get("verification") for r in subset]}
    complete = all({r["arm"] for r in rows if r["blockIndex"] == block} == {"baseline", "candidate"}
                   for block in range(repetitions)) and len(rows) == 2 * repetitions
    improvement = (results["baseline"]["populationMs"]["median"] - results["candidate"]["populationMs"]["median"]
                   if len(results) == 2 else None)
    return dict(measurementRole="exploratory Phase-1 population diagnostic", arms=results,
                complete=complete, medianImprovementMs=improvement, thresholdMs=750,
                performanceGatePassed=complete and improvement is not None and improvement >= 750,
                nextGate="Review correctness results before one follow-up JFR; Phase 2 remains gated, and longitudinal work also requires warm-read/incremental performance checks")


def run(output, baseline_root, repetitions=3, scratch_root=None):
    if repetitions not in (3, 4, 5):
        raise ValueError("requires 3-5 fresh paired repetitions")
    output, baseline_root = Path(output).resolve(), Path(baseline_root).resolve()
    baseline = baseline_identity(baseline_root)
    manifests, paths = verify(ROOT / "configs/paper/oct5k-longitudinal")
    if manifests["counts"] != [1200, 1260, 1323, 1389, 1458] or manifests["seed"] != 20260926:
        raise ValueError("frozen population membership drift")
    started = time.perf_counter()
    args = workload_args(paths[0], 1200, 256)
    sources = base.workload.load_sources(args)
    reference = base.tensor_digest(base.CanonicalTransform(args)(s) for s in sources)
    preflight_ms = (time.perf_counter() - started) * 1000
    order = [["baseline", "candidate"] if i % 2 == 0 else ["candidate", "baseline"] for i in range(repetitions)]
    protocol = dict(schema="aether-bulk-verification-v2-phase1", samples=1200, putBatch=16,
                    targetSstableBytes=32 * 1024 ** 2, training=False, jfr=False, serverTrace=False,
                    repetitions=repetitions, order=order, baseline=baseline, referenceHash=reference,
                    manifestSha256=sha256(paths[0]), improvementThresholdMs=750,
                    scope="population including finish; excludes startup, close and restart validation",
                    overhead="population minus measured source load/preprocessing; includes feeding, encoding and storage",
                    pageCache="uncontrolled, common reference preflight warms inputs; fresh store/process each arm")
    scratch_root = Path(scratch_root or ROOT / "build/bulk-verification-stores").resolve()
    scratch_root.mkdir(parents=True, exist_ok=True)
    rows = []
    with campaign(output, protocol) as meta:
        write_json(output / "preflight.json", {"commonPreflightMs": preflight_ms})
        for block, arms in enumerate(order):
            for arm in arms:
                if baseline_identity(baseline_root) != baseline:
                    raise ValueError("baseline provenance changed during campaign")
                check_capacity(scratch_root, 1024 ** 3)
                location = output / f"block-{block:02d}" / arm
                location.mkdir(parents=True, exist_ok=False)
                scratch = Path(tempfile.mkdtemp(prefix="bulk-verification-", dir=scratch_root))
                request = dict(case=dict(name=arm, backend="aether_bulk", lookupBatch=16, putBatch=16,
                                        trace=False, targetSstableBytes=32 * 1024 ** 2),
                               store=str(scratch / "store"), manifest=str(paths[0]), samples=1200,
                               imageSize=256, referenceHash=reference)
                write_json(location / "request.json", request)
                checkout = baseline_root if arm == "baseline" else ROOT
                print(f"Bulk verification {block + 1}/{repetitions}: {arm}", flush=True)
                try:
                    with (location / "worker.log").open("w", encoding="utf-8") as log:
                        subprocess.run([sys.executable, str(checkout / "scripts/profile_population.py"),
                                        "--request", str(location / "request.json"), "--output", str(location / "worker.json")],
                                       cwd=checkout, stdout=log, stderr=subprocess.STDOUT, check=True,
                                       env={**os.environ, "PYTHONPATH": os.pathsep.join(
                                           str(checkout / p) for p in ("scripts", "clients/python"))})
                    report = json.loads((location / "worker.json").read_text(encoding="utf-8"))
                    validate_report(report, arm, reference)
                    report.update(arm=arm, blockIndex=block)
                    save_result(location / "result.json", report, meta)
                    rows.append(report)
                    write_json(output / "summary.json", summarize(rows, repetitions))
                finally:
                    for path in scratch.glob("store.*"):
                        if path.is_file():
                            shutil.copy2(path, location / path.name)
                if scratch.parent != scratch_root or not scratch.name.startswith("bulk-verification-"):
                    raise ValueError("unsafe scratch cleanup")
                shutil.rmtree(scratch)
        write_json(output / "completion.json", dict(status="passed", role="diagnostic", runs=len(rows), recordings=0))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--baseline-root", type=Path, required=True)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--repetitions", type=int, choices=(3, 4, 5), default=3)
    opts = parser.parse_args()
    run(opts.output, opts.baseline_root, opts.repetitions, opts.scratch_root)


if __name__ == "__main__":
    main()
