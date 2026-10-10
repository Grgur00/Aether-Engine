# Python Dataset Integration Functions

[Function index](FUNCTION-INDEX.md) | [Transform cache](PYTHON-TRANSFORM-FUNCTIONS.md) | [ML architecture](TRAINING-CACHE-AND-PYTHON.md)

This reference covers every explicit function in
[dataset.py](../../clients/python/aether_ml/dataset.py). The higher-level
`AetherDataset` is an indexable adapter, not a training loop, a persistent daemon
owner, or the separately frozen H2 research dataset implementation.

Import scope matters: this is `aether_ml.AetherDataset`. The lower-level
`aether_training_cache` package also exports an AetherDataset from its ml.py;
that is a different implementation, not an alias for this adapter.

## Pipeline and Cache Boundary

```text
source dataset[index]
  -> identity_fn(full source, index)
  -> optional cache_selector(full source)
  -> deterministic_transform(selected input)
  -> encode / cache publication / decode
  -> optional merge_cached_artifact(full source, decoded artifact)
  -> optional random_transform(merged result)
  -> consumer / DataLoader collation
```

Only deterministic-transform output is cached. The identity is computed from the
full source before optional selection. Merge and random augmentation execute again
for every normal fetch, including cache hits. Keeping label/metadata outside a
cached image is possible through selector/merge, but the caller must choose an
identity covering everything that affects the deterministic result.

The wrapper does not validate determinism, seed random augmentation, synchronize
workers, collate samples, transfer tensors to a GPU, or decide which source-file
changes invalidate identity. It delegates artifact serialization, reuse, and
publication to AetherTransformCache.

## Selected Transform Functions

| Function | Behavior |
| --- | --- |
| `_SelectedTransform.__init__(transform, selector)` | Stores both callables without evaluating samples or validating their return types. |
| `_SelectedTransform.__call__(source)` | Passes selector(source) to transform when a selector exists; otherwise passes the original full source. Exceptions propagate. |

`AetherDataset` always wraps the supplied transform in _SelectedTransform. That
wrapper belongs to aether_ml, not the supported torchvision/monai automatic
fingerprint namespaces. Consequently, the current dataset constructor needs an
**explicit transform_identity**, even if deterministic_transform itself is a
recognized framework transform. Its default None does not automatically unwrap
and fingerprint the underlying transform. Selector semantics are not separately
fingerprinted; include them in the explicit identity when they affect output.

## Dataset Construction and Fetch Functions

| Function | Inputs, outputs, and boundaries |
| --- | --- |
| `AetherDataset.__init__(dataset, deterministic_transform, ...)` | Requires __len__ and __getitem__ attributes, explicit identity_fn, and cache_selector when merge_cached_artifact is supplied. Stores source/augmentation/merge callbacks and constructs AetherTransformCache around _SelectedTransform. Attribute checks do not prove those dataset methods work; callback failures propagate when used. |
| `AetherDataset.__len__()` | Delegates to len(source dataset); does not maintain an independent version length. |
| `AetherDataset.__getitem__(index)` | Fetches one full source, computes identity, obtains deterministic cached result, optionally merges with that source, then optionally augments. Source index exceptions and callback/codec/cache failures propagate. |
| `AetherDataset.__getitems__(indices)` | PyTorch batch-fetch hook; delegates to get_batch. Returns a list of samples for downstream collation, not an already collated model batch. |
| `AetherDataset.get_batch(indices)` | Reads sources in requested order, pairs each with identity_for(source,index), and makes one cache batch call. Merges and augments results in order. The cache splits requests exceeding 4,096 entries, so one wrapper call does not guarantee one network request. |

Repeated indices remain repeated results and receive separate augmentation calls.
Distinct-key miss work is coalesced inside the cache chunk; decoding is still per
requested entry. Full source objects are retained during merge. No defensive deep
copy protects the source object from selector/transform/merge mutation.

For a batch, deterministic cache work completes before the merge list and random
augmentation list are evaluated. A later merge/augmentation failure does not undo
published artifacts. There is no dataset-wide transactional fetch or epoch lease.

## Planning and Population Functions

| Function | Behavior |
| --- | --- |
| `AetherDataset.plan(estimated_compute_seconds_per_sample)` | Visits every dataset index to derive identities, then delegates presence planning. Does not run selector, deterministic transform, merge, or augmentation; reading the source dataset itself may still perform I/O. |
| `AetherDataset.populate(workers)` | Rejects workers < 1, snapshots cache stats, visits all indices, and returns total plus hits/misses counter differences. One worker uses a batched cache call; more workers use ThreadPoolExecutor with per-index requests. Neither path applies merge or random augmentation. |
| `AetherDataset.populate.populate_one(index)` | Nested thread-task helper: reads one source, derives its identity, and calls cache on it. The decoded result is discarded. Exceptions are surfaced while consuming executor.map. |

Single-worker population materializes all entries before the cache splits them
into chunks. The list comprehension reads dataset[index] twice: once for the
source and once for identity_for. Source datasets with side effects, randomness,
or changing data can therefore derive an identity from a different object than
the one transformed. The threaded helper reads once per task.

Multiple workers here means threads sharing this wrapper/client/counters, not
processes or an offline bulk writer. The wrapper provides no per-key in-flight
deduplication: concurrent tasks can both miss and compute the same identity.
Client exchange serialization does not make the entire lookup/compute/publish
sequence atomic. Failed tasks can leave artifacts from successful tasks behind.

The population report counts requested-entry hit/miss deltas, not independently
verified distinct publications. Statistics keys are lazy: a missing hits or misses
key is accessed directly in the final report. Disabled or empty population can
raise KeyError instead of yielding a report. Publication fallback can count a
computed miss without proving it became reusable. These limitations are current
implementation behavior, not claims that populate certifies a complete store.

Planning similarly checks presence without reserving values or validating decoded
payloads. Estimated avoided work is a caller-supplied model, not measured speedup.

## Metrics and Lifetime Functions

| Function | Behavior |
| --- | --- |
| `AetherDataset.stats()` | Delegates wrapper-level cache counters/timing summaries; no dataset length, per-epoch statistics, or server poll is added. |
| `AetherDataset.reproducibility_metadata()` | Delegates namespace/schema/transform description metadata. Does not attest source dataset contents, random seeds, model weights, or the frozen research protocol. |
| `AetherDataset.close()` | Closes the transform cache's current client. Does not close the source dataset, clear stored artifacts, or stop the Java daemon. |
| `AetherDataset.__enter__()` | Returns the dataset without connecting or prepopulating. |
| `AetherDataset.__exit__(exc_type, exc_value, traceback)` | Calls close; does not suppress exceptions or invoke source-dataset cleanup. |

Use a client factory for worker-process connection ownership. Source dataset,
callbacks, and codec must also satisfy the chosen DataLoader worker/pickling
requirements; this adapter does not make an arbitrary lambda or live socket
spawn-safe. The transform-cache PID handling is described separately, including
the limitations of passing an already-created client.

## Test Evidence and Next Reading

[test_aether_ml.py](../../clients/python/tests/test_aether_ml.py) exercises
augmentation after reuse, ordered batch fetch, selector/merge metadata retention,
population without augmentation, explicit source identities, one-argument identity
callbacks, and optional PyTorch DataLoader batching/worker counts. In-memory
client fixtures do not establish real-daemon thread safety, persistence, GPU
throughput, or correctness for every user callback.

For an architectural exercise, trace image-only caching with labels merged outside
the cache. Change the image content, preprocessing, label, and random augmentation
independently and explain which change must invalidate the artifact key. Then
compare normal fetch with populate: merge/augmentation are absent in population,
but encode/decode and publication still belong to the cache path.

Lifecycle factories, framework-specific wrappers, metrics exporters, and research
drivers require their own individual-function references. This page does not
claim those adjacent files are exhaustively documented.
