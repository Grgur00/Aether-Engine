"""Five-version paired pilot; fresh processes, persistent stores, verified resume."""
import argparse
import importlib.metadata
import math
import json
import shutil
import subprocess
import sys
import tempfile
import time
from pathlib import Path

from evidence import digest
from paper_common import ROOT, sha256, write_json
from system_campaign import campaign
from longitudinal_manifests import COUNTS, verify, read_rows
from longitudinal_state import (BACKENDS, backend_order, capacity_required, check_capacity,
    execute_stage, cumulative, save_receipt, load_receipt, remove_owned)
from kaggle_results import bundle_results


def bundle_checkpoint(output, destination):
    # Windows byte-range locks cannot be read while the campaign is active.
    with tempfile.TemporaryDirectory(prefix="result-checkpoint-", dir=output.parent) as temporary:
        snapshot = Path(temporary) / "results"
        shutil.copytree(output, snapshot, ignore=shutil.ignore_patterns(".campaign.lock"))
        bundle_results(snapshot, destination)


def validate_config(config, fixture=False, smoke=False):
    if config.get("initialPopulation") == "bulk-streaming-v1" and (
            config.get("serviceLifecycle") != "persistent-per-block" or config.get("trainV0") is not True
            or config.get("targetSstableBytes") != 33554432
            or config.get("storageCommit") != "00760e5f31fa31a17e69522539a3b60318ca9bf0"):
        raise ValueError("frozen H2 storage/training configuration drift")
    if config.get("serviceLifecycle", "restart-per-version") not in {"restart-per-version", "persistent-per-block"}:
        raise ValueError("unknown service lifecycle")
    if (len(config["versions"]) != 5 or config["versions"] != sorted(set(config["versions"]))
            or config["versions"][0] < 1 or config["prefetchDepth"] != 0 or config["serverTrace"]
            or config["confirmatory"] or config["batchSize"] != 16):
        raise ValueError("invalid longitudinal protocol")
    if not fixture and (config["versions"] != COUNTS or config["imageSize"] != 256
                        or config["seed"] != 20260926 or config["sourceSamples"] != 1505):
        raise ValueError("longitudinal OCT5K dataset configuration drift")
    if smoke:
        if config["pairedBlocks"] != 1 or config["epochs"] != 1:
            raise ValueError("smoke requires one block and one epoch/update")
    elif not fixture and (config["pairedBlocks"] != 5 or config["epochs"] != 20):
        raise ValueError("pilot requires five fresh paired blocks and 20 epochs/update")


def preflight(config):
    import monai_comparison as base
    started = time.perf_counter()
    receipt, paths = verify(config["manifestDirectory"])
    if (receipt["counts"] != config["versions"] or receipt["seed"] != config["seed"]
            or receipt["sourceSamples"] != config["sourceSamples"]
            or sha256(config["sourceManifest"]) != receipt["sourceManifestSha256"]):
        raise ValueError("frozen membership/source pool mismatch")
    args = base.workload.parse_args(["--dataset-kind", "oct5k", "--dataset-manifest", config["sourceManifest"],
        "--samples", str(config["sourceSamples"]), "--resize", str(config["imageSize"]), "--preprocess-passes", "4"])
    pool = base.workload.load_sources(args)
    lookup = {row["source_identity"]: row for row in pool}
    rows = read_rows(paths[-1])[1]
    ordered = []
    for row in rows:
        value = lookup[row["source_identity"]]
        for field in ("sample_id", "image_sha256", "mask_sha256"):
            if row[field] != value[field]:
                raise ValueError("retained source bytes or sample identity changed")
        for field in ("image_path", "mask_path"):
            if Path(row[field]).resolve() != value[field].resolve():
                raise ValueError("frozen manifest references different source paths")
        ordered.append(value)
    hash_ms = (time.perf_counter() - started) * 1000
    transform = base.CanonicalTransform(args)
    tensors = [transform(item) for item in ordered]
    references = [base.tensor_digest(tensors[:count]) for count in config["versions"]]
    return paths, {"inputHashPreflightMs": hash_ms, "totalPreflightMs": (time.perf_counter() - started) * 1000,
                   "referenceHashes": references, "transformIdentity": transform.identity,
                   "sourceSamplesVerified": len(pool), "canonicalSamplesPreprocessed": len(tensors),
                   "manifests": receipt}


def validate_stage(report, config, stage):
    count = config["versions"][stage]
    previous = config["versions"][stage - 1] if stage else 0
    if (report["preprocessCalls"] != count - previous or report["trainingCacheMisses"] != 0
            or report["uniqueArtifacts"] != count or report["reusedSamples"] != previous
            or report["trainingSampleRequests"] != (count * config["epochs"] if stage or config.get("trainV0") else 0)
            or len(report["epochs"]) != (config["epochs"] if stage or config.get("trainV0") else 0)):
        raise ValueError("longitudinal cardinality/request accounting mismatch")
    if any(epoch["sampleRequests"] != count for epoch in report["epochs"]):
        raise ValueError("training dropped or duplicated samples")
    if any(not math.isfinite(loss) for epoch in report["epochs"] for loss in epoch["losses"]):
        raise ValueError("training produced nonfinite losses")
    cumulative([report])


def run_block(index, output, scratch, meta, config, paths, reference, worker=None):
    if config.get("serviceLifecycle") != "persistent-per-block":
        return _run_block(index, output, scratch, meta, config, paths, reference, worker)
    from persistent_service import PersistentService
    reports = output / "blocks" / f"{index:02d}"
    identity = {"protocolHash": meta["protocolHash"], "sourceHash": digest(meta["sourceSha256"]),
                "sourceManifestSha256": meta["protocol"]["sourceManifestSha256"],
                "environmentId": meta["environmentId"], "blockIndex": index}
    kwargs = {}
    if config.get("initialPopulation") == "bulk-streaming-v1":
        from h2_bootstrap import h2_daemon
        kwargs["factory"] = h2_daemon
    with PersistentService(scratch, reports, identity, completed=(reports / "paired.json").exists(), **kwargs) as service:
        return _run_block(index, output, scratch, meta, config, paths, reference, worker, service)


def _run_block(index, output, scratch, meta, config, paths, reference, worker=None, service=None):
    reports = output / "blocks" / f"{index:02d}"
    reports.mkdir(parents=True, exist_ok=True)
    identity = {"protocolHash": meta["protocolHash"], "sourceHash": digest(meta["sourceSha256"]),
                "sourceManifestSha256": meta["protocol"]["sourceManifestSha256"],
                "environmentId": meta["environmentId"], "blockIndex": index}
    complete = reports / "paired.json"
    if complete.exists():
        block = load_receipt(complete, identity)
        for stage in range(5):
            hashes = {}
            for backend in BACKENDS:
                record = load_receipt(reports / f"v{stage}-{backend}.json", {**identity, "version": stage, "backend": backend})
                validate_stage(record, config, stage)
                if record != block["backendResults"][backend]["stages"][stage]:
                    raise ValueError("paired receipt and stage evidence differ")
                hashes[backend] = digest(record)
            weekly = load_receipt(reports / f"v{stage}-paired.json", {**identity, "version": stage})
            if weekly["stageHashes"] != hashes:
                raise ValueError("weekly paired receipt differs from stage evidence")
        if service is not None:
            from persistent_service import validate_service_stages
            validate_service_stages(block["backendResults"]["aether"]["stages"])
        return block
    def subprocess_worker(backend, stage, live):
        request_path = reports / f"v{stage}-{backend}.request.json"
        result_path = reports / f"v{stage}-{backend}.worker.json"
        request = dict(config=config, version=stage, backend=backend, store=str(live),
            manifest=str(paths[stage]), modelSeed=config["seed"] + index + stage,
            referenceHash=reference["referenceHashes"][stage], cpuFixture=meta["protocol"]["cpuFixture"],
            lease=str(reports / f"v{stage}-{backend}.lease.json"))
        if service is not None and backend == "aether":
            request["service"] = service.daemon
        write_json(request_path, request)
        start = time.perf_counter()
        with (reports / f"v{stage}-{backend}.log").open("a", encoding="utf-8") as log:
            try:
                subprocess.run([sys.executable, str(ROOT / "scripts/longitudinal_worker.py"),
                                "--request", str(request_path), "--output", str(result_path)],
                               cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
            finally:
                for diagnostic in live.parent.glob("live.*"):
                    if diagnostic.is_file():
                        shutil.copy2(diagnostic, reports / f"v{stage}-{backend}-{diagnostic.name}")
        value = json.loads(result_path.read_text())
        value["workerProcessWallMs"] = (time.perf_counter() - start) * 1000
        return value
    stages = {name: [] for name in BACKENDS}
    orders = {}
    for stage in range(5):
        order = backend_order(index, stage, config["seed"])
        orders[f"V{stage}"] = order
        for backend in order:
            print(f"Block {index + 1}/{config['pairedBlocks']} V{stage}: {backend}", flush=True)
            value = (service.run_stage(backend, stage, worker or subprocess_worker) if service is not None else
                     execute_stage(scratch, reports, backend, stage, identity, worker or subprocess_worker))
            validate_stage(value, config, stage)
            if value["tensorSha256"] != reference["referenceHashes"][stage]:
                raise ValueError("reference checksum differs")
            stages[backend].append(value)
        for key in ("tensorSha256", "modelSha256", "initialModelSha256", "sampleOrderSha256", "modelSeed"):
            if len({stages[name][-1][key] for name in BACKENDS}) != 1:
                raise ValueError(f"paired V{stage} {key} differs")
        if any(stages[name][-1]["transformIdentity"] != reference["transformIdentity"] for name in BACKENDS):
            raise ValueError("transform identity drift")
        weekly_path = reports / f"v{stage}-paired.json"
        hashes = {name: digest(stages[name][-1]) for name in BACKENDS}
        if weekly_path.exists():
            if load_receipt(weekly_path, {**identity, "version": stage})["stageHashes"] != hashes:
                raise ValueError("weekly paired receipt differs from stage evidence")
        else:
            save_receipt(weekly_path, {"stageHashes": hashes}, {**identity, "version": stage})
    backend_results = {name: {"stages": values, "initial": values[0], "updates": values[1:],
                             "cumulativeMs": cumulative(values)} for name, values in stages.items()}
    if service is not None:
        from persistent_service import validate_service_stages
        validate_service_stages(stages["aether"])
    return save_receipt(complete, {"backendOrderByStage": orders, "backendResults": backend_results}, identity)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--cpu-fixture", action="store_true")
    opts = parser.parse_args(argv)
    config = json.loads(opts.config.read_text())
    if opts.smoke:
        config.update(pairedBlocks=1, epochs=1)
    validate_config(config, fixture=opts.cpu_fixture, smoke=opts.smoke)
    for package, expected in (("monai", "1.6.0"), ("lmdb", "2.1.1")):
        if importlib.metadata.version(package) != expected:
            raise RuntimeError(f"requires {package}=={expected}")
    config["manifestDirectory"] = str(Path(config["manifestDirectory"]).resolve())
    config["sourceManifest"] = str(Path(config["sourceManifest"]).resolve())
    paths, reference = preflight(config)
    output = opts.output.resolve()
    source_manifest = ROOT / "artifact-provenance.json"
    archive_hash = sha256(source_manifest) if source_manifest.is_file() else None
    protocol = {"schema": "aether-longitudinal-campaign-v1", "config": config,
        "measurementRole": "CPU correctness fixture" if opts.cpu_fixture else ("GPU smoke; excluded from pilot" if opts.smoke else "exploratory pilot"),
        "confirmatory": False, "cpuFixture": opts.cpu_fixture, "sourceManifestSha256": archive_hash,
        "manifestSha256": reference["manifests"]["manifestSha256"], "referenceHashes": reference["referenceHashes"],
        "transformIdentity": reference["transformIdentity"], "backends": list(BACKENDS),
        "primaryPilotEndpoint": "cumulative measured lifecycle through V4, including initial population",
        "primaryComparison": "Aether vs incremental mmap", "secondaryComparisons": list(BACKENDS[2:]),
        "order": "seeded permutation rotated by (block+stage)%4; balanced to within one position count",
        "scope": "cache/service start + complete scan/admission + fresh model setup/warmup + training + drains + close",
        "timingExclusions": "shared input preflight; Python worker import/launch; tensor/model validation; checkpoint copy/hash; instrumentation",
        "scanAdmission": "combined native cache open, missing lookup and admission; MONAI LMDB eagerly populates in constructor",
        "model": "same small model, AdamW 0.001, BCEWithLogitsLoss, 12 zero-input warmup steps; fresh each week",
        "serialization": "unchanged pilot: Aether/mmap TensorDictCodec, MONAI torch.save; same tensors",
        "durability": "unchanged: Aether DURABLE, mmap fsync, LMDB sync, PersistentDataset no matching fsync guarantee",
        "gpuWaiting": "synchronous input-wait proxy, not direct hardware idle time",
        "pageCache": "uncontrolled; process restart is not a cold OS page cache",
        "resume": "same source/protocol/host; closed-store hashed checkpoints; interrupted live stores discarded and restored",
        "checkpointOverhead": "reported separately; excluded from commercial endpoint; file-copy reads warm OS page cache"}
    if config.get("serviceLifecycle") == "persistent-per-block":
        protocol.update(schema="aether-longitudinal-persistent-campaign-v1",
            serviceLifecycle="persistent-per-block",
            serviceAccounting="one Aether start charged to V0; one shutdown charged to V4; client/native dataset open/close every job",
            primaryPilotEndpoint="persistent-service cumulative lifecycle through V4, including V0 and single service start/stop",
            resume="validated complete blocks only; an interrupted persistent block requires a fresh campaign output",
            checkpointOverhead="no live cache copies or store hashing between versions, for any backend",
            serviceResidence="reported separately, includes interleaved baseline jobs; excluded from operational phase sum",
            pageCache="uncontrolled; Aether service remains resident while baselines run; clients are fresh processes",
            restartCorrectness="separate CPU process-restart test, excluded from commercial pilot endpoint")
    if config.get("initialPopulation") == "bulk-streaming-v1":
        protocol.update(schema="aether-h2-streaming-pilot-v1", trainingVersions=[0, 1, 2, 3, 4],
            storageCommit=config["storageCommit"], initialPopulation="same-JVM frozen bulk streaming-v1",
            historicalComparability="V0 training added; do not pool with earlier pilot",
            primaryPilotEndpoint="cumulative lifecycle through V4, including V0 training and one service start/stop")
    scratch_base = (opts.scratch_root or ROOT / "build/longitudinal-stores").resolve()
    scratch_base.mkdir(parents=True, exist_ok=True)
    required = capacity_required(config["versions"][-1], config["imageSize"])
    with campaign(output, protocol, resume=opts.resume) as meta:
        workspace_file = output / "workspace.json"
        if workspace_file.exists():
            workspace = json.loads(workspace_file.read_text())
            scratch = Path(workspace["path"]).resolve()
            if scratch.parent != scratch_base or workspace["protocolHash"] != meta["protocolHash"]:
                raise ValueError("scratch ownership/protocol differs")
        else:
            capacity = check_capacity(scratch_base, required)
            scratch = Path(tempfile.mkdtemp(prefix="aether-longitudinal-", dir=scratch_base)).resolve()
            workspace = {"path": str(scratch), "protocolHash": meta["protocolHash"], "capacityPreflight": capacity}
            write_json(workspace_file, workspace)
            write_json(scratch / "owner.json", workspace)
        if json.loads((scratch / "owner.json").read_text()) != workspace:
            raise ValueError("scratch owner receipt changed")
        if not (output / "preflight.json").exists():
            generation = Path(config["manifestDirectory"]) / "generation-timing.json"
            write_json(output / "preflight.json", {**reference,
                "offlineManifestGeneration": json.loads(generation.read_text()) if generation.exists() else None})
        else:
            write_json(output / f"resume-preflight-{time.time_ns()}.json", reference)
        first_preflight = json.loads((output / "preflight.json").read_text())
        common_ms = first_preflight["totalPreflightMs"] + (first_preflight.get("offlineManifestGeneration") or {}).get("manifestGenerationMs", 0)
        blocks = []
        try:
            for index in range(config["pairedBlocks"]):
                block_scratch = scratch / f"block-{index:02d}"
                complete = output / "blocks" / f"{index:02d}" / "paired.json"
                if not complete.exists():
                    check_capacity(scratch_base, required)
                blocks.append(run_block(index, output, block_scratch, meta, config, paths, reference))
                from longitudinal_analyze import analyze
                write_json(output / "summary.json", {**analyze(blocks, common_ms), "measurementRole": protocol["measurementRole"]})
                checkpoint = output.parent / f"{output.name}-through-block-{index:02d}.zip"
                if config.get("initialPopulation") == "bulk-streaming-v1" and checkpoint.exists():
                    checkpoint = checkpoint.with_name(checkpoint.stem + f"-resume-{time.time_ns()}.zip")
                bundle_checkpoint(output, checkpoint)
                remove_owned(block_scratch, scratch)
            from longitudinal_analyze import plot
            plot(blocks, output / "figures", role=protocol["measurementRole"])
            write_json(output / "completion.json", {"pairedBlocks": len(blocks), "status": "passed", "protocolHash": meta["protocolHash"]})
        except BaseException as error:
            write_json(output / f"failure-{time.time_ns()}.json", {"type": type(error).__name__, "message": str(error),
                "protocolHash": meta["protocolHash"], "status": "failed-closed; retained stores; explicit --resume required"})
            raise


if __name__ == "__main__":
    main()
