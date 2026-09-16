"""Ordered, bounded prefetch for a finite schedule constructed by the caller.

The iterator owns one producer for positive depths and no thread for depth zero.
It never owns the callback's client/socket: close the iterator before closing that
client. Supply cancel_callback to interrupt pending I/O on early close; normal
exhaustion preserves the client. Without cancellation, callback I/O including
retries must fit within join_timeout. Close raises if it cannot join the producer.
Use a context manager or a finally block on early exit.
"""

from dataclasses import dataclass, replace
import math
from queue import Queue
import threading
from time import perf_counter_ns
from typing import Any, Callable, Iterable, Iterator


QUEUE_DEPTH_DEFINITION = (
    "Arithmetic mean of ready-batch queue depth sampled immediately after each "
    "successful batch enqueue and consumer dequeue, under the queue condition; "
    "excludes failure markers, shutdown drains and in-flight preparation. "
    "Epoch/run means are weighted by queueDepthSamples; depth zero has no samples."
)
_TOTALS = (
    "batchesRequested", "batchesConsumed", "queueEmptyWaitNs", "queueFullWaitNs",
    "prefetchLookupNs", "prefetchDecodeNs", "consumerWaitNs",
    "queueDepthSamples", "queueDepthTotal",
)


def merge_prefetch_metrics(snapshots, prefetch_depth):
    """Sum counters and combine occupancy samples without averaging epoch means."""
    snapshots = list(snapshots)
    result = {key: sum(value.get(key, 0) for value in snapshots) for key in _TOTALS}
    result["prefetchDepth"] = prefetch_depth
    result["maxQueueDepth"] = max((value.get("maxQueueDepth", 0) for value in snapshots), default=0)
    result["meanQueueDepth"] = (
        result["queueDepthTotal"] / result["queueDepthSamples"]
        if result["queueDepthSamples"] else 0.0
    )
    result["meanQueueDepthDefinition"] = QUEUE_DEPTH_DEFINITION
    return result


@dataclass(frozen=True)
class PreparedBatch:
    """Callback result with separately measured lookup/decode durations in ns."""

    value: Any
    lookup_ns: int = 0
    decode_ns: int = 0


@dataclass(frozen=True)
class PrefetchedBatch:
    batchIndex: int
    task: Any
    value: Any
    consumerWaitNs: int = 0


@dataclass(frozen=True)
class PrefetchFailure:
    batchIndex: int
    exception: BaseException


class BoundedPrefetchIterator(Iterator[PrefetchedBatch]):
    """Prepare a caller-generated schedule in order, with at most one callback active.

    ``schedule`` is materialized on the constructing thread before any producer
    starts; its entries should be immutable or exclusively owned by the iterator.
    ``prepare(entry)`` returns a payload or PreparedBatch. Optional
    ``metrics_callback(payload)`` returns lookup_ns/decode_ns counters for a raw
    payload, and runs on the same thread as prepare. ``batchIndex`` starts at zero for
    each iterator. One consumer is supported. Queue capacity is exactly depth;
    at most one additional batch may be in preparation or waiting to enqueue.

    Optional ``cancel_callback()`` aborts pending I/O on early close of a live
    producer. It runs at most once, outside the queue condition and before join,
    and must return promptly. It may irreversibly disable the caller's client;
    complete consumption (including epoch boundaries) never invokes it.

    ``metrics`` is a thread-safe snapshot. Requested counts callback starts;
    consumed counts successful deliveries. Lookup/decode sums are supplied by
    completed callbacks, including unconsumed lookahead. Queue wait metrics count
    time blocked on empty/full conditions (including wake/reacquire); consumer
    wait counts obtaining the next item, including inline preparation
    at depth zero. Failed next() waits are also counted; terminal exhaustion is
    excluded. Occupancy sampling is described by meanQueueDepthDefinition.
    """

    def __init__(self, schedule: Iterable, prepare: Callable[[Any], Any],
                 prefetch_depth: int = 0, *, metrics_callback=None, cancel_callback=None,
                 join_timeout: float = 35.0):
        if isinstance(prefetch_depth, bool) or not isinstance(prefetch_depth, int) or prefetch_depth < 0:
            raise ValueError("prefetch_depth must be a non-negative integer")
        if not math.isfinite(join_timeout) or join_timeout <= 0:
            raise ValueError("join_timeout must be positive and finite")
        self._schedule = tuple(schedule)
        self._prepare = prepare
        self._metrics_callback = metrics_callback
        self._cancel_callback = cancel_callback
        self._depth = prefetch_depth
        self._join_timeout = join_timeout
        self._condition = threading.Condition()
        self._queue = Queue(maxsize=prefetch_depth) if prefetch_depth else None
        self._ready_depth = 0
        self._counters = merge_prefetch_metrics([], prefetch_depth)
        self._closed = False
        self._done = False
        self._next_index = 0
        self._thread = None
        if prefetch_depth:
            self._thread = threading.Thread(target=self._produce, name="aether-batch-prefetch", daemon=True)
            self._thread.start()

    @property
    def metrics(self):
        with self._condition:
            return merge_prefetch_metrics([self._counters], self._depth)

    @property
    def worker_alive(self):
        return self._thread is not None and self._thread.is_alive()

    def _sample_depth(self):
        depth = self._ready_depth
        self._counters["queueDepthSamples"] += 1
        self._counters["queueDepthTotal"] += depth
        self._counters["maxQueueDepth"] = max(self._counters["maxQueueDepth"], depth)

    def _prepare_one(self, index, entry):
        with self._condition:
            if self._closed:
                return None
            self._counters["batchesRequested"] += 1
        prepared = self._prepare(entry)
        if not isinstance(prepared, PreparedBatch):
            timing = self._metrics_callback(prepared) if self._metrics_callback else {}
            prepared = PreparedBatch(prepared, timing.get("lookup_ns", 0), timing.get("decode_ns", 0))
        with self._condition:
            self._counters["prefetchLookupNs"] += prepared.lookup_ns
            self._counters["prefetchDecodeNs"] += prepared.decode_ns
        return PrefetchedBatch(index, entry, prepared.value)

    def _enqueue(self, item):
        with self._condition:
            if self._queue.full() and not self._closed:
                started = perf_counter_ns()
                try:
                    self._condition.wait_for(lambda: self._closed or not self._queue.full())
                finally:
                    self._counters["queueFullWaitNs"] += perf_counter_ns() - started
            if self._closed:
                return False
            self._queue.put_nowait(item)
            if isinstance(item, PrefetchedBatch):
                self._ready_depth += 1
                self._sample_depth()
            self._condition.notify_all()
            return True

    def _produce(self):
        try:
            for index, entry in enumerate(self._schedule):
                try:
                    item = self._prepare_one(index, entry)
                except BaseException as error:
                    self._enqueue(PrefetchFailure(index, error))
                    return
                if item is None or not self._enqueue(item):
                    return
        finally:
            with self._condition:
                self._done = True
                self._condition.notify_all()

    def __iter__(self):
        return self

    def __next__(self):
        started = perf_counter_ns()
        if self._depth == 0:
            if self._closed or self._next_index == len(self._schedule):
                self.close()
                raise StopIteration
            try:
                item = self._prepare_one(self._next_index, self._schedule[self._next_index])
            except BaseException as error:
                with self._condition:
                    self._counters["consumerWaitNs"] += perf_counter_ns() - started
                self.close()
                if isinstance(error, StopIteration):
                    raise RuntimeError("prepare raised StopIteration before schedule exhaustion") from error
                raise
        else:
            with self._condition:
                if not self._closed and self._queue.empty() and not self._done:
                    wait_started = perf_counter_ns()
                    try:
                        self._condition.wait_for(lambda: self._closed or not self._queue.empty() or self._done)
                    finally:
                        self._counters["queueEmptyWaitNs"] += perf_counter_ns() - wait_started
                item = None
                if not self._closed and not self._queue.empty():
                    item = self._queue.get_nowait()
                    self._queue.task_done()
                    if isinstance(item, PrefetchedBatch):
                        self._ready_depth -= 1
                        self._sample_depth()
                    self._condition.notify_all()
        if item is None:
            self.close()
            raise StopIteration
        consumer_wait_ns = perf_counter_ns() - started
        with self._condition:
            self._counters["consumerWaitNs"] += consumer_wait_ns
        if isinstance(item, PrefetchFailure):
            self.close()
            if isinstance(item.exception, StopIteration):
                raise RuntimeError("prepare raised StopIteration before schedule exhaustion") from item.exception
            raise item.exception
        if item.batchIndex != self._next_index:
            self.close()
            raise RuntimeError(f"prefetch batch order mismatch: expected {self._next_index}, got {item.batchIndex}")
        self._next_index += 1
        with self._condition:
            self._counters["batchesConsumed"] += 1
        return replace(item, consumerWaitNs=consumer_wait_ns)

    def close(self):
        """Cancel queued work, wake waiters and join; safe to call repeatedly.

        A timeout raises instead of silently abandoning a live worker. Calling
        close again retries the join. Early cancellation is invoked at most once;
        even if it raises, close still attempts to join and drain the queue.
        The callback's client remains caller-owned.
        """
        with self._condition:
            cancel_callback = (
                self._cancel_callback
                if not self._closed and self._counters["batchesConsumed"] < len(self._schedule)
                and self.worker_alive else None
            )
            self._closed = True
            self._condition.notify_all()
        try:
            if cancel_callback is not None:
                cancel_callback()
        finally:
            if self._thread is not None:
                self._thread.join(timeout=self._join_timeout)
                if self._thread.is_alive():
                    raise RuntimeError("batch producer did not stop after cancellation")
            with self._condition:
                if self._queue is not None:
                    while not self._queue.empty():
                        self._queue.get_nowait()
                        self._queue.task_done()
                    self._ready_depth = 0
                self._schedule = ()
                self._prepare = None
                self._metrics_callback = None
                self._cancel_callback = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()
