"""Shared-store process scaling microbenchmark (explicitly not GPU training throughput)."""
import argparse
import hashlib
import multiprocessing
import queue
import random
import struct
import tempfile
import time
from pathlib import Path

from paper_common import java_daemon, write_json
from system_campaign import campaign, load_result, save_result
from aether_training_cache.java_store import JavaArtifactStore
from aether_training_cache.persistent_mmap import PersistentMmapStore


def payload(index, size):
    seed = hashlib.sha256(str(index).encode()).digest()
    return (seed * ((size + 31) // 32))[:size]


def worker(backend, root, port, indices, epochs, size, ready, start, results):
    store = None
    announced = False
    try:
        store = JavaArtifactStore(port=port, namespace="concurrency") if backend == "aether" else PersistentMmapStore(root, shared=True, durable=True)
        ready.put(True)
        announced = True
        if not start.wait(120):
            raise TimeoutError("client start barrier timed out")
        started = time.perf_counter()
        hits = misses = attempts = read_bytes = 0
        for _ in range(epochs):
            for index in indices:
                key = str(index)
                if backend == "aether":
                    value = store.load_cached_bytes_many([key]).get(key)
                else:
                    record = store.get(key)
                    value = record[4:] if record is not None else None
                if value is None:
                    misses += 1
                    value = payload(index, size)
                    if backend == "aether":
                        store.commit_bytes_many([{"cache_key": key, "data": value}])
                    else:
                        store.put(key, value)
                    attempts += 1
                else:
                    hits += 1
                    read_bytes += len(value)
                if hashlib.sha256(value).digest() != hashlib.sha256(payload(index, size)).digest():
                    raise AssertionError("shared store returned wrong payload")
        results.put({"passed": True, "samples": len(indices) * epochs,
                     "wallSeconds": time.perf_counter() - started, "hits": hits, "misses": misses,
                     "publishAttempts": attempts, "bytesRead": read_bytes})
    except BaseException as error:
        if not announced:
            ready.put({"error": repr(error)})
        results.put({"passed": False, "error": repr(error)})
    finally:
        if store is not None:
            store.close()


def run_clients(backend, root, port, *, clients, workers, samples, epochs, size):
    context = multiprocessing.get_context("spawn")
    ready, results, start = context.Queue(), context.Queue(), context.Event()
    processes = []
    for _ in range(clients):
        for rank in range(max(1, workers)):
            indices = list(range(rank, samples, max(1, workers)))
            processes.append(context.Process(target=worker, args=(backend, str(root), port, indices, epochs, size, ready, start, results)))
    launch = time.perf_counter()
    try:
        for process in processes:
            process.start()
        for _ in processes:
            status = ready.get(timeout=120)
            if status is not True:
                raise RuntimeError(f"client failed before start barrier: {status}")
        started = time.perf_counter()
        start.set()
        reports = [results.get(timeout=300) for _ in processes]
        elapsed = time.perf_counter() - started
        for process in processes:
            process.join(timeout=15)
            if process.is_alive() or process.exitcode != 0:
                raise RuntimeError("client failed to exit successfully")
        if not all(report["passed"] for report in reports):
            raise RuntimeError(f"client verification failed: {reports}")
        return {"backend": backend, "clients": clients, "workerProcessesPerClient": workers,
                "actualProcesses": len(processes), "aggregateSamplesPerSecond": clients * samples * epochs / elapsed,
                "synchronizedWallSeconds": elapsed, "includingStartupSeconds": time.perf_counter() - launch,
                "workers": reports, "allPassed": True}
    finally:
        for process in processes:
            if process.is_alive():
                process.kill()
                process.join(timeout=15)
        ready.close()
        results.close()


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--backends", default="aether,mmap")
    parser.add_argument("--clients", default="1,2,4")
    parser.add_argument("--workers", default="0,2,4,8")
    parser.add_argument("--repeats", type=int, default=10)
    parser.add_argument("--samples", type=int, default=128)
    parser.add_argument("--epochs", type=int, default=5)
    parser.add_argument("--payload-bytes", type=int, default=65536)
    parser.add_argument("--reuse-percent", type=float, default=66.6445)
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--output", type=Path, default=Path("results/concurrency"))
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args(argv)
    backends, client_counts, worker_counts = args.backends.split(","), [int(x) for x in args.clients.split(",")], [int(x) for x in args.workers.split(",")]
    if not set(backends) <= {"aether", "mmap"} or min(client_counts) < 1 or min(worker_counts) < 0 or min(args.repeats, args.samples, args.epochs, args.payload_bytes) < 1 or not 0 <= args.reuse_percent <= 100:
        parser.error("invalid concurrency matrix")
    if any(len(set(values)) != len(values) for values in (backends, client_counts, worker_counts)):
        parser.error("duplicate matrix values")
    protocol = {"kind": "storage-concurrency", "backends": backends, "clients": client_counts,
                "workers": worker_counts, "repeats": args.repeats, "samples": args.samples,
                "epochs": args.epochs, "payloadBytes": args.payload_bytes,
                "reusePercent": args.reuse_percent, "seed": args.seed}
    with campaign(args.output, protocol, args.resume) as metadata:
        run_campaign(args, backends, client_counts, worker_counts, metadata)


def run_campaign(args, backends, client_counts, worker_counts, metadata):
    for clients in client_counts:
        for workers in worker_counts:
            for repeat in range(args.repeats):
                order = list(backends)
                random.Random(args.seed + repeat).shuffle(order)
                for backend in order:
                    result_path = args.output / f"{backend}-c{clients}-w{workers}-r{repeat}.json"
                    if result_path.exists():
                        report = load_result(result_path, metadata)
                        validate_report(report, metadata)
                        expected = (backend, clients, workers, repeat)
                        if tuple(report[key] for key in ("backend", "clients", "workerProcessesPerClient", "repeat")) != expected:
                            raise ValueError("concurrency result does not match its trial")
                        print(f"{result_path.name}: verified existing result", flush=True)
                        continue
                    with tempfile.TemporaryDirectory(prefix="concurrent-", dir=args.output) as temporary:
                        root = Path(temporary)
                        with java_daemon(root / "java") as daemon:
                            store = JavaArtifactStore(port=daemon["port"], namespace="concurrency") if backend == "aether" else PersistentMmapStore(root / "mmap", durable=True)
                            initial = round(args.samples * args.reuse_percent / 100)
                            for index in range(initial):
                                if backend == "aether":
                                    store.commit_bytes_many([{"cache_key": str(index), "data": payload(index, args.payload_bytes)}])
                                else:
                                    store.put(str(index), payload(index, args.payload_bytes))
                            store.close()
                            report = run_clients(backend, root / "mmap", daemon["port"], clients=clients,
                                workers=workers, samples=args.samples, epochs=args.epochs, size=args.payload_bytes)
                            # Verify every unique key after all racing clients have finished.
                            verifier = JavaArtifactStore(port=daemon["port"], namespace="concurrency") if backend == "aether" else PersistentMmapStore(root / "mmap", durable=True)
                            try:
                                for index in range(args.samples):
                                    value = verifier.load_cached_bytes_many([str(index)]).get(str(index)) if backend == "aether" else verifier.get(str(index))[4:]
                                    if value != payload(index, args.payload_bytes):
                                        raise AssertionError("post-concurrency store verification failed")
                            finally:
                                verifier.close()
                            report.update(measurementRole="synthetic storage-client microbenchmark", repeat=repeat,
                                initialReusableEntries=initial, uniqueFinalKeysVerified=args.samples,
                                duplicateComputeAllowed=True, publicationMetric="client attempts, not unique engine commits",
                                pageCacheProtocol="uncontrolled", backendOrder=order)
                            validate_report(report, metadata)
                            save_result(result_path, report, metadata)
                            print(f"{backend} clients={clients} workers={workers} repeat={repeat}: passed", flush=True)


def validate_report(report, metadata):
    import math
    protocol = metadata["protocol"]
    clients, workers, repeat = (report[key] for key in ("clients", "workerProcessesPerClient", "repeat"))
    backend = report["backend"]
    expected_processes = clients * max(1, workers)
    expected_samples = clients * protocol["samples"] * protocol["epochs"]
    elapsed = report["synchronizedWallSeconds"]
    order = list(protocol["backends"])
    random.Random(protocol["seed"] + repeat).shuffle(order)
    if (protocol["kind"] != "storage-concurrency" or backend not in protocol["backends"]
            or clients not in protocol["clients"] or workers not in protocol["workers"]
            or not 0 <= repeat < protocol["repeats"] or report["backendOrder"] != order
            or report.get("allPassed") is not True or report["actualProcesses"] != expected_processes
            or len(report["workers"]) != expected_processes
            or report.get("uniqueFinalKeysVerified") != protocol["samples"]
            or report.get("initialReusableEntries") != round(protocol["samples"] * protocol["reusePercent"] / 100)
            or not math.isfinite(elapsed) or elapsed <= 0
            or not math.isclose(report["aggregateSamplesPerSecond"], expected_samples / elapsed, rel_tol=1e-12)
            or sum(worker["samples"] for worker in report["workers"]) != expected_samples
            or not all(worker.get("passed") is True and worker["hits"] + worker["misses"] == worker["samples"]
                       for worker in report["workers"])):
        raise ValueError("incomplete, failed or inconsistent concurrency measurement")


def validated_concurrency(root):
    from system_campaign import load_campaign
    root = Path(root)
    metadata = load_campaign(root)
    reports, seen = [], set()
    for path in sorted(root.glob("*-c*-w*-r*.json")):
        if path.name.endswith(".receipt.json"):
            continue
        report = load_result(path, metadata)
        validate_report(report, metadata)
        identity = tuple(report[key] for key in ("backend", "clients", "workerProcessesPerClient", "repeat"))
        if identity in seen:
            raise ValueError("duplicate concurrency measurement")
        seen.add(identity)
        reports.append(report)
    if not reports:
        raise ValueError("no concurrency measurements")
    return reports


if __name__ == "__main__":
    main()
