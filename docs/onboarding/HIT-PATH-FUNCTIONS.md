# Steady-State Hit Path Diagnostic Functions

[Function index](FUNCTION-INDEX.md) | [Benchmark backend ownership](BENCHMARK-BACKEND-FUNCTIONS.md) | [Research tooling](EXPERIMENTS-AND-PROFILING.md)

Source: [hit_path_profile.py](../../scripts/hit_path_profile.py).
The Java subprocess is documented separately in [Java diagnostic drivers](TRAINING-DRIVER-FUNCTIONS.md).
All **19 explicit declarations**, including nested percentile, batch, export and
latency helpers, are covered. InvalidHitRun is an exception type with no explicit
methods. This tool reports read-only V2 diagnostics, not paper-block results.

## Three-Layer Architecture

```text
validated plan -> subset V2 manifest -> identical artifact encoding
  -> prepopulate durable Java and mmap stores -> drain -> full byte/hash parity
  -> seeded key permutation -> binary Java workload -> seal final memtable
  -> seeded trial order:
       Java database.get loop / Python RPC bytes / mmap local bytes
       CPU full input (decode + stack + normalize) across prefetch depths
  -> hit/idle/activity/order/trace checks -> per-trial and per-repeat summaries
```

Preparation publishes; measured trials must not. The same artifact permutation
is reused across layers and epochs within a repetition. Each Java trial is a
separate process; each Python trial opens its own daemon and persistent workload
connection. These are separately timed cache/JIT states, not one shared experiment
that causally subtracts transport or decoding cost. No GPU compute, simulated
compute delay, training step or dataset revision is performed.

## Hit-Only Gates and Statistical Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `require_hits(values, expected)` | Requires exact list length and no None values; raises InvalidHitRun on missing entries. Empty bytes count as hits. Does not check payload hash, identity, duplicates or expected>0. |
| `require_idle(info)` | Requires state IDLE, debtBytes=0, failed=0, and exact int types (not bool) for four activity counters: flushesCompleted, backgroundCompactionsStarted, completed, failed. Does not separately require nonnegative counters. |
| `require_no_activity(before, after, *, misses=0, publishes=0, traces=())` | Requires idle snapshots, zero supplied misses/publications and unchanged activity counters. For each supplied trace rejects server flush/compaction/scheduling evidence and non-complete outcome. Missing server_trace defaults to empty evidence; does not reject attempt_failed events or independently count missed requests. |
| `distribution(values)` | Sorts input; empty returns None. Returns arithmetic mean, interpolated median/p95/p99 and count. No finite-value validation, confidence interval or weighting. |
| `distribution.percentile(fraction)` | Interpolates between floor/ceil of (n-1)*fraction in the enclosing sorted list. Internal callers use .5/.95/.99; no general fraction-range validation. |
| `summarize(batches, elapsed_ns)` | Sums samples and bytes; reports per-request duration/byte distributions and per-batch duration/sample milliseconds, plus throughput from supplied active-epoch elapsed. Zero elapsed yields None throughput. Batch means are unweighted; a smaller final batch still contributes one distribution observation. Zero-sample batches divide by zero. |
| `numbers(value, minimum)` | Parses comma-separated ints, rejects duplicates or values below minimum, preserves supplied order. Invalid/empty tokens fail int conversion. Does not sort or enforce a maximum; caller applies upper bounds. |

## Plan and Workload Construction

| Function | Behavior and boundaries |
| --- | --- |
| `build_plan(args)` | Rejects confirmatory/resume, workers other than literal '0', GPU-count string other than '1', lifecycle/reuse/size/step overrides and workflow count other than one. Requires positive repetitions/epochs/batch, nonnegative warmup, batch/request sizes <=64, prefetch depths 0..64 and one preprocessing-pass value. Accepts backend set aether/mmap or default raw/aether/mmap/ram, but jobs still compare only Aether/mmap. Reads configured unique real datasets and returns explicit diagnostic/timing/cache scope metadata. |
| `workload_args(plan, dataset, spec, manifest)` | Builds benchmark arguments for the selected V2 manifest/count, split, resize, classes, fixed training-sized batch, epochs and preprocessing. Sets backends raw/aether/mmap, max reference bytes, maximum configured prefetch and augmentation none. Class default is 80 for COCO, otherwise 1000. Parses through the existing benchmark parser; does not train. |
| `write_workload(path, keys)` | Writes big-endian u32 key count, then length-prefixed UTF-8 namespace/sample ID and raw transformation digest per key. No digest-length assertion or parent-directory creation here. Length/count overflow raises packing errors; writing directly can leave a partial file. |
| `jobs(plan, seed)` | Emits Java-database/RPC-byte/local-mmap trials per request size and Aether/mmap full-input trials per prefetch depth, using the fixed training batch. Creates backend-layer-batch-depth IDs, then shuffles with seed XOR 0xAE7. Count is 3*request-size count + 2*depth count. No validation or duplicate-ID guard beyond upstream plan checks. |

build_plan adds trainingBatchSize to requestSizes and sorts them so a three-layer
comparison is available at that size. Backend validation is set-based, so duplicate
backend labels are not separately rejected. Dataset keys must exist in config;
missing spec fields fail later. maxReferenceBytes and seed are passed through.
The optional GPU-count argument constrains the surrounding configuration, not proof
of any device use. sourceClean starts false until run inspects provenance.

## Prepopulation and Java Trials

| Function | Behavior and boundaries |
| --- | --- |
| `prepare(plan, dataset, spec, directory, stores, seed)` | Writes selected V2 manifest, loads sources and derives unique complete keys using BackendContext.cache_key. Preprocesses/encodes each artifact identically into Java and durable mmap in batches of 16, retaining sample ID/byte length/hash metadata. Drains, checks cardinality, reads every artifact for hash/byte parity, shuffles entries and writes keys.bin through Java adapter key conversion. Closes stores/daemon, writes untimed population receipt and returns ordered metadata. |
| `java_trial(stores, directory, job, plan, *, prepare_only=False)` | Launches HitPathBenchmark with prepared Java store/key file, request size, epochs and warmup. prepare_only uses zero measured epochs and seal.json; otherwise requires PASSED and unchanged idle activity, adds job/role/summary and rewrites trial JSON. Uses reported elapsedNs or sum of batch durations fallback. Checked process output goes to target .log; no subprocess timeout. Returns parsed report. |

Preparation validates complete cardinality and byte hashes outside measured trials.
mmap records contain a four-byte prefix removed for parity comparison. The returned
ordered metadata stores hashes, not every full payload. population.wallNs starts
before daemon launch and includes transformation, publication, validation and
shutdown, but excludes earlier manifest/source/key setup. It is untimed preparation,
not a hit-performance endpoint. Exceptions close Java/mmap sequentially; one close
failure can prevent later cleanup. Constructors before try are not covered by its
finally.

run first calls java_trial prepare_only to seal the final memtable before measuring
any layer. That path does not apply Python's PASSED/activity checks to the returned
seal report; it relies on the Java process exit and producer behavior. Measured
Java trials are not separately tensor/hash-validated by this wrapper.

## Python Trial and Nested Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `python_trial(stores, directory, job, plan, entries)` | Constructs full epoch schedule, starts Java daemon and both adapters even for mmap trials, drains and runs untimed warmup. Captures initial cardinality/activity and metric baselines, then consumes ordered bounded-prefetch epochs. Checks complete schedule, no publication/storage activity, trace errors, stable connection count and one correlated Aether getMany per batch; optional completed-server trace reconciliation enriches traces. Writes PASSED after cleanup, or INVALID evidence inside its protected trial body and rethrows. |
| `python_trial.prepare_batch(indices)` | Retrieves Aether packed values (closing packed response in finally) or mmap records minus their prefix. Requires all hits and expected byte lengths. Full-input mode unpacks, checks sample ID order, stacks arrays and normalizes CPU tensors. Returns PreparedBatch with lookup/decode/tensor timing, bytes, samples and correlated getMany trace IDs. No fallback transformation or publication exists here. |
| `python_trial.export_completed()` | With serverTrace off returns empty list. Otherwise drains completed traces, requires dropped==0 and returns records. Called after warmup and between epochs outside active epoch timing; missing dropped also fails. Does not independently validate every returned record here. |

Measured retrieval checks length, not full hashes again; full-input mode also checks
decoded sample IDs. The byte preflight is the stronger initial content check.
Lookup timing includes retrieval/copying but ends before hit/length checks; duration
includes checks and optional CPU preparation. artifactDecodeNs includes unpack,
identity checking and stacking; tensorMaterializationNs covers normalization.
No transfer, model forward, backward or optimizer work is present.

Prefetch uses one workload connection with one producer at a time. Epoch wall
includes iterator consumption/close; trace export between epochs is excluded.
Batch records keep producer duration and consumer wait separately; throughput uses
sum of active epoch walls, not sum of overlapping producer phases. Tensors are
released after consumption. Warmup traces are cleared and completed records
discarded before measured snapshots.

Publication count combines Java operation-2/6 deltas and mmap appended-entry delta.
Measured control requests also affect protocol snapshots. Successful Aether trials
require get trace count equals batch count and stable connections; they do not
explicitly reject internal attempt_failed events if final outcome is complete.
Server reconciliation rejects duplicate completed IDs and differing ID sets, but
does not separately assert uniqueness of client IDs. Missing stage keys contribute
zero in distributions. Java/storage phases are inclusive, not additive.

The INVALID-report handler covers the inner protected body, including schedule and
validation errors, not every construction/setup/cleanup failure. Exceptions before
try, in client/mmap close or daemon-context exit can bypass that receipt or prevent
the final PASSED write. client.close then mmap.close are not independently guarded.
Each mmap trial still incurs excluded Java startup/drain/control activity to verify
storage idleness, though measured data retrieval is local mmap.

## Comparison and Campaign Functions

| Function | Behavior and boundaries |
| --- | --- |
| `comparisons(reports, training_size)` | Indexes trials by backend/layer/size/depth (last duplicate wins). At depth zero computes RPC minus Java mean request duration only when Java report has no segment-backed entries; computes full-input minus RPC mean when available. Negative differences remain. Missing measurements give None. Missing Java report defaults inline-comparable true but still produces no Java subtraction. |
| `comparisons.latency(backend, layer)` | Finds depth-zero training-size trial and returns summary.durationNs.mean or None if missing. Does not check report PASSED, matching epochs or nonempty duration distribution itself. |
| `run(args)` | Builds plan; plan-only writes a plan without population. Otherwise exclusively owns output, rejects existing protocol/repetition evidence, records environment/source/manifest hashes, creates repeats and cache workspaces, prepares/seals stores and runs shuffled jobs. Writes per-repeat comparisons and final diagnostic summary. On protected-body BaseException writes INVALID with completed repetitions and rethrows. |

Plan-only allows an existing directory and overwrites hit-path-plan.json; it does
not verify data, build Java or establish clean source. Active mode records sourceClean
from successful empty git status, but does not reject a dirty checkout. Manifest
hashes are recorded before repeats; this function does not rehash them each trial.
Preparation/verification/warmup/process startup and trace export stay outside active
epoch summaries. Cache-workspace retention/cleanup follows that helper's contract.

The campaign INVALID handler starts after protocol/environment writes; setup failures
before it need not produce hit-path-summary.json. Successful output uses its own
diagnostic schema and explicitly confirmatory=false. No paper-block merging,
checkpoint resume or research conclusion is inferred from these layer differences.

## Coverage and Evidence

AST checks require all 19 qualified declarations. Existing
[hit-path tests](../../scripts/tests/test_hit_path_profile.py) check hit/idleness,
activity rejection and plan constraints. Focused contracts cover interpolated
percentiles, job construction, binary workload layout and descriptive differences.
These local fixtures do not claim live Java/RPC/tensor throughput evidence.

This module is **19/19 declarations covered (100%; 0% remaining)**. Wider repository
documentation remains unfinished.
