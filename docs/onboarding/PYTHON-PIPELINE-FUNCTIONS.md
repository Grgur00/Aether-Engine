# Python Loading, Workers, and Prefetch Functions

[Function index](FUNCTION-INDEX.md) | [Mapped views](PYTHON-MAPPING-FUNCTIONS.md) | [Store adapters](PYTHON-STORE-ADAPTER-FUNCTIONS.md)

This reference follows three different loading mechanisms, rather than treating
every `AetherDataLoader` name as the same API. It covers all 25 explicit functions
in [dataset.py](../../clients/python/aether_training_cache/dataset.py),
[loader_workers.py](../../clients/python/aether_training_cache/loader_workers.py),
and [prefetch.py](../../clients/python/aether_training_cache/prefetch.py), including
private properties and the nested percentile helper. It also describes the two
functions in the benchmark's `prepared_batches` subtree. The rest of that large
benchmark module remains outside this page's declaration inventory.

## Select the Actual Loading Path

| Surface | Preparation and ownership | Result |
| --- | --- | --- |
| `aether_training_cache.dataset.AetherDataLoader` | Caller-owned cache/segment registry; optional pool of threads sharing them | Lists of mapped reference views, or explicitly pinned tensor copies |
| `BoundedPrefetchIterator` | Caller-owned preparation callback/client; exactly one producer at positive depth | Ordered PrefetchedBatch objects carrying the original task and payload |
| `loader_workers.worker_batches` | Benchmark-specific Torch DataLoader, spawned processes, worker-local Java clients and mmap stores | Prepared benchmark batches plus process/transport observations |
| `aether_ml.torch.AetherDataLoader` | Delegates to the framework's DataLoader | Framework-defined collation and worker semantics |
| `aetherml.AetherDataLoader` | Separate filesystem/provenance prototype | Not the Java-backed paths in this reference |

The higher-level [framework reference](PYTHON-FRAMEWORK-FUNCTIONS.md) covers the
Torch facade. The [dataset reference](PYTHON-DATASET-FUNCTIONS.md) covers deterministic
transform reuse; that is a different boundary from segment-reference loading.

```text
Reference loader: keys -> GET_MANY_REFS -> local registry views -> optional pinned copy

Benchmark, workers = 0:
  depth = 0 -> yield schedule metadata -> train_step prepares its own batch
  depth > 0 -> caller materializes one epoch -> one producer -> bounded ready queue
               -> ordered consumer -> join/close -> next epoch

Benchmark, workers > 0:
  one epoch schedule -> spawn DataLoader -> worker-local context/client/mmap
  -> prepared result and per-worker counters -> parent aggregation -> next epoch
```

## Reference-Batch Loader Functions

The package exports `AetherDataLoader` and its literal alias `AetherBatchLoader`
from dataset.py. This loader expects segment references; it is not the packed
inline-value loader used by every ML or benchmark path.

| Declaration | Behavior and boundary |
| --- | --- |
| `AetherDataLoader.__init__(cache, keys, mapped_segments, batch_size=32, dtype="int32", shape=None, workers=0, prefetch_batches=0, pin_memory=False)` | Materializes keys, retains caller-owned cache/registry, stores batching/tensor settings and initializes counters. Rejects negative worker/prefetch values, but does not validate positive/integer batch size or reject Boolean/fractional worker settings itself. Does not open a socket or mapping here. |
| `AetherDataLoader._load(batch_keys)` | Requests references for the batch, maps present references in requested order, and silently omits keys absent from the response. With pinning enabled, converts each view to a CPU tensor and then copies into pinned storage. On normal completion accumulates elapsed latency and increments samples by **requested** key count, not delivered view count. Failed loads do not record these counters and have no local view-cleanup finally block. |
| `AetherDataLoader.__iter__()` | Materializes all key slices. With zero workers prepares synchronously; otherwise submits `_load` to a ThreadPoolExecutor and yields futures in submission order, not completion order. Submits before checking its pending-future bound. Finally waits for executor shutdown and records this iteration's total duration. It does not close the caller's cache/registry or cancel outstanding requests on early termination. |
| `AetherDataLoader.__len__()` | Returns ceiling key count divided by batch size. Does not fetch keys or account for misses. Invalid batch sizes can raise here or in iteration rather than being rejected at construction. |
| `AetherDataLoader.metrics()` | Sorts successful load latencies, returns accumulated batch/requested-sample counts, their timing sums and percentile observations. Divides accumulated load duration by the most recently finalized iteration duration for inputWaitPercent. Does not reset counters, poll the daemon or lock around concurrent updates. |
| `AetherDataLoader.metrics.percentile(value)` | Nested helper returns zero for no observations; otherwise selects a clamped nearest-rank-style element using the implemented integer expression. No interpolation or separate statistical sampling. |

### Queue and Lifetime Limits

The threaded loader retains up to `max(1, prefetch_batches + 1)` futures after
yielding; it transiently submits one more before waiting for the oldest. With
`prefetch_batches=0` and positive workers it still submits lookahead work. This
is not the exact-depth queue of BoundedPrefetchIterator. The same low-level client
serializes its own requests, and mapped registries have their separate ownership
limitations; a thread count is not evidence of concurrent network throughput.

Views borrow local mapped storage, so keep the registry/file lifetime valid and
release views before cleanup. Pinning makes a tensor copy; it does not send data
to CUDA. No missing-value exception guarantees one returned result per input key.
Duplicate requested keys can generate repeated views of the same reference.

On early exit, explicitly close the iterator or ensure its generator is finalized.
Executor shutdown waits for submitted work without a timeout and does not invoke
the low-level client's cancellation API. A blocked load can therefore delay cleanup.
After several iterations, latency/sample counters remain cumulative while total
duration is replaced by the last iteration. Parallel load-duration sums can exceed
wall time, so `inputWaitPercent` is not a bounded measure of training-thread stall.

## Ordered Prefetch Records and Functions

The frozen dataclasses have no explicit methods: `PreparedBatch(value, lookup_ns=0,
decode_ns=0)` reports completed callback stages; `PrefetchedBatch(batchIndex, task,
value, consumerWaitNs=0)` is a successful delivery; `PrefetchFailure(batchIndex,
exception)` transports the original producer error. Frozen fields do not deep-copy
or make the contained task/payload immutable. Timing values are caller-supplied,
not validated as nonnegative integers.

| Declaration | Behavior and boundary |
| --- | --- |
| `merge_prefetch_metrics(snapshots, prefetch_depth)` | Materializes snapshots, sums the declared total counters, takes maximum recorded queue depth and computes mean occupancy from total/samples. Adds the supplied depth and definition string. Does not average epoch means, validate a shared depth, or calculate a time-weighted occupancy integral. Missing counters contribute zero. |
| `BoundedPrefetchIterator.__init__(schedule, prepare, prefetch_depth=0, *, metrics_callback=None, cancel_callback=None, join_timeout=35.0)` | Rejects Boolean/noninteger/negative depth and nonpositive/nonfinite join timeout. Materializes the entire finite schedule into a tuple on the constructing thread, stores callbacks, creates condition/counters and, at positive depth, a queue of exactly that capacity plus one named daemon producer. Depth zero starts no thread and defers preparation to next(). Task objects themselves are not copied. |
| `BoundedPrefetchIterator.metrics()` | Property. Under the condition, constructs a fresh merged snapshot so callers do not mutate internal counters. Derived means are recomputed. No polling of the callback's client or device. |
| `BoundedPrefetchIterator.worker_alive()` | Property reporting whether a producer thread exists and is alive. No producer means false; a done flag alone is not the liveness test. |
| `BoundedPrefetchIterator._sample_depth()` | Private helper samples `_ready_depth` into count/total/max. Callers hold the condition. Counts successful ready payloads only, not failure markers or in-flight preparation. |
| `BoundedPrefetchIterator._prepare_one(index, entry)` | Under the condition, returns None if closed or increments requested count before invoking the callback. Raw payloads are wrapped using optional same-thread metrics extraction; PreparedBatch results bypass that extraction. Adds supplied lookup/decode timings and returns a PrefetchedBatch. A raw payload of None is a valid wrapped payload, not exhaustion. Counters may include completed but unconsumed lookahead. |
| `BoundedPrefetchIterator._enqueue(item)` | Under the condition, waits while a live queue is full, records that blocked duration, returns false after closure, or queues the item and wakes waiters. Only successful PrefetchedBatch objects increment ready depth and occupancy samples; a failure still occupies queue capacity. |
| `BoundedPrefetchIterator._produce()` | Visits the frozen schedule in order with at most one active preparation callback. On callback/metrics BaseException, queues one failure behind earlier successes and stops. Stops on closure/enqueue rejection. Finally sets done and wakes consumers even after producer failure. |
| `BoundedPrefetchIterator.__iter__()` | Returns this single-consumer iterator, not an independent traversal or reset. |
| `BoundedPrefetchIterator.__next__()` | At depth zero prepares the next task inline; otherwise waits for a ready item/failure/done condition and dequeues in order. Records wait time for successful and failed deliveries, but not terminal exhaustion. Re-raises original failures, translates callback StopIteration to RuntimeError, rejects batch-index mismatch, advances successful consumed count, and returns a dataclass replacement with this call's consumerWaitNs. Exhaustion/failure closes the iterator. |
| `BoundedPrefetchIterator.close()` | Marks closed and wakes waiters. On the first early close with a live producer and incomplete consumption, invokes optional cancellation outside the condition, then attempts join in finally. Raises if the producer remains alive after join_timeout; a later close can retry joining. After successful join drains queued items and clears schedule/callback references, preserving counters. Never closes the callback's client by itself. |
| `BoundedPrefetchIterator.__enter__()` | Returns the iterator; positive-depth preparation may already have begun at construction. |
| `BoundedPrefetchIterator.__exit__(exc_type, exc_value, traceback)` | Calls close; does not suppress a body exception. A cancellation/join error can replace the body exception during exit. |

### Cancellation and Measurement

One producer and one consumer are supported. Queue capacity is exactly depth;
one additional batch may be in preparation or waiting to enqueue. The full schedule
tuple and payload sizes are not bounded by that queue capacity. Closing clears queued
references after joining; it does not call a payload-specific release function.

Normal exhaustion, including consumption of the final batch before a terminal
next(), does not call cancellation. Early close calls it at most once and only when
the producer is live. It can irreversibly disable a caller's client; the caller still
owns that client and should close the iterator **before** closing it. If cancellation
raises, joining/draining is still attempted. If join also times out, that error can
replace the cancellation error. Without a cancellation callback, pending I/O/retries
must fit the configured join budget. A daemon thread is not a substitute for joining.

`queueEmptyWaitNs` and `queueFullWaitNs` cover condition waiting, including reacquire.
`consumerWaitNs` covers obtaining an item; at depth zero it includes preparation.
Lookup/decode values come from completed callbacks, including lookahead later
discarded by an early close. `batchesRequested` counts callback starts and
`batchesConsumed` successful deliveries. Queue occupancy is sampled after successful
enqueue/dequeue events, excludes failures/drains/in-flight work and is weighted
by `queueDepthSamples` across epochs, not by elapsed time.

## Spawned Worker Functions

This is benchmark-specific code, not the aether_ml Torch adapter. The worker
uses JavaArtifactStore even though the benchmark also has a separate Python-store
option elsewhere. It should not be described as a general backend-independent
worker factory.

| Declaration | Behavior and boundary |
| --- | --- |
| `identity_collate(value)` | Returns an already prepared item unchanged. With framework batch_size=None, avoids default conversion/collation of this composite result. |
| `PreparedBatchDataset.__init__(context, backend, schedule)` | Shallow-copies args, retains source metadata and mmap root, materializes schedule, retains ram_ready only for RAM_READY, and sets worker context to None. Does not carry the parent's live client/mmap owner in its context field. Retained source/args/RAM contents still must be spawn-serializable. |
| `PreparedBatchDataset.__len__()` | Returns scheduled **batch** count, not sample count or training epochs. |
| `PreparedBatchDataset._open()` | Lazily constructs a BackendContext using __new__ without running its full population constructor. Supplies args/NumPy/sources and worker fields, creates a Java client adapter and a shared mmap store, then registers both closes with atexit and installs the context. Creates both owners even for other selected backends. This path does not run the parent's full daemon identity/durability validation, and partial setup lacks a comprehensive cleanup finally block. |
| `PreparedBatchDataset.__getitem__(index)` | Opens worker context on first fetch, resets protocol/store/mmap counters, obtains the scheduled indices/epoch, snapshots resources around context.batch, and returns preparation duration plus backend, transport, operation and process observations. Framework wait, imports/open/reset/schedule lookup and serialization are outside that preparation duration. Invalid indices may be discovered only after lazy open/reset. |
| `worker_batches(context, backend, schedule)` | Groups consecutive tasks by epoch, creates a fresh PreparedBatchDataset/Torch DataLoader for each group with spawn, batch_size=None, identity collation, configured positive worker count, prefetch_factor=max(1, configured depth) and no persistent workers. Times each next(iterator), aggregates resources by PID, aggregates Aether transport/operations or mmap counters as applicable, then yields indices/epoch/prepared/wait_ms. StopIteration ends a group; other errors propagate. |

Each epoch's worker pool is exhausted before the next is constructed. Grouping
is by adjacent epoch values, not a sort or validation of the supplied schedule;
interleaved epoch IDs would make separate groups. `prefetch_factor` belongs to
Torch's per-worker scheduling, not BoundedPrefetchIterator's queue-depth metric.
Persistent workers are explicitly disabled. There is no explicit worker shutdown
finally block here on an early break; cleanup relies on framework iterator lifetime
and process exit/atexit. Do not infer the single-producer join/cancel guarantees.

Per-worker requests/connections are reset for each batch. Parent aggregation skips
the benchmark protocol's synthetic `connectionsOpened` field and uses transport
observations instead. Resource counter deltas add only when both values are
available; missing values remain null. RSS endpoint/high-water values are replaced
by the latest worker observation, not summed into a measured simultaneous peak.
Parent next() wait includes worker/IPC delay; it is distinct from worker preparation.

## Benchmark Routing Functions

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
These are routing functions from that module, not a complete function reference
for preprocessing, training, scheduling or result analysis.

| Declaration | Behavior and boundary |
| --- | --- |
| `prepared_batches(context, backend, schedule, *, metrics=None)` | Initializes metrics. Routes positive worker counts to worker_batches and marks queue metrics unavailable. With no workers/nonpositive depth, yields schedule metadata with no prepared payload; train_step performs preparation later. With positive depth, freezes one epoch's metadata, constructs an ordered iterator, forwards deliveries and finally closes/merges its metrics before entering the next epoch. For Java AETHER_CACHE only, supplies the client's irreversible pending-request cancellation callback for early exit. |
| `prepared_batches.prepare(task)` | Nested positive-depth callback calls context.batch, returns its batch/counters/elapsed duration as PreparedBatch, and derives nanosecond lookup/decode fields from the existing millisecond stage counters. Aether lookup and mmap read are included in lookup; artifact decode excludes later CPU tensor construction. It does not schedule the next epoch, train or perform CUDA transfer. |

The benchmark's depth-zero wrapper deliberately differs from constructing a
depth-zero BoundedPrefetchIterator directly: the former leaves preparation to
train_step; the latter executes its callback inline on next(). This distinction
preserves the existing synchronous timing boundary. A multiprocess run explicitly
reports queue metrics unavailable rather than inventing zeros.

## Verification and Remaining Boundaries

[test_prefetch.py](../../clients/python/tests/test_prefetch.py) exercises order,
single-producer ownership, lazy depth zero, queue bounds, original error delivery,
StopIteration translation, join/cancellation, weighted metrics, epoch transitions
and fixed-artifact array/CPU tensor parity. These require no GPU/server; mocked
cancellation is not a socket/server integration result.

The [documented-contract tests](../../scripts/tests/test_pipeline_documented_contracts.py)
additionally check reference-loader omissions and
cumulative counters, Java adapter copy/batch limits, and selected worker routing
and resource aggregation with test doubles. Such tests do not launch a Torch
worker process, prove IPC behavior under failure, or establish a GPU speedup.
Source inventory and website tests check entries/rendering; runtime implementation
and the frozen H2 protocol are not changed for this reference.
