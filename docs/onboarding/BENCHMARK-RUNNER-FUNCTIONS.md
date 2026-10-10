# Benchmark Entry Points, Run Control, and Validity Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark data](BENCHMARK-DATA-FUNCTIONS.md) | [Model and training](BENCHMARK-TRAINING-FUNCTIONS.md) | [Backend ownership](BENCHMARK-BACKEND-FUNCTIONS.md)

Source: [benchmark_gpu_segmentation.py](../../clients/python/benchmark_gpu_segmentation.py).
This reference covers **13 explicit declarations**, including the three
LazyReferenceSequence methods. It is not a complete benchmark reference; result
aggregation, ratios and statistical/accounting helpers remain separate.

## Control Architecture

```text
main -> parse_args -> normalize CLI defaults -> validate_args
     -> run_benchmark -> environment/validity report
          -> population-only: sources/reference/context -> populate -> close
          -> unsupported device: skipped report (no training CPU fallback)
          -> GPU: smoke -> repeated run_training_once -> aggregate -> report
     -> write JSON + print summary

run_training_once -> sources + reference + checksums -> initial model state
                  -> measured context + separate small validation context
                  -> shuffled backend order: fresh model/optimizer + warmup/train
                  -> Java background drain -> parity/reuse/counter invariants
                  -> comparisons/accounting -> close measured context
```

This is the original benchmark controller, not the H2 paired longitudinal runner.
It does not schedule MONAI baselines, freeze a source inventory, start one JVM for
V0-V4, enforce a balanced 24-block order, or perform crash experiments. Shared
functions may also be called by other workers under different timing protocols.
Do not infer the frozen campaign's implementation solely from this entry point.

## Entry Point and Argument Functions

| Function | Behavior and boundaries |
| --- | --- |
| `main()` | Parses process arguments, runs the benchmark, creates output parents, writes indented UTF-8 JSON plus newline and prints a short report summary/path. Write is not atomic and overwrites an existing output. Exceptions propagate before/while writing; it does not serialize a structured failure report or choose a nonzero exit code for a successfully returned skipped report. |
| `parse_args(argv=None)` | Defines CLI options for workload/device, preparation, training, backends, storage, workers/prefetch and output. Requires raw,aether,mmap and optionally ram, with no duplicate/unknown tokens; comma tokens are not whitespace-trimmed. Maps backend labels, defaults class count to 80 for COCO and 1000 otherwise, lets oct5k-image-size override resize, resolves auto augmentation to light for OCT5K and none otherwise, records invocation tokens, validates and returns Namespace. Argparse syntax/choice errors exit; validate_args errors raise ValueError. Explicit argv is expected to be a reusable argument sequence. |
| `validate_args(args)` | Requires one Java run (external matrix owns isolation), positive sample/batch/epoch/run counts, dimensions at least four, nonnegative warmup/measured steps, finite affine normalization, estimated reference/RAM memory within max_reference_bytes, positive preprocess passes, nonnegative prefetch/interval/workers and Java for worker mode. Checks percentage ranges/cache modes, existing input directory and CSV-file manifest path for real datasets, plus OCT image size. Returns None or raises ValueError. Does not parse the manifest, verify GPU count/device/port/cache freshness, reserve memory, validate positive class count, or require finite sample interval. CLI choices supply other bounds not rechecked here. |

The memory limit is a formula, not measurement of peak working set, allocator
fragmentation, model/optimizer state or Java memory. With RAM it estimates full
reference plus payload copies and worker copies; without RAM it estimates batch
buffers scaled by workers/prefetch. An explicit larger limit does not establish
that the host has that memory. Manifest existence is not content/provenance
verification. Cache directory safety belongs to the caller; fresh mode can delete
directories as described in the backend reference.

## Benchmark and Reference Functions

| Function | Behavior and boundaries |
| --- | --- |
| `run_benchmark(args)` | Sets CUBLAS_WORKSPACE_CONFIG only when unset, probes NumPy/accelerator, and builds environment/configuration/storage/validity report before deciding mode. Population-only requires NumPy, constructs sources/reference/context, executes requested Aether and/or mmap population and closes context; returns PASSED/allPassed True without GPU smoke/training/checksum-equivalence gates. Training unsupported reason returns SKIPPED_UNSUPPORTED_ACCELERATOR with allPassed False. Otherwise requires requested GPU count, selects cuda (including ROCm API), smokes device, runs args.runs sequentially, aggregates and marks success. Does not call validate_args itself, check every validity flag before success, isolate a daemon across runs, or turn exceptions into a report. |
| `LazyReferenceSequence.__init__(self, args, sources, np)` | Retains the arguments/source list/NumPy module for later preparation. Does not copy/snapshot them, prepare a sample or cache outputs; subsequent mutations affect later accesses. |
| `LazyReferenceSequence.__len__(self)` | Returns the current source sequence length, not args.samples or a fixed constructor-time count. |
| `LazyReferenceSequence.__getitem__(self, index)` | Slice expands normalized indices and returns a materialized list. Scalar indexes the underlying sources then preprocesses and performs artifact quantization on every access. Source indexing supplies negative-index/error behavior. Repeated access recomputes rather than caching; no stable file-byte snapshot. |
| `make_reference(args, sources, np)` | Constructs a LazyReferenceSequence and materializes it only when RAM_READY is selected. Otherwise returns the lazy object. Subsequent checksum/validation/context passes can repeatedly prepare inputs; bounded reference storage does not mean no preflight work or no batch/payload copies. |

Population-only still constructs both stores and any selected RAM payload list.
It may read/create/delete unrelated-to-the-selected-population backend state as
part of context construction. Its successful flag is not evidence of GPU timing,
backend training parity, clinical quality or crash safety. Training reports also
explicitly label page-cache state uncontrolled: reference/presence preflight reads
precede measurement. A crashRecoveryReference string is a pointer to another
experiment, not an executed crash test.

## Per-Run Controller

| Function | Behavior and boundaries |
| --- | --- |
| `run_training_once(args, torch, np, device, run_index)` | Uses run_seed=args.seed+run_index for model/training policy but loads sources using original args. Computes/checks reference digests, captures shared pre-warmup model state/metadata and shuffles backend names with a dedicated seeded Random. Creates measured context, then a separate at-most-eight-sample validation context with unique Java namespace, temporary fresh paths and zero explicit hit ratio; closes validation context after equivalence. For each backend reseeds, creates/restores a model, creates AdamW(lr=1e-3), chooses ImageNet CrossEntropyLoss or BCEWithLogitsLoss, warms/trains and for Java Aether drains compaction/rejects failed drain. Requires identical final model hashes, mmap invariant success, exactly equal initial index sets and Aether dynamics success before returning reports. The measured context is closed in the later training/report finally, not when earlier validation construction/equivalence fails. Model/cache mutations are not rolled back on rejection. |

The validation context is separate from measured data, but copies other arguments:
prepopulate_previous_version is not automatically disabled by setting the explicit
hit ratio to zero. Backend order is shuffled per run, not guaranteed balanced
across a finite collection of runs. Each backend starts with shared model weights
and a fresh optimizer; warmup updates both before measured training. Final state
hash parity does not compare optimizer state, clinical correctness or held-out
generalization. Initial source generation does not automatically change with the
incremented run seed.

Java background drain happens after measured backend training and is reported
separately as totalWithBackgroundDrainMs; it is not silently included in totalMs.
It waits for reported background completion, not an independently proven power-loss
barrier. Constructor/validation failures need caller-level resource care: the
existence of later finally blocks is not an all-stage cleanup guarantee.

## Validity and Equivalence Functions

| Function | Behavior and boundaries |
| --- | --- |
| `validity_checks(args, accelerator, numpy_status)` | Returns device/backend/name/NumPy flags, cache_invalidation_self_test and timing descriptions. noCpuFallback is an unconditional policy flag and timing synchronization fields are descriptions, not measurements performed here. It does not combine all flags into a passed gate or run GPU events/cache operations. Population-only can have failed accelerator flags while returning allPassed True. |
| `cache_invalidation_self_test(args)` | Copies deterministic descriptors, changes resize and named normalization/implementation/transform/denoise/artifact-version fields, hashes synthetic source descriptors, and requires stability for identical descriptors and inequality for mutations. Returns individual flags plus all-flags passed. Some mutations merely add/change descriptor fields; no real preprocessing, cache lookup, eviction, regeneration or implementation-change detector is run. |
| `test_cache_key(parameters, *, source_identity, source_hash)` | SHA-256 hashes sorted JSON with fixed sample_id=oct-validation-sample, supplied source identity/hash and deterministic parameters. Does not read or validate source bytes and is a descriptor test helper, not a pytest test entry or a replacement for BackendContext.cache_key's actual sample IDs. Non-JSON-serializable parameters raise. |
| `validate_backend_equivalence(context, reference)` | Stacks at most the first eight reference samples, computes raw and step-zero/epoch-zero augmented expected values/checksums, then obtains that prefix from every selected backend. Requires exact shape, dtype, array equality, checksum and augmented equality; raises RuntimeError on mismatch, otherwise returns per-backend details. Backend.batch may populate caches during this check. Does not compare the full dataset, multiple augmentation seeds/epochs, training outcomes, crash behavior or performance; empty reference fails in stack_values rather than passing vacuously. |

The digest helpers used in equivalence hash raw array bytes; explicit shape/dtype
comparisons are separate guards. Exact checks can reject NaNs even when the same
NaN bytes occur on both sides. Training's final-state parity and cache-dynamics
checks add different evidence; a descriptor self-test alone is not enough.

## Verification and Remaining Coverage

Qualified AST checks cover these 13 declarations. Local tests exercise parsing,
guard failures, lazy recomputation, descriptor checks, supported/unsupported and
population-only report boundaries using doubles, plus a tiny real CPU-only call
to the per-run controller with disposable caches. Calling this helper on CPU for
testing does not change the entry point's no-performance-CPU-fallback policy.

The function index links all partitions, jointly covering **138 of 138 explicit
benchmark declarations**. This is scoped entry coverage, not completion of the
repository overview or verification of every runtime/hardware combination.
