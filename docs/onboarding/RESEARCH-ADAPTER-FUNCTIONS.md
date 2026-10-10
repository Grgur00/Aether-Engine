# Research MONAI Comparison and Adapter Functions

[Function index](FUNCTION-INDEX.md) | [Longitudinal worker](LONGITUDINAL-WORKER-FUNCTIONS.md) | [Research tooling](EXPERIMENTS-AND-PROFILING.md)

Source: [monai_comparison.py](../../scripts/monai_comparison.py). All 16 explicit
functions are covered, plus the nested Aether identity lambda. This is the
older two-version exploratory comparison and its reusable dataset adapters, not
the five-version H2 confirmatory controller. Imports load NumPy, PyTorch, MONAI,
the benchmark workload and both cache clients. Missing optional dependencies can
fail import before argument parsing. The module sets `CUBLAS_WORKSPACE_CONFIG`
only when absent; it does not override a caller's existing setting.

## Architecture

```text
declared source identity + canonical transform descriptor
    -> transform.key -> durable cache adapter
    -> Aether dataset / incremental mmap / MONAI file cache / MONAI LMDB
    -> identical float16 images and uint8 masks
    -> scan admission -> float32 batch -> augmentation -> model training
    -> compaction drain -> untimed readback digest -> close
```

The two-version GPU workload grows from 1430 to 1505 sources, adding 75 while
retaining 1430 identities. V1 populates a store; V2 trains for 20 epochs. The
primary comparison is V2 preparation plus training and drain, **not** the entire
initial-plus-update lifecycle. Model setup/warmup, daemon startup, validation and
close are excluded from `measure_stage.totalMs`. A separately reported
`fullLifecycleMs` adds startup and both stage totals, but still does not include
those excluded costs. Do not substitute either endpoint for longitudinal/H2.

## Transform and Mmap Functions

| Function | Behavior and boundaries |
| --- | --- |
| `CanonicalTransform.__init__(args)` | Stores the argument object by reference, starts a per-instance call counter and constructs a descriptor containing resize, preprocessing passes, output dtypes, pipeline/OCT version and normalization scale/offset. Descriptor values are captured at construction; mutating args afterward can change computation without automatically refreshing identity. |
| `CanonicalTransform.__call__(item)` | Increments calls before delegating to workload preprocessing. Converts image to NumPy float16 and mask to uint8, then wraps them with `torch.from_numpy`. A failing preprocess still counts as a call. No random training augmentation is cached here. |
| `CanonicalTransform.key(item)` | Hashes sorted-key JSON of source identity and transform descriptor, returning lowercase SHA-256 hexadecimal as ASCII bytes. Uses the default JSON formatting, not the compact shared evidence digest. Paths and row position are excluded. Including transform identity here is deliberate because the LMDB adapter does not apply `hash_transform` to database keys. |
| `MmapDataset.__init__(data, transform, directory)` | Retains data/transform, opens `PersistentMmapStore` with durability enabled, and constructs a TensorDictCodec. The store owns files/mappings; construction can fail. This is a benchmark adapter, not the high-level provenance store. |
| `MmapDataset.get_batch(indices)` | Computes ordered string keys, looks up payloads, transforms and encodes every missing occurrence, then calls `put_many` once. Builds a missing-payload lookup and decodes outputs in requested order. Existing payloads skip their four-byte stored envelope with `payload[4:]`; new payloads already contain codec bytes. No deduplication of repeated missing indices before preprocessing, no all-or-nothing transform/publication guarantee, and no separate bounds check beyond normal indexing. |
| `MmapDataset.close()` | Delegates store closure. Does not delete cached artifacts; repeated-close behavior belongs to the store implementation. |

## Dataset and Observation Functions

| Function | Behavior and boundaries |
| --- | --- |
| `dataset(backend, data, transform, directory, port=None)` | Selects one of four adapters. Aether uses namespace monai-pilot-v1, an explicitly constructed low-level client, the source callback and captured transform descriptor. Mmap uses the local adapter. MONAI PersistentDataset uses directory/hash_func; LMDB uses the same identity and a map-size estimate of max(16 MiB, twice sample count times resize squared times three), with progress disabled. LMDB can populate during construction. Unknown backend raises ValueError. Actual framework allocation/growth and client ownership belong to those implementations. |
| `dataset.<lambda>(item, index)` | Returns only the row's source_identity; ignores dataset position. A missing identity raises KeyError. This callback is not a raw-image hashing function. |
| `batches(count, batch_size)` | Materializes contiguous ordered index lists, retaining a short final batch. Nonpositive count can yield no batches; zero step raises, and negative step is not validated as a meaningful training configuration. Memory grows with sample count; it is not a lazy shuffled sampler. |
| `fetch(ds, indices)` | Dispatches to get_batch when the attribute exists, otherwise indexes one item at a time in order. Does not test callability, normalize outputs, suppress errors or deduplicate indices. |
| `close(ds)` | Calls close only when that attribute exists. No framework-specific LMDB teardown is added here; lack of a close method means no operation. Errors propagate. |
| `tensor_digest(items)` | Streams values in caller order; for image then mask, hashes name/dtype/shape representation and detached CPU-contiguous bytes. Includes tensor metadata, unlike model_hash in the longitudinal worker. May copy/transfer/synchronize tensors; does not hash all dictionary fields, source paths, transform identity or cache metadata. |
| `disk_usage(directory)` | Enumerates regular-file paths recursively, stats them, sums logical bytes/file count and allocated st_blocks times 512 when available on every file. Empty directory gives zero allocated bytes by vacuous all; any nonempty unsupported stat yields None. No owner/path containment guard, symlink rejection, lock or race handling; sampler callers handle selected stat errors. This is occupancy, not bytes written. |
| `drain(port)` | None port returns None. Otherwise opens a client, waits for compaction and rejects false drained or a nonzero failure count. Does not independently require state IDLE or zero debt; longitudinal strict_drain adds those checks. Missing report fields can raise ordinary lookup errors. |

## Measurement and Controller Functions

| Function | Behavior and boundaries |
| --- | --- |
| `measure_stage(backend, data, args, directory, device, reference, daemon=None, train=False)` | Creates a fresh transform/ordered batches. Optional seeded model/optimizer/warmup occurs before the stage timer. Starts GPU sampling and process snapshots, then times dataset open, complete scan, admission drain, optional synchronous epochs and final drain. Records preparation inclusive of its drain; stops total timing before observations/readback. Validates tensor digest and no additional readback preprocessing, hashes final model state when training, and returns metrics. Finally stops sampling and closes a constructed dataset. Close errors can mask body errors. Does not enforce expected admission counts or pairing by itself; main supplies those checks. |
| `paired_summary(blocks)` | Computes baseline V2 total divided by Aether V2 total, geometric means and a descriptive log-scale t interval for multiple blocks. One block has no interval. Reports epoch cumulative ratios at 1/5/10/20 when present in the first Aether block. No explicit empty/positive/finite input validation, identity checks or significance test; empty input and inconsistent epochs can raise, invalid arithmetic can warn/produce nonfinite output rejected later by JSON publication. |
| `main(argv=None)` | Parses two-version config, positive epochs/repeats and prefetch restricted to zero. GPU settings require exactly five blocks/20 epochs, MONAI 1.6.0 and LMDB 2.1.1, CUDA and append75 dimensions. CPU fixture allows smaller runs but still pins framework versions. Loads actual sources, checks retained identity set and reusable count, binds protocol/campaign, computes untimed references, shuffles four backend lifecycles with seed 20260925 plus block index, opens an owned workspace, and runs initial population plus V2 training in one backend service context. Checks expected preprocess counts and paired final models, saves backend/paired receipts and descriptive summary. No resume option or automatic technical retry. |

`measure_stage` converts fetched cached tensors to stacked float32 arrays, applies
deterministic seeded augmentation per batch/epoch, transfers inputs/targets, clears
gradients, computes BCE-with-logits, backpropagates and steps AdamW. Each batch
synchronizes the device before its loss is recorded. Input-wait milliseconds
cover fetch/stack/conversion/augmentation, not device transfer or direct GPU idle.
Training hit counts are inferred from sample count and epochs; transform-call
differences are separately reported. Readback checks dtype/shape/value equality,
not complete backend durability equivalence.

`main` writes each backend result inside the service context, before daemon exit.
The later paired receipt adds a final-model equality check but does not compare
initial-model hashes as the longitudinal runner does. Earlier backend receipts
can survive a later failure. Framework file caches do not gain Aether's fsync
guarantee because outputs have equal tensors.

## Verification Scope

[MONAI integration tests](../../scripts/tests/test_monai_comparison.py) exercise
real mmap, PersistentDataset and LMDB reuse/invalidation on tiny local images.
[Workload documentation contracts](../../scripts/tests/test_longitudinal_documented_contracts.py)
check selected helpers and CPU worker behavior. Optional Java tests and real GPU
experiments are separate. These entries cover this module, not every function in
the larger benchmark_gpu_segmentation.py dependency.
