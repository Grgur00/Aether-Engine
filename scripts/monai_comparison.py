"""Exploratory paired OCT5K comparison with actual MONAI persistent caches."""
import argparse
import contextlib
import hashlib
import importlib.metadata
import json
import os
import random
import time
from pathlib import Path
from types import SimpleNamespace

from paper_common import java_daemon, sha256, write_json
from cache_workspace import cache_workspace
from system_campaign import campaign, save_result

os.environ.setdefault("CUBLAS_WORKSPACE_CONFIG", ":4096:8")

import numpy as np
import torch
from monai.data import LMDBDataset, PersistentDataset
from monai.transforms import Transform
from aether_ml.codecs import TensorDictCodec
from aether_ml.monai import AetherPersistentDataset
from aether_training_cache.client import AetherTrainingCache
from aether_training_cache.persistent_mmap import PersistentMmapStore
from aether_training_cache.resources import snapshot, delta
import benchmark_gpu_segmentation as workload

BACKENDS = ("aether", "mmap", "monai_persistent", "monai_lmdb")


class CanonicalTransform(Transform):
    def __init__(self, args):
        self.args = args
        self.calls = 0
        self.identity = {"schema": "oct5k-monai-tensors-v1", "resize": args.resize,
                         "passes": args.preprocess_passes, "image": "float16", "mask": "uint8",
                         "pipeline": args.pipeline_version, "octVersion": args.oct5k_transform_version,
                         "scale": args.normalization_scale, "offset": args.normalization_offset}

    def __call__(self, item):
        self.calls += 1
        value = workload.preprocess_sample(item, self.args, np)
        return {"image": torch.from_numpy(value["image"].astype(np.float16)),
                "mask": torch.from_numpy(value["mask"].astype(np.uint8))}

    def key(self, item):
        # LMDBDataset does not apply hash_transform to its database keys.
        value = {"source": item["source_identity"], "transform": self.identity}
        return hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest().encode("ascii")


class MmapDataset:
    def __init__(self, data, transform, directory):
        self.data, self.transform = data, transform
        self.store = PersistentMmapStore(directory, durable=True)
        self.codec = TensorDictCodec()

    def get_batch(self, indices):
        keys = [self.transform.key(self.data[i]).decode("ascii") for i in indices]
        payloads = [self.store.get(key) for key in keys]
        missing = [(key, self.codec.encode(self.transform(self.data[i])))
                   for i, key, payload in zip(indices, keys, payloads) if payload is None]
        self.store.put_many(missing)
        added = dict(missing)
        return [self.codec.decode(added[key] if payload is None else payload[4:])
                for key, payload in zip(keys, payloads)]

    def close(self):
        self.store.close()


def dataset(backend, data, transform, directory, port=None):
    if backend == "aether":
        return AetherPersistentDataset(data, transform, namespace="monai-pilot-v1",
            client=AetherTrainingCache(port=port),
            identity_fn=lambda item, index: item["source_identity"], transform_identity=transform.identity)
    if backend == "mmap":
        return MmapDataset(data, transform, directory)
    common = dict(data=data, transform=transform, cache_dir=directory, hash_func=transform.key)
    if backend == "monai_persistent":
        return PersistentDataset(**common)
    if backend == "monai_lmdb":
        capacity = max(16 * 1024 ** 2, 2 * len(data) * transform.args.resize ** 2 * 3)
        return LMDBDataset(**common, progress=False, lmdb_kwargs={"map_size": capacity})
    raise ValueError(backend)


def batches(count, batch_size):
    return [list(range(start, min(start + batch_size, count))) for start in range(0, count, batch_size)]


def fetch(ds, indices):
    return ds.get_batch(indices) if hasattr(ds, "get_batch") else [ds[i] for i in indices]


def close(ds):
    if hasattr(ds, "close"):
        ds.close()


def tensor_digest(items):
    digest = hashlib.sha256()
    for value in items:
        for name in ("image", "mask"):
            tensor = value[name].detach().cpu().contiguous()
            digest.update(str((name, tensor.dtype, tuple(tensor.shape))).encode())
            digest.update(tensor.numpy().tobytes())
    return digest.hexdigest()


def disk_usage(directory):
    stats = [path.stat() for path in Path(directory).rglob("*") if path.is_file()]
    return {"logicalBytes": sum(s.st_size for s in stats), "files": len(stats),
            "allocatedBytes": sum(s.st_blocks * 512 for s in stats) if all(hasattr(s, "st_blocks") for s in stats) else None}


def drain(port):
    if port is None:
        return None
    with AetherTrainingCache(port=port) as client:
        report = client.wait_for_background_compaction()
    if not report["drained"] or report["backgroundCompaction"].get("failed", 0):
        raise RuntimeError("Aether background compaction failed or did not drain")
    return report


def measure_stage(backend, data, args, directory, device, reference, daemon=None, train=False):
    transform = CanonicalTransform(args)
    port = daemon["port"] if daemon else None
    indices = batches(len(data), args.batch_size)
    model = optimizer = loss_fn = None
    if train:
        workload.set_seed(torch, args.seed)
        model = workload.create_model("small", torch).to(device)
        optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
        loss_fn = torch.nn.BCEWithLogitsLoss()
        workload.run_model_warmup(torch, model, loss_fn, optimizer, device, args)
    sampler = workload.GpuUtilizationSampler(250 if device.type == "cuda" and train else 0)
    sampler.start()
    before, java_before = snapshot(), snapshot(daemon["pid"]) if daemon else None
    ds = None
    started = time.perf_counter()
    try:
        ds = dataset(backend, data, transform, directory, port)
        # All implementations get the same complete admission scan; LMDB may fill in __init__.
        for batch in indices:
            fetch(ds, batch)
        admission_drain = drain(port)
        preparation_ms = (time.perf_counter() - started) * 1000
        preparation_calls = transform.calls
        epochs = []
        context = SimpleNamespace(args=args, np=np, run_seed=args.seed)
        if train:
            for epoch in range(args.epochs):
                epoch_start, input_ms, losses = time.perf_counter(), 0.0, []
                for step, batch in enumerate(indices):
                    input_start = time.perf_counter()
                    values = fetch(ds, batch)
                    tensors = {"images": np.stack([v["image"].numpy() for v in values]).astype(np.float32),
                               "masks": np.stack([v["mask"].numpy() for v in values]).astype(np.float32)}
                    tensors = workload.augment_batch(tensors, context, batch, step, epoch)
                    input_ms += (time.perf_counter() - input_start) * 1000
                    x, y = (torch.from_numpy(tensors[key]).to(device) for key in ("images", "masks"))
                    optimizer.zero_grad(set_to_none=True)
                    loss = loss_fn(model(x), y)
                    loss.backward()
                    optimizer.step()
                    workload.synchronize_device(torch, device)
                    losses.append(loss.item())
                epochs.append({"epoch": epoch + 1, "wallMs": (time.perf_counter() - epoch_start) * 1000,
                               "inputWaitMs": input_ms, "losses": losses,
                               "cumulativeMs": (time.perf_counter() - started) * 1000,
                               "preprocessCalls": transform.calls - preparation_calls})
        final_drain = drain(port)
        elapsed_ms = (time.perf_counter() - started) * 1000
        usage = {"python": delta(before, snapshot()),
                 "java": delta(java_before, snapshot(daemon["pid"])) if daemon else None}
        gpu = sampler.stop()
        calls = transform.calls
        # Readback validation is outside the timed endpoint and must never compute a miss.
        actual = tensor_digest(value for batch in indices for value in fetch(ds, batch))
        if actual != reference or transform.calls != calls:
            raise RuntimeError(f"{backend}: cached tensor correctness/reuse failure")
        state = hashlib.sha256()
        if model is not None:
            for name, value in sorted(model.state_dict().items()):
                state.update(name.encode())
                state.update(value.detach().cpu().contiguous().numpy().tobytes())
        if epochs:
            epochs[-1]["cumulativeMs"] = elapsed_ms
        return {"totalMs": elapsed_ms, "preparationMs": preparation_ms,
                "preprocessCalls": calls, "trainingPreprocessCalls": calls - preparation_calls,
                "preparationReusable": len(data) - preparation_calls,
                "trainingCacheHits": len(data) * args.epochs if train else 0,
                "epochs": epochs, "disk": disk_usage(directory), "processUsage": usage,
                "gpuUtilization": gpu, "tensorSha256": actual,
                "modelSha256": state.hexdigest() if train else None,
                "admissionDrain": admission_drain, "finalDrain": final_drain}
    finally:
        sampler.stop()
        if ds is not None:
            close(ds)


def paired_summary(blocks):
    from scipy.stats import t
    result = {}
    for baseline in BACKENDS[1:]:
        ratios = np.array([b["backends"][baseline]["updateTraining"]["totalMs"] /
                           b["backends"]["aether"]["updateTraining"]["totalMs"] for b in blocks])
        logs = np.log(ratios)
        mean = float(logs.mean())
        ci = None
        if len(logs) > 1:
            radius = float(t.ppf(.975, len(logs) - 1) * logs.std(ddof=1) / np.sqrt(len(logs)))
            ci = [float(np.exp(mean - radius)), float(np.exp(mean + radius))]
        checkpoints = {}
        for epoch in (1, 5, 10, 20):
            if epoch > len(blocks[0]["backends"]["aether"]["updateTraining"]["epochs"]):
                continue
            values = [b["backends"][baseline]["updateTraining"]["epochs"][epoch - 1]["cumulativeMs"] /
                      b["backends"]["aether"]["updateTraining"]["epochs"][epoch - 1]["cumulativeMs"] for b in blocks]
            checkpoints[str(epoch)] = float(np.exp(np.log(values).mean()))
        result[baseline] = {"n": len(logs), "aetherThroughputRatio": float(np.exp(mean)),
                            "descriptiveCI95": ci, "cumulativeRatios": checkpoints}
    return {"measurementRole": "exploratory pilot; no confirmatory claim", "comparisons": result}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--pilot-repeats", type=int, default=5)
    parser.add_argument("--epochs", type=int, default=20)
    parser.add_argument("--prefetch-depth", type=int, choices=[0], default=0)
    parser.add_argument("--cpu-fixture", action="store_true")
    opts = parser.parse_args(argv)
    if opts.pilot_repeats < 1 or opts.epochs < 1:
        parser.error("repetitions and epochs must be positive")
    if not opts.cpu_fixture and (opts.epochs != 20 or opts.pilot_repeats != 5):
        parser.error("this exploratory GPU protocol is fixed at 5 paired repetitions and 20 epochs")
    for package, version in (("monai", "1.6.0"), ("lmdb", "2.1.1")):
        if importlib.metadata.version(package) != version:
            raise RuntimeError(f"requires {package}=={version}")
    device = torch.device("cpu" if opts.cpu_fixture else "cuda")
    if device.type == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("GPU pilot requires CUDA; no silent CPU fallback")
    spec = json.loads(opts.config.read_text())["oct5k"]
    versions, arguments = [], []
    for version in ("V1", "V2"):
        args = workload.parse_args(["--dataset-kind", "oct5k", "--dataset-manifest", spec["manifest" + version],
            "--dataset-split", spec.get("split", "train"), "--samples", str(spec["samples" + version]),
            "--resize", str(spec["imageSize"]), "--epochs", str(opts.epochs), "--preprocess-passes", "4",
            "--batch-size", "16", "--prefetch-batches", "0"])
        versions.append(workload.load_sources(args))
        arguments.append(args)
    v1, v2 = versions
    old, new = ({s["source_identity"] for s in data} for data in versions)
    if not old <= new or len(old & new) != spec["expectedReusable"]:
        raise ValueError("expected append-only workload with unchanged reusable content")
    if not opts.cpu_fixture and (len(v1), len(v2), len(new - old), spec["imageSize"]) != (1430, 1505, 75, 256):
        raise ValueError("GPU pilot requires the frozen OCT5K append75 workload")
    protocol = {"id": "oct5k-monai-append75-pilot-v1", "confirmatory": False,
        "measurementRole": "cpu correctness fixture" if opts.cpu_fixture else "exploratory pilot",
        "pairedBlocks": opts.pilot_repeats, "epochs": opts.epochs, "batchSize": 16,
        "prefetchDepth": 0, "serverTrace": False, "backends": list(BACKENDS),
        "spec": spec, "manifestSha256": [sha256(spec["manifest" + v]) for v in ("V1", "V2")],
        "transform": CanonicalTransform(arguments[1]).identity,
        "primaryPilotEndpoint": "V2 cache open + complete admission scan + 20 training epochs + background drain",
        "initialPopulationReportedSeparately": True, "checkpoints": [1, 5, 10, 20],
        "backendOrder": "random.Random(20260925 + blockIndex).shuffle",
        "sampleOrder": "manifest order each epoch; identical between backends",
        "model": "small segmentation; AdamW lr=0.001; BCEWithLogitsLoss; 12 zero-input warmup steps",
        "timingExclusions": "source hash/preprocessing reference preflight, model setup/warmup, Java startup, validation, close",
        "pageCache": "uncontrolled; source checksum and reference preflight; warm persistent V1 cache",
        "serialization": "Aether/mmap TensorDictCodec; MONAI native torch.save; identical fp16 image/u8 mask tensors",
        "lmdbMapSize": "max(16 MiB, twice uncompressed tensor payload); native automatic growth if needed",
        "durability": "Aether DURABLE; mmap fsync; LMDB default sync/metasync; PersistentDataset no fsync guarantee",
        "gpuWaiting": "synchronized inputWaitMs proxy, not hardware idle time; sampled utilization also retained"}
    with campaign(opts.output, protocol) as meta:
        references = [tensor_digest(CanonicalTransform(args)(item) for item in data)
                      for args, data in zip(arguments, versions)]
        blocks = []
        for block_index in range(opts.pilot_repeats):
            seed = 20260925 + block_index
            order = list(BACKENDS)
            random.Random(seed).shuffle(order)
            report = {"block": block_index, "seed": seed, "backendOrder": order, "backends": {}}
            for backend in order:
                print(f"Block {block_index + 1}/{opts.pilot_repeats}: {backend}", flush=True)
                location = opts.output / "blocks" / f"{block_index:02d}" / backend
                with cache_workspace(location, scratch_root=opts.scratch_root,
                                     payload_bytes=len(v2) * spec["imageSize"] ** 2 * 6) as scratch:
                    store = scratch / "store"
                    startup = time.perf_counter()
                    manager = java_daemon(store) if backend == "aether" else contextlib.nullcontext(None)
                    with manager as daemon:
                        startup_ms = (time.perf_counter() - startup) * 1000
                        engine_info = None
                        if daemon:
                            with AetherTrainingCache(port=daemon["port"]) as client:
                                engine_info = client.engine_info()
                            if (engine_info.get("durability") != "DURABLE"
                                    or engine_info.get("integrityPolicy", {}).get("version") != "immutable-inline-admission-v1"
                                    or engine_info.get("backgroundCompaction", {}).get("enabled") is not True):
                                raise RuntimeError("Aether engine settings differ from the pilot protocol")
                        stages = []
                        for index, (args, data) in enumerate(zip(arguments, versions)):
                            args.seed = seed
                            stages.append(measure_stage(backend, data, args, store, device, references[index],
                                                        daemon, train=index == 1))
                        if stages[0]["preprocessCalls"] != len(v1) or stages[1]["preprocessCalls"] != len(new - old):
                            raise RuntimeError(f"{backend}: wrong initial/update preprocessing counts")
                        result = {"initialPopulation": stages[0], "updateTraining": stages[1],
                                  "engineInfo": engine_info,
                                  "serviceStartupMs": startup_ms,
                                  "fullLifecycleMs": startup_ms + sum(stage["totalMs"] for stage in stages)}
                        report["backends"][backend] = result
                        save_result(location / "result.json", result, meta)
            if len({r["updateTraining"]["modelSha256"] for r in report["backends"].values()}) != 1:
                raise RuntimeError("paired final model states differ")
            save_result(opts.output / "blocks" / f"{block_index:02d}" / "paired.json", report, meta)
            blocks.append(report)
        write_json(opts.output / "summary.json", paired_summary(blocks))


if __name__ == "__main__":
    main()
