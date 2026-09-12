"""Spawn-safe DataLoader batches; each worker owns its client and mmap handles."""
import atexit
import copy
import itertools
import time
from .resources import snapshot, delta, COUNTERS


def identity_collate(value):
    return value


class PreparedBatchDataset:
    def __init__(self, context, backend, schedule):
        self.args = copy.copy(context.args)
        self.sources = context.sources
        self.backend = backend
        self.schedule = list(schedule)
        self.mmap_root = context.mmap_root
        self.ram_ready = context.ram_ready if backend == "RAM_READY" else None
        self.context = None

    def __len__(self):
        return len(self.schedule)

    def _open(self):
        import numpy as np
        from benchmark_gpu_segmentation import BackendContext
        from .java_store import JavaArtifactStore
        from .persistent_mmap import PersistentMmapStore
        context = BackendContext.__new__(BackendContext)
        context.args, context.np, context.sources = self.args, np, self.sources
        context.ram_ready = self.ram_ready
        context.static_offsets = {}
        context.store = JavaArtifactStore(port=self.args.aether_port, namespace=self.args.aether_namespace)
        context.mmap_store = PersistentMmapStore(self.mmap_root, shared=True, durable=self.args.cache_durability == "durable")
        context.mmap_protocol = dict(lookups=0, hits=0, misses=0, lookupMs=0.0)
        context.protocol = {}
        atexit.register(context.store.close)
        atexit.register(context.mmap_store.close)
        self.context = context

    def __getitem__(self, index):
        if self.context is None:
            self._open()
        context = self.context
        context.reset_measured_counters()
        context.mmap_protocol = dict(lookups=0, hits=0, misses=0, lookupMs=0.0)
        context.mmap_store.reset_metrics()
        indices, epoch = self.schedule[index]
        before = snapshot()
        started = time.perf_counter()
        batch, counters = context.batch(self.backend, indices)
        duration = (time.perf_counter() - started) * 1000
        usage = delta(before, snapshot())
        return (indices, epoch, (batch, counters, duration), context.protocol, context.mmap_protocol,
                context.mmap_store.metrics, usage, context.store.client.protocol_metrics(), context.store.operation_observations())


def worker_batches(context, backend, schedule):
    import torch
    # Finish an epoch before scheduling the next: no early next-epoch reads can
    # race first-epoch lazy publications. Each sample occurs once per epoch.
    for _, epoch_schedule in itertools.groupby(schedule, key=lambda item: item[1]):
        dataset = PreparedBatchDataset(context, backend, list(epoch_schedule))
        loader = torch.utils.data.DataLoader(dataset, batch_size=None,
            num_workers=context.args.workers, multiprocessing_context="spawn",
            collate_fn=identity_collate, prefetch_factor=max(1, context.args.prefetch_batches),
            persistent_workers=False)
        iterator = iter(loader)
        while True:
            started = time.perf_counter()
            try:
                indices, epoch, prepared, protocol, mmap_protocol, mmap_metrics, usage, transport, observations = next(iterator)
            except StopIteration:
                break
            wait_ms = (time.perf_counter() - started) * 1000
            if not hasattr(context, "worker_resources"):
                context.worker_resources = {}
            key = str(usage["pid"])
            if key not in context.worker_resources:
                context.worker_resources[key] = usage
            else:
                totals = context.worker_resources[key]
                for field in COUNTERS:
                    totals[field] = totals[field] + usage[field] if totals[field] is not None and usage[field] is not None else None
                for field in ("rssBytesAtEnd", "lifetimePeakRssBytes"):
                    totals[field] = usage[field]
            if backend == "AETHER_CACHE":
                if not hasattr(context, "worker_aether_transport"):
                    context.worker_aether_transport = {"connectionsOpened": 0, "requestsSent": 0, "operationCounts": {}}
                    context.worker_aether_observations = {}
                for key in ("connectionsOpened", "requestsSent"):
                    context.worker_aether_transport[key] += transport[key]
                for key, count in transport["operationCounts"].items():
                    counts = context.worker_aether_transport["operationCounts"]
                    counts[key] = counts.get(key, 0) + count
                for key, values in observations.items():
                    context.worker_aether_observations.setdefault(key, []).extend(values)
                for key, value in protocol.items():
                    if key == "connectionsOpened":
                        continue
                    if isinstance(value, list):
                        context.protocol[key].extend(value)
                    else:
                        context.protocol[key] += value
            elif backend == "STATIC_PREPROCESSED_MMAP":
                for key, value in mmap_protocol.items():
                    context.mmap_protocol[key] += value
                for key, value in mmap_metrics.items():
                    context.mmap_store.metrics[key] += value
            yield indices, epoch, prepared, wait_ms
