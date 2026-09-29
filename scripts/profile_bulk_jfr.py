"""Fixed 32 MiB bulk control/profile/control; no optimization or benchmark claim."""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile

from paper_common import ROOT, sha256, write_json
from profile_population import base, workload_args
from longitudinal_manifests import verify
from longitudinal_state import check_capacity
from system_campaign import campaign, save_result
from bulk_jfr_analyze import analyze

EVENTS = ",".join(("aether.BulkPopulation", "aether.BulkPhase", "jdk.ExecutionSample", "jdk.NativeMethodSample",
    "jdk.ObjectAllocationSample", "jdk.GarbageCollection", "jdk.GCPhasePause", "jdk.GCHeapSummary",
    "jdk.FileRead", "jdk.FileWrite", "jdk.FileForce", "jdk.JavaMonitorEnter", "jdk.ThreadPark",
    "jdk.SocketRead", "jdk.DataLoss"))


def run(output, manifest, samples=1200, scratch_root=None, smoke=False):
    jfr = shutil.which("jfr")
    if not jfr:
        raise RuntimeError("JDK jfr executable is required")
    output = Path(output).resolve()
    args = workload_args(manifest, samples, 256)
    sources = base.workload.load_sources(args)
    reference = base.tensor_digest(base.CanonicalTransform(args)(s) for s in sources)
    protocol = dict(schema="aether-bulk-population-jfr-v1", role="smoke diagnostic" if smoke else "diagnostic",
        samples=samples, bulkSstableMiB=32, putBatch=16, training=False, serverTrace=False,
        jfrSettings="profile", stackDepth=256, sequence=["control-before", "jfr-run", "control-after"],
        referenceHash=reference, manifestSha256=sha256(manifest),
        targetProcess="offline BulkArtifactWriter JVM; normal restart reader is not profiled",
        optimizations=False, scope="V0 only; population includes finish/quiescence; verification readback outside timer")
    scratch_root = Path(scratch_root or ROOT / "build/bulk-jfr-stores").resolve()
    scratch_root.mkdir(parents=True, exist_ok=True)
    reports = []
    with campaign(output, protocol) as meta:
        write_json(output / "protocol.json", protocol)
        for name in protocol["sequence"]:
            check_capacity(scratch_root, 1024 ** 3)
            scratch = Path(tempfile.mkdtemp(prefix="bulk-jfr-", dir=scratch_root))
            request = dict(case=dict(name=name, backend="aether_bulk", lookupBatch=16, putBatch=16,
                           trace=False, targetSstableBytes=32 * 1024 ** 2),
                           store=str(scratch / "store"), manifest=str(manifest), samples=samples,
                           imageSize=256, referenceHash=reference)
            if name == "jfr-run":
                request.update(jfrFile=str(output / "aether-bulk-32.jfr"), jfrSettings="profile")
            write_json(output / (name + ".request.json"), request)
            print(f"Bulk JFR sequence: {name}", flush=True)
            try:
                with (output / (name + ".log")).open("w", encoding="utf-8") as log:
                    subprocess.run([sys.executable, str(ROOT / "scripts/profile_population.py"),
                        "--request", str(output / (name + ".request.json")), "--output", str(output / (name + ".worker.json"))],
                        cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
                report = json.loads((output / (name + ".worker.json")).read_text())
                report["measurementRole"] = "diagnostic control" if name != "jfr-run" else "JFR diagnostic; not benchmark"
                save_result(output / (name + ".json"), report, meta)
                reports.append(report)
            finally:
                for path in scratch.glob("store.*"):
                    if path.is_file():
                        shutil.copy2(path, output / (name + "." + path.name))
            if scratch.parent != scratch_root or not scratch.name.startswith("bulk-jfr-"):
                raise ValueError("unsafe scratch cleanup")
            shutil.rmtree(scratch)
        recording = output / "aether-bulk-32.jfr"
        with (output / "jfr-events.json").open("w", encoding="utf-8") as stream:
            subprocess.run([jfr, "print", "--json", "--stack-depth", "256", "--events", EVENTS, str(recording)],
                           stdout=stream, check=True)
        with (output / "jfr-event-summary.txt").open("w", encoding="utf-8") as stream:
            subprocess.run([jfr, "summary", str(recording)], stdout=stream, check=True)
        analyze(output / "jfr-events.json", output, reports)
        write_json(output / "completion.json", dict(status="passed", role="diagnostic", runs=3, recordings=1))
    files = sorted(p for p in output.rglob("*") if p.is_file() and p.name != "checksums.sha256")
    (output / "checksums.sha256").write_text("".join(f"{sha256(p)}  {p.relative_to(output).as_posix()}\n" for p in files), encoding="utf-8")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--smoke", action="store_true")
    opts = parser.parse_args()
    _, manifests = verify(ROOT / "configs/paper/oct5k-longitudinal")
    run(opts.output, manifests[0], 65 if opts.smoke else 1200, opts.scratch_root, opts.smoke)


if __name__ == "__main__":
    main()
