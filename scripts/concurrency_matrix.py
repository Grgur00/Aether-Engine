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

from paper_common import environment, java_daemon, write_json
from aether_training_cache.java_store import JavaArtifactStore
from aether_training_cache.persistent_mmap import PersistentMmapStore


def payload(index, size):
    seed = hashlib.sha256(str(index).encode()).digest()
    return (seed * ((size + 31) // 32))[:size]


def worker(backend, root, port, indices, epochs, size, ready, start, results):
    store = None
    try:
        store = JavaArtifactStore(port=port, namespace="concurrency") if backend == "aether" else PersistentMmapStore(root, shared=True, durable=True)
        ready.put(True)
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
            ready.get(timeout=120)
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
    args = parser.parse_args(argv)
    backends, client_counts, worker_counts = args.backends.split(","), [int(x) for x in args.clients.split(",")], [int(x) for x in args.workers.split(",")]
    if not set(backends) <= {"aether", "mmap"} or min(client_counts) < 1 or min(worker_counts) < 0 or min(args.repeats, args.samples, args.epochs, args.payload_bytes) < 1 or not 0 <= args.reuse_percent <= 100:
        parser.error("invalid concurrency matrix")
    args.output.mkdir(parents=True, exist_ok=True)
    write_json(args.output / "environment.json", environment())
    for clients in client_counts:
        for workers in worker_counts:
            for repeat in range(args.repeats):
                order = list(backends)
                random.Random(args.seed + repeat).shuffle(order)
                for backend in order:
                    result_path = args.output / f"{backend}-c{clients}-w{workers}-r{repeat}.json"
                    if result_path.exists():
                        raise FileExistsError(result_path)
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
                            write_json(result_path, report)
                            print(f"{backend} clients={clients} workers={workers} repeat={repeat}: passed", flush=True)


if __name__ == "__main__":
    main()
