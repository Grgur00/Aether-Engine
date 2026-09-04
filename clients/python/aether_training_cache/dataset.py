from concurrent.futures import ThreadPoolExecutor
import time


class AetherDataLoader:
    """Batch loader using one reference request per batch and bounded prefetching."""

    def __init__(self, cache, keys, mapped_segments, batch_size=32, dtype="int32", shape=None,
                 workers=0, prefetch_batches=0, pin_memory=False):
        self.cache = cache
        self.keys = list(keys)
        self.mapped_segments = mapped_segments
        self.batch_size = batch_size
        self.dtype = dtype
        self.shape = shape
        if workers < 0 or prefetch_batches < 0:
            raise ValueError("workers and prefetch_batches must be non-negative")
        self.workers = workers
        self.prefetch_batches = prefetch_batches
        self.pin_memory = pin_memory
        self.batch_latencies = []
        self.input_wait_nanos = 0
        self.total_nanos = 0
        self.samples_processed = 0

    def _load(self, batch_keys):
        started = time.perf_counter_ns()
        references = self.cache.get_many_refs(batch_keys)
        views = [self.mapped_segments.view(references[key]) for key in batch_keys if key in references]
        if self.pin_memory:
            from .tensor import pinned_tensor, torch_tensor
            result = [pinned_tensor(torch_tensor(view, self.dtype, self.shape)) for view in views]
        else:
            result = views
        elapsed = time.perf_counter_ns() - started
        self.batch_latencies.append(elapsed)
        self.input_wait_nanos += elapsed
        self.samples_processed += len(batch_keys)
        return result

    def __iter__(self):
        iteration_started = time.perf_counter_ns()
        batches = [self.keys[start:start + self.batch_size]
                   for start in range(0, len(self.keys), self.batch_size)]
        executor = ThreadPoolExecutor(max_workers=self.workers) if self.workers else None
        try:
            if executor is None:
                for batch in batches:
                    yield self._load(batch)
            else:
                futures = []
                for batch in batches:
                    futures.append(executor.submit(self._load, batch))
                    if len(futures) > max(1, self.prefetch_batches + 1):
                        yield futures.pop(0).result()
                for future in futures:
                    yield future.result()
        finally:
            if executor is not None:
                executor.shutdown(wait=True)
            self.total_nanos = time.perf_counter_ns() - iteration_started

    def __len__(self):
        return (len(self.keys) + self.batch_size - 1) // self.batch_size

    def metrics(self):
        ordered = sorted(self.batch_latencies)
        def percentile(value):
            if not ordered:
                return 0
            return ordered[min(len(ordered) - 1, max(0, int((value / 100) * len(ordered) + .999) - 1))]
        total = sum(self.batch_latencies)
        return {
            "batches": len(ordered),
            "samples": self.samples_processed,
            "inputWaitNanos": self.input_wait_nanos,
            "inputWaitPercent": 0.0 if self.total_nanos == 0 else 100.0 * self.input_wait_nanos / self.total_nanos,
            "batchLatencyP50Nanos": percentile(50),
            "batchLatencyP95Nanos": percentile(95),
            "batchLatencyP99Nanos": percentile(99),
        }


AetherBatchLoader = AetherDataLoader
