import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import asdict
from pathlib import Path

from aetherml import AetherMLStore


FAULTS = ("temp-write", "orphan-artifact", "dangling-cache", "committed")


def main():
    parser = argparse.ArgumentParser(description="Run AetherML artifact-store crash recovery trials.")
    parser.add_argument("--trials", type=int, default=25)
    parser.add_argument("--store", default=None)
    parser.add_argument("--reset-store", action="store_true")
    parser.add_argument("--output", default="build/aetherml-crash-recovery.json")
    parser.add_argument("--_worker-fault", choices=FAULTS)
    parser.add_argument("--_worker-store")
    parser.add_argument("--_worker-sample")
    args = parser.parse_args()
    if args._worker_fault:
        run_worker(args._worker_store, args._worker_fault, args._worker_sample)
        return
    if args.trials < 1:
        raise ValueError("trials must be positive")

    temporary = None
    if args.store is None:
        temporary = tempfile.mkdtemp(prefix="aetherml-crash-")
        store_path = Path(temporary)
    else:
        store_path = Path(args.store)
        if store_path.exists() and args.reset_store:
            shutil.rmtree(store_path)
        elif store_path.exists() and any(store_path.iterdir()):
            raise ValueError("store exists and is not empty; pass --reset-store to replace it")

    try:
        report = run_campaign(args.trials, store_path)
    finally:
        if temporary is not None:
            shutil.rmtree(temporary, ignore_errors=True)

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))
    print(f"Report: {output}")


def run_worker(store_path, fault, sample):
    store = AetherMLStore(store_path, code_commit="crash-campaign")
    payload = f"sample:{sample}:fault:{fault}".encode("utf-8")
    digest = hashlib.sha256(payload).hexdigest()
    if fault == "temp-write":
        (store.tmp_dir / f"{digest}.tmp").write_bytes(payload)
    elif fault == "orphan-artifact":
        artifact = store.artifacts_dir / digest[:2] / digest[2:4] / digest
        artifact.parent.mkdir(parents=True, exist_ok=True)
        artifact.write_bytes(payload)
    elif fault == "dangling-cache":
        cache_path = store.cache_dir / f"{digest}.json"
        cache_path.write_text(json.dumps({"artifact_id": f"aether:{digest}"}) + "\n", encoding="utf-8")
    elif fault == "committed":
        cache_key = store.transformation_key(
            input_hash=digest,
            transformation_name="crash_worker",
            transformation_version="1",
        )
        store.commit_artifact(
            payload,
            cache_key=cache_key,
            transformation_name="crash_worker",
            transformation_version="1",
        )
        with (store.root / "acknowledged.jsonl").open("a", encoding="utf-8") as stream:
            stream.write(json.dumps({"cache_key": cache_key, "payload": payload.decode("utf-8")}) + "\n")
            stream.flush()
            os.fsync(stream.fileno())
    os._exit(137)


def run_campaign(trials, store_path):
    fault_counts = {fault: 0 for fault in FAULTS}
    trial_reports = []
    acknowledged = []
    for index in range(trials):
        fault = FAULTS[index % len(FAULTS)]
        fault_counts[fault] += 1
        command = [
            sys.executable,
            __file__,
            "--_worker-fault",
            fault,
            "--_worker-store",
            str(store_path),
            "--_worker-sample",
            str(index),
        ]
        completed = subprocess.run(command, capture_output=True, text=True)
        recovery_started = time.perf_counter_ns()
        store = AetherMLStore(store_path, code_commit="crash-campaign")
        before = store.recover(remove_orphan_artifacts=True)
        after = store.validate()
        recovery_nanos = time.perf_counter_ns() - recovery_started
        acknowledged = load_acknowledged(store_path)
        committed_ok = 0
        for record in acknowledged:
            value = store.load_cached(record["cache_key"])
            if value == record["payload"].encode("utf-8"):
                committed_ok += 1
        trial_reports.append({
            "trial": index,
            "fault": fault,
            "workerExitCode": completed.returncode,
            "recoveryNanos": recovery_nanos,
            "preRecovery": asdict(before),
            "postRecoveryConsistent": after.is_consistent,
            "acknowledgedArtifacts": len(acknowledged),
            "acknowledgedArtifactsLoaded": committed_ok,
        })
    successful_recoveries = sum(1 for trial in trial_reports if trial["postRecoveryConsistent"])
    acknowledged_loaded = trial_reports[-1]["acknowledgedArtifactsLoaded"] if trial_reports else 0
    lost_acknowledged = max(0, len(acknowledged) - acknowledged_loaded)
    return {
        "backend": "AETHERML_LOCAL_ARTIFACT_STORE",
        "trials": trials,
        "faultCounts": fault_counts,
        "postRecoveryConsistent": all(trial["postRecoveryConsistent"] for trial in trial_reports),
        "acknowledgedArtifacts": len(acknowledged),
        "recoveredArtifacts": acknowledged_loaded,
        "acknowledgedArtifactsLoaded": acknowledged_loaded,
        "lostAcknowledgedArtifacts": lost_acknowledged,
        "successfulRecoveries": successful_recoveries,
        "trialsDetail": trial_reports,
    }


def load_acknowledged(store_path):
    path = store_path / "acknowledged.jsonl"
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


if __name__ == "__main__":
    main()
