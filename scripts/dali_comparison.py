"""Paired DALI-direct / Java-cache / mmap training with one canonical DALI pipeline."""
import argparse
import hashlib
import json
import math
import os
import random
import tempfile
import time
from pathlib import Path
from types import SimpleNamespace

from paper_common import ROOT, environment, capture, java_daemon, sha256, write_json
from evidence import digest
from aether_training_cache.dali_workload import DaliBatches, artifact_key, descriptor, payloads, targets
from aether_training_cache.java_store import JavaArtifactStore
from aether_training_cache.persistent_mmap import PersistentMmapStore
from aether_training_cache.vision_workload import load_sources, model
from benchmark_gpu_segmentation import set_seed, unpack_payload


def batches(count, size, epochs=1):
    return [list(range(start, min(count, start + size))) for _ in range(epochs) for start in range(0, count, size)]


def model_hash(network):
    result = hashlib.sha256()
    for name, tensor in sorted(network.state_dict().items()):
        result.update(name.encode())
        result.update(tensor.detach().cpu().contiguous().numpy().tobytes())
    return result.hexdigest()


def validate_payloads(values, expected):
    for index, value in values.items():
        if hashlib.sha256(value).hexdigest() != expected[index]:
            raise ValueError(f"DALI output/cache tensor parity failed for sample {index}")


def canonical_hashes(args, sources, np):
    schedule = batches(len(sources), args.batch_size)
    pipeline = DaliBatches(args, sources, schedule)
    hashes = {}
    try:
        for indices in schedule:
            images = pipeline.batch().cpu().numpy()
            hashes.update({index: hashlib.sha256(value).hexdigest()
                           for index, value in payloads(images, sources, indices, args.num_classes, np).items()})
    finally:
        pipeline.close()
    return hashes


def open_store(backend, root, port):
    if backend == "aether":
        store = JavaArtifactStore(port=port, namespace="dali-canonical")
        info = store.engine_info()
        if info.get("engine") != "java-training-cache" or info.get("durability") != "DURABLE":
            store.close()
            raise ValueError("DALI comparison requires the actual Java DURABLE engine")
        store.measured_engine_info = info
        return store
    return PersistentMmapStore(root / "mmap", durable=True)


def publish(store, backend, values, keys):
    if backend == "aether":
        store.commit_bytes_many([{"cache_key": keys[index], "data": value} for index, value in values.items()])
    else:
        store.put_many([(keys[index], value) for index, value in values.items()])


def populate(args, sources, keys, expected, initial, backend, root, port, np):
    started = time.perf_counter()
    store = open_store(backend, root, port)
    pipeline = DaliBatches(args, sources) if initial else None
    try:
        for indices in batches(initial, args.batch_size):
            values = payloads(pipeline.batch(indices).cpu().numpy(), sources, indices, args.num_classes, np)
            validate_payloads(values, expected)
            publish(store, backend, values, keys)
    finally:
        if pipeline:
            pipeline.close()
        store.close()
    return (time.perf_counter() - started) * 1000


def train(args, sources, keys, expected, initial, backend, root, port, np, torch):
    setup = time.perf_counter()
    set_seed(torch, args.seed)
    network = model(args, torch).cuda()
    optimizer = torch.optim.AdamW(network.parameters(), lr=1e-3)
    criterion = torch.nn.CrossEntropyLoss() if args.dataset_kind == "imagenet" else torch.nn.BCEWithLogitsLoss()
    schedule = batches(len(sources), args.batch_size, args.epochs)
    pipeline = DaliBatches(args, sources, schedule if backend == "raw" else None) if backend == "raw" or initial < len(sources) else None
    store = open_store(backend, root, port) if backend != "raw" else None
    if store is not None:
        present = store.cached_artifact_ids(keys) if backend == "aether" else {key for key in keys if store.contains(key)}
        if set(present) != set(keys[:initial]):
            raise ValueError("persistent backends did not survive restart with identical initial reuse")
        if backend == "aether":
            store.reset_operation_metrics()
        else:
            store.reset_metrics()
    torch.cuda.synchronize()
    torch.cuda.reset_peak_memory_stats()
    setup_ms = (time.perf_counter() - setup) * 1000
    lookups = hits = misses = published = 0
    input_seconds, losses = 0.0, []
    started = time.perf_counter()
    try:
        for indices in schedule:
            input_started = time.perf_counter()
            if backend == "raw":
                images = pipeline.batch()
                labels = torch.from_numpy(targets(sources, indices, args.num_classes, np)).cuda()
            else:
                if backend == "aether":
                    loaded = store.load_cached_bytes_many([keys[index] for index in indices])
                    values = {index: loaded[keys[index]] for index in indices if keys[index] in loaded}
                else:
                    loaded = {index: store.get(keys[index]) for index in indices}
                    values = {index: value[4:] for index, value in loaded.items() if value is not None}
                missing = [index for index in indices if index not in values]
                lookups += len(indices)
                hits += len(values)
                misses += len(missing)
                if missing:
                    computed = payloads(pipeline.batch(missing).cpu().numpy(), sources, missing, args.num_classes, np)
                    validate_payloads(computed, expected)
                    publish(store, backend, computed, keys)
                    published += len(computed)
                    values.update(computed)
                validate_payloads(values, expected)
                decoded = [unpack_payload(values[index], np) for index in indices]
                images = torch.from_numpy(np.stack([value["image"] for value in decoded])).cuda()
                labels = torch.from_numpy(np.stack([value["mask"] for value in decoded])).cuda()
            # Synchronize only the consumer stream: DALI may prepare later batches
            # on its own streams while this batch is trained.
            torch.cuda.current_stream().synchronize()
            input_seconds += time.perf_counter() - input_started
            optimizer.zero_grad(set_to_none=True)
            loss = criterion(network(images), labels)
            loss.backward()
            optimizer.step()
            value = float(loss.item())
            if not math.isfinite(value):
                raise ValueError("non-finite DALI workload loss")
            losses.append(value)
        torch.cuda.synchronize()
        wall = time.perf_counter() - started
        metrics = store.operation_metrics() if backend == "aether" else dict(store.metrics) if store else None
        if backend != "raw" and (lookups != len(sources) * args.epochs or misses != len(sources) - initial or published != misses):
            raise ValueError("DALI cache lifecycle accounting failed")
        return {"backend": backend, "effectiveSamplesPerSecond": len(sources) * args.epochs / wall,
                "trainingWallSeconds": wall, "setupMs": setup_ms, "inputWaitPercent": 100 * input_seconds / wall,
                "modelStateSha256": model_hash(network), "lossTrajectory": losses,
                "lookups": lookups, "hits": hits, "misses": misses, "publishedEntries": published,
                "initialReusableEntries": initial if store else None, "storageMetrics": metrics,
                "engineInfo": getattr(store, "measured_engine_info", None),
                "gpuPeakAllocatedBytes": torch.cuda.max_memory_allocated(), "gpuHoursDuringTraining": wall / 3600,
                "cacheInvariantsPassed": True if store else None}
    finally:
        if pipeline:
            pipeline.close()
        if store:
            store.close()


def run_block(args, point, index, output, protocol, env_id):
    import numpy as np
    import torch
    work = SimpleNamespace(**(vars(args) | point))
    work.seed = args.seed_base + index
    sources = load_sources(work)
    parameters = protocol["pipelineParameters"][point["dataset_kind"]]
    keys = [artifact_key(source, parameters) for source in sources]
    expected = canonical_hashes(work, sources, np)
    initial = round(len(sources) * args.reuse_percent / 100)
    order = ["raw", "aether", "mmap"]
    random.Random(work.seed).shuffle(order)
    population = {}
    with tempfile.TemporaryDirectory(prefix="dali-store-", dir=output.parent) as temporary:
        root = Path(temporary)
        with java_daemon(root / "java") as daemon:
            for backend in (name for name in order if name != "raw"):
                population[backend] = populate(work, sources, keys, expected, initial, backend, root, daemon["port"], np)
        with java_daemon(root / "java") as daemon:
            measured = {backend: train(work, sources, keys, expected, initial, backend, root, daemon["port"], np, torch) for backend in order}
    if len({report["modelStateSha256"] for report in measured.values()}) != 1:
        raise ValueError("DALI-direct and cached artifacts produced different final models")
    report = {"schema": "aether-dali-block-v1", "status": "PASSED", "modelParityPassed": True,
        "measurementRole": protocol["measurementRole"],
        "protocolHash": digest(protocol), "environmentId": env_id, "condition": point, "blockIndex": index,
        "seed": work.seed, "backendOrder": order, "initialReusableEntries": initial,
        "canonicalArtifactHashes": expected, "backends": measured, "populationMs": population,
        "throughput": {name: value["effectiveSamplesPerSecond"] for name, value in measured.items()},
        "workflowCostMs": {name: value["trainingWallSeconds"] * 1000 + value["setupMs"] + population.get(name, 0) for name, value in measured.items()},
        "tensorParityScope": "every cached/generated payload matches preflight canonical bytes; final model equal for all three backends",
        "pageCache": "uncontrolled; full source hashes and canonical preflight warm files",
        "timingScope": "DALI pipeline construction/model setup and initial population separate; direct DALI prefetch enabled; cache misses use the same operators on demand"}
    write_json(output, report)
    write_json(output.with_suffix(".receipt.json"), {"sha256": sha256(output), "protocolHash": digest(protocol)})
    return report


def validated_report(path, protocol, env_id):
    report = json.loads(path.read_text())
    receipt = json.loads(path.with_suffix(".receipt.json").read_text())
    if sha256(path) != receipt["sha256"] or report["protocolHash"] != digest(protocol) or report["environmentId"] != env_id:
        raise ValueError("DALI resume would use modified evidence or mix protocol/environment")
    if report.get("status") != "PASSED" or report.get("modelParityPassed") is not True:
        raise ValueError("failed DALI block")
    if len({value["modelStateSha256"] for value in report["backends"].values()}) != 1 or not all(
            report["backends"][name]["cacheInvariantsPassed"] is True for name in ("aether", "mmap")):
        raise ValueError("DALI correctness evidence is inconsistent")
    condition = report["condition"]
    index = report["blockIndex"]
    if condition not in protocol["conditions"] or type(index) is not int or not 0 <= index < protocol["repeats"] or report["seed"] != protocol["seedBase"] + index:
        raise ValueError("DALI block does not belong to the frozen protocol")
    if set(report["backends"]) != {"raw", "aether", "mmap"} or sorted(report["backendOrder"]) != ["aether", "mmap", "raw"]:
        raise ValueError("incomplete DALI paired block")
    if set(report["canonicalArtifactHashes"]) != {str(i) for i in range(condition["samples"])}:
        raise ValueError("incomplete DALI canonical reference")
    initial = round(condition["samples"] * protocol["reusePercent"] / 100)
    total = condition["samples"] * protocol["epochs"]
    for backend, value in report["backends"].items():
        throughput = value["effectiveSamplesPerSecond"]
        if not math.isfinite(throughput) or throughput <= 0 or report["throughput"][backend] != throughput:
            raise ValueError("invalid DALI throughput")
        if backend != "raw" and (value["initialReusableEntries"] != initial or value["lookups"] != total or
                value["misses"] != condition["samples"] - initial or value["hits"] != total - value["misses"] or value["publishedEntries"] != value["misses"]):
            raise ValueError("DALI cache counts violate the frozen lifecycle")
    if report["backends"]["aether"].get("engineInfo", {}).get("durability") != "DURABLE":
        raise ValueError("missing Java engine durability evidence")
    return report


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=ROOT / "configs/paper/datasets.json")
    parser.add_argument("--datasets", default="coco,imagenet")
    parser.add_argument("--samples", type=int)
    parser.add_argument("--repeats", type=int, default=12)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--batch-size", type=int, default=16)
    parser.add_argument("--reuse-percent", type=float, default=66.6445)
    parser.add_argument("--dali-threads", type=int, default=4)
    parser.add_argument("--dali-prefetch", type=int, default=2)
    parser.add_argument("--model-tier", choices=["small", "medium", "large"], default="small")
    parser.add_argument("--seed-base", type=int, default=20260904)
    parser.add_argument("--plan-only", action="store_true")
    parser.add_argument("--fixture-smoke", action="store_true", help="Label generated-fixture CUDA correctness; excluded from research analysis")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--output", type=Path, default=ROOT / "results/dali")
    args = parser.parse_args(argv)
    if min(args.repeats, args.epochs, args.batch_size, args.dali_threads, args.dali_prefetch) < 1 or not 0 <= args.reuse_percent <= 100:
        parser.error("invalid DALI matrix")
    config = json.loads(args.config.read_text())
    points = []
    for dataset in args.datasets.split(","):
        if dataset not in {"coco", "imagenet"}:
            parser.error("canonical DALI comparison supports COCO/ImageNet RGB workloads")
        spec = config[dataset]
        point = dict(dataset_kind=dataset, dataset_manifest=spec["manifestV2"], dataset_split=spec.get("split", "train"),
                     samples=args.samples if args.samples is not None else spec["samplesV2"], num_classes=spec["numClasses"],
                     resize=spec.get("imageSize", 256), trust_manifest_hashes=False)
        if point["samples"] < 1:
            parser.error("positive real sample count required")
        points.append(point)
    plan = {"schema": "aether-dali-protocol-v1", "conditions": points, "repeats": args.repeats,
        "measurementRole": "generated-fixture-smoke" if args.fixture_smoke else "secondary-canonical-DALI",
        "epochs": args.epochs, "batchSize": args.batch_size, "reusePercent": args.reuse_percent,
        "seedBase": args.seed_base, "modelTier": args.model_tier, "daliThreads": args.dali_threads, "daliPrefetch": args.dali_prefetch,
        "durability": "DURABLE", "backends": ["raw", "aether", "mmap"],
        "statistics": {"alpha": .05, "equivalenceMargin": .03, "bootstrapResamples": 10000,
                       "family": "secondary paired log-ratio RAW t-test and mmap TOST with Holm"},
        "canonicalWorkload": "DALI mixed RGB decode, bilinear antialiased resize, divide by 255, CHW fp16; consumed as fp32",
        "primaryEquivalent": False}
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.plan_only:
        write_json(args.output / "plan.json", plan)
        print("DALI comparison planned; CUDA experiments have not run")
        return
    os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")
    import torch
    if not torch.cuda.is_available() or not torch.version.cuda:
        raise RuntimeError("DALI comparison requires a CUDA GPU; no CPU timing fallback")
    import nvidia.dali
    provenance = environment()
    provenance["accelerator"] = {"backend": "cuda", "torchVersion": torch.__version__, "cudaVersion": torch.version.cuda,
                                 "selectedDevice": 0, "deviceName": torch.cuda.get_device_name(0)}
    identity = {"gpu": capture(["nvidia-smi", "--query-gpu=name,uuid,driver_version,memory.total", "--format=csv,noheader"]),
                "java": provenance["commands"]["java"], "packages": provenance["commands"]["packages"], "platform": provenance["platform"]}
    env_id = digest(identity)
    provenance["measurementIdentity"] = identity
    plan.update(sourceSha256=provenance["sourceSha256"], manifestSha256={p["dataset_manifest"]: sha256(p["dataset_manifest"]) for p in points},
                pipelineParameters={p["dataset_kind"]: descriptor(SimpleNamespace(**p), nvidia.dali.__version__) for p in points})
    protocol_path = args.output / "protocol.json"
    if protocol_path.exists() and json.loads(protocol_path.read_text()) != plan:
        raise ValueError("different frozen DALI protocol; use a new output directory")
    write_json(protocol_path, plan)
    write_json(args.output / f"environment-{env_id}.json", provenance)
    reports = []
    for point in points:
        for index in range(args.repeats):
            destination = args.output / point["dataset_kind"] / f"block-{index:04d}.json"
            destination.parent.mkdir(parents=True, exist_ok=True)
            if destination.exists():
                if not args.resume:
                    raise FileExistsError(destination)
                report = validated_report(destination, plan, env_id)
                if report["blockIndex"] != index or report["condition"] != point:
                    raise ValueError("DALI result stored under the wrong condition/block path")
            else:
                try:
                    report = run_block(args, point, index, destination, plan, env_id)
                    validated_report(destination, plan, env_id)
                except Exception as error:
                    import traceback
                    write_json(args.output / "failures" / f"{point['dataset_kind']}-{index:04d}-{time.time_ns()}.json",
                        {"status": "FAILED", "condition": point, "blockIndex": index, "seed": args.seed_base + index,
                         "protocolHash": digest(plan), "environmentId": env_id, "error": repr(error), "traceback": traceback.format_exc()})
                    raise
            reports.append(report)
            print(f"DALI {point['dataset_kind']} paired block {index + 1}/{args.repeats}: passed", flush=True)
    write_json(args.output / "summary.json", {"schema": "aether-dali-summary-v1", "allPassed": True, "blocks": len(reports),
        "measurementRole": "secondary DALI-canonical workload", "protocolHash": digest(plan)})


if __name__ == "__main__":
    main()
