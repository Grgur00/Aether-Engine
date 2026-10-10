"""One version in a fresh process, reusing the immutable MONAI pilot cache adapters."""
import argparse
import contextlib
import hashlib
import json
import os
import threading
import time
from pathlib import Path
from types import SimpleNamespace

import monai_comparison as base
from paper_common import write_json


class DiskSampler:
    def __init__(self, directory):
        self.directory = directory
        self.stop_event = threading.Event()
        self.values = []
        self.errors = []
        self.thread = threading.Thread(target=self.run, daemon=True)

    def sample(self):
        try:
            self.values.append(base.disk_usage(self.directory))
        except FileNotFoundError:
            pass  # Compaction can remove files between directory enumeration and stat.
        except OSError as error:
            self.errors.append(str(error))

    def run(self):
        while not self.stop_event.wait(1):
            self.sample()

    def start(self):
        self.sample()
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        self.thread.join()
        self.sample()
        allocated = [value["allocatedBytes"] for value in self.values if value["allocatedBytes"] is not None]
        return {"peakAllocatedBytes": max(allocated) if allocated else None,
                "peakLogicalBytes": max((value["logicalBytes"] for value in self.values), default=0),
                "peakScope": "1-second sampled lower bound for live store; checkpoint copies excluded",
                "sampleCount": len(self.values), "samplingErrors": self.errors}


def model_hash(model):
    state = hashlib.sha256()
    for name, value in sorted(model.state_dict().items()):
        state.update(name.encode())
        state.update(value.detach().cpu().contiguous().numpy().tobytes())
    return state.hexdigest()


def strict_drain(port):
    report = base.drain(port)
    if report:
        state = report["backgroundCompaction"]
        if state.get("state") != "IDLE" or state.get("debtBytes") != 0 or state.get("failed", 0):
            raise RuntimeError("version boundary requires compaction IDLE, debt 0, and no failures")
    return report


def execute(request):
    torch, np, workload = base.torch, base.np, base.workload
    config = request["config"]
    stage, backend = request["version"], request["backend"]
    count = config["versions"][stage]
    previous = config["versions"][stage - 1] if stage else 0
    args = workload.parse_args(["--dataset-kind", "oct5k", "--dataset-manifest", request["manifest"],
        "--samples", str(count), "--resize", str(config["imageSize"]), "--preprocess-passes", "4",
        "--batch-size", str(config["batchSize"]), "--epochs", str(config["epochs"]),
        "--prefetch-batches", "0", "--trust-manifest-hashes"])
    args.seed = request["modelSeed"]
    sources = workload.load_sources(args)
    device = torch.device("cpu" if request.get("cpuFixture") else "cuda")
    if device.type == "cpu":
        torch.set_num_threads(1)
    elif not torch.cuda.is_available():
        raise RuntimeError("longitudinal performance evidence requires CUDA")
    directory = Path(request["store"])
    transform = base.CanonicalTransform(args)
    indices = base.batches(count, args.batch_size)
    timings = dict(startup=0., modelSetup=0., scanAdmission=0., training=0., drain=0., close=0.)
    external = request.get("service")
    if external and (backend != "aether" or config.get("serviceLifecycle") != "persistent-per-block"):
        raise ValueError("external service requires the separate persistent-service protocol")
    if backend == "aether" and config.get("serviceLifecycle") == "persistent-per-block" and not external:
        raise ValueError("persistent-service worker cannot start a replacement daemon")
    manager = (contextlib.nullcontext(external) if external else
               (base.java_daemon(directory) if backend == "aether" else contextlib.nullcontext(None)))
    disk_sampler = DiskSampler(directory)
    gpu_sampler = workload.GpuUtilizationSampler(250 if device.type == "cuda" and stage else 0)
    disk_sampler.start()
    gpu_sampler.start()
    parent_before = base.snapshot()
    ds, entered, daemon, epochs, drains = None, False, None, [], []
    validation_ms = 0.
    try:
        start = time.perf_counter()
        daemon = manager.__enter__()
        entered = True
        if request.get("lease"):
            write_json(request["lease"], {"pids": [os.getpid()] + ([daemon["pid"]] if daemon else [])})
        timings["startup"] = 0. if external else (time.perf_counter() - start) * 1000
        port = daemon["port"] if daemon else None
        java_before = base.snapshot(daemon["pid"]) if daemon else None
        info = None
        if daemon:
            with base.AetherTrainingCache(port=port) as client:
                info = client.engine_info()
            if external and info["pid"] != external["pid"]:
                raise RuntimeError("external service identity changed")
            if (info["durability"] != "DURABLE" or not info["backgroundCompaction"]["enabled"]
                    or info["integrityPolicy"]["version"] != "immutable-inline-admission-v1"):
                raise RuntimeError("engine settings changed")
            if info["cacheEntries"] != previous:
                raise RuntimeError("persistent Aether entries did not survive restart")
        start = time.perf_counter()
        ds = base.dataset(backend, sources, transform, directory, port)
        for batch in indices:
            base.fetch(ds, batch)
        timings["scanAdmission"] = (time.perf_counter() - start) * 1000
        if transform.calls != count - previous:
            raise RuntimeError(f"{backend} V{stage}: expected {count - previous} admissions, got {transform.calls}")
        start = time.perf_counter()
        drains.append(strict_drain(port))
        timings["drain"] += (time.perf_counter() - start) * 1000
        preparation_calls = transform.calls
        model = None
        initial_model_hash = None
        if stage:
            start = time.perf_counter()
            workload.set_seed(torch, args.seed)
            model = workload.create_model("small", torch).to(device)
            optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
            loss_fn = torch.nn.BCEWithLogitsLoss()
            workload.run_model_warmup(torch, model, loss_fn, optimizer, device, args)
            timings["modelSetup"] = (time.perf_counter() - start) * 1000
            initial_model_hash = model_hash(model)
            context = SimpleNamespace(args=args, np=np, run_seed=args.seed)
            start = time.perf_counter()
            for epoch in range(args.epochs):
                epoch_start, input_ms, losses, requests = time.perf_counter(), 0., [], 0
                for step, batch in enumerate(indices):
                    input_start = time.perf_counter()
                    values = base.fetch(ds, batch)
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
                    requests += len(batch)
                epochs.append({"epoch": epoch + 1, "wallMs": (time.perf_counter() - epoch_start) * 1000,
                               "inputWaitMs": input_ms, "losses": losses, "sampleRequests": requests})
            timings["training"] = (time.perf_counter() - start) * 1000
        if transform.calls != preparation_calls:
            raise RuntimeError("unexpected training-time cache miss/preprocessing")
        start = time.perf_counter()
        drains.append(strict_drain(port))
        timings["drain"] += (time.perf_counter() - start) * 1000
        start = time.perf_counter()
        actual = base.tensor_digest(value for batch in indices for value in base.fetch(ds, batch))
        if actual != request["referenceHash"] or transform.calls != preparation_calls:
            raise RuntimeError("decoded tensor checksum or persistent readback mismatch")
        final_hash = model_hash(model) if model is not None else None
        if daemon:
            with base.AetherTrainingCache(port=port) as client:
                info = client.engine_info()
            unique = info["cacheEntries"]
        elif backend == "mmap":
            unique = len(ds.store.index)
        elif backend == "monai_persistent":
            unique = len(list(directory.glob("*.pt")))
        else:
            unique = ds._read_env.stat()["entries"]  # MONAI 1.6.0 reader, pinned and integration-tested.
        if unique != count:
            raise RuntimeError("unique persistent artifact count mismatch")
        validation_ms = (time.perf_counter() - start) * 1000
        java_usage = base.delta(java_before, base.snapshot(daemon["pid"])) if daemon else None
        start = time.perf_counter()
        base.close(ds)
        ds = None
        manager.__exit__(None, None, None)
        entered = False
        timings["close"] = (time.perf_counter() - start) * 1000
        gpu = gpu_sampler.stop()
        peak = disk_sampler.stop()
        total = sum(timings.values())
        metrics = {"totalMs": total, "preparationMs": timings["scanAdmission"],
            "preprocessCalls": preparation_calls, "trainingPreprocessCalls": 0,
            "trainingCacheMisses": 0, "newSamples": count - previous, "reusedSamples": previous,
            "uniqueArtifacts": unique, "trainingSampleRequests": sum(e["sampleRequests"] for e in epochs),
            "epochs": epochs, "timingsMs": timings, "fullLifecycleMs": total,
            "serviceStartupMs": timings["startup"], "tensorSha256": actual, "modelSha256": final_hash,
            "initialModelSha256": initial_model_hash, "modelSeed": args.seed,
            "sampleOrderSha256": base.sha256(request["manifest"]), "transformIdentity": transform.identity,
            "engineInfo": info, "backgroundDrains": drains, "validationMs": validation_ms,
            "gpuUtilization": gpu, "disk": {**base.disk_usage(directory), **peak},
            "processUsage": {"python": base.delta(parent_before, base.snapshot()), "java": java_usage,
                             "scope": "stage process counters include validation/instrumentation; Java excludes process startup/exit"}}
        metrics["initialPopulation" if stage == 0 else "updateTraining"] = {
            key: metrics[key] for key in ("totalMs", "preparationMs", "preprocessCalls", "trainingPreprocessCalls", "epochs")}
        return metrics
    finally:
        gpu_sampler.stop()
        disk_sampler.stop()
        if ds is not None:
            base.close(ds)
        if entered:
            manager.__exit__(None, None, None)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--request", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    request = json.loads(args.request.read_text())
    lease = Path(request["lease"]) if request.get("lease") else None
    if lease:
        write_json(lease, {"pids": [os.getpid()]})
    try:
        write_json(args.output, execute(request))
    finally:
        # A killed process cannot reach this cleanup; resume must verify recorded PIDs.
        if lease:
            lease.unlink(missing_ok=True)
