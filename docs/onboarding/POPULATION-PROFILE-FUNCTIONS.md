# Population Diagnostic and Publication Trace Functions

[Function index](FUNCTION-INDEX.md) | [Research tooling](EXPERIMENTS-AND-PROFILING.md) | [Comparison adapters](RESEARCH-ADAPTER-FUNCTIONS.md)

Source: [profile_population.py](../../scripts/profile_population.py).
This reference covers all **18 explicit function declarations**, including the
nested preprocessing callback. Anonymous callbacks are described separately.
This tool diagnoses population costs; it does not train a model or produce an H2
confirmatory result. The bulk-layout mode adds an excluded post-population update
regression despite the module's shorter V0-only description.

## Parent and Worker Architecture

```text
main (parent)
  -> verify frozen version manifests and package versions
  -> compute common reference tensors outside measured arms
  -> choose arm order and create a fresh scratch store
  -> subprocess main --request (one isolated worker per arm)
       -> run_case: startup -> populate -> drain -> validate -> close
       -> bulk backend delegates to bulk_population.run_bulk_case
  -> save result with campaign metadata; update descriptive summary
  -> remove successful scratch store; retain failed store
  -> CSV, plot and completion receipt
```

Each ordinary Aether arm starts and stops its own daemon. This is **not** the
persistent-daemon V0-through-V4 longitudinal experiment. Fresh stores avoid reuse
between arms, but the common reference preflight warms source data and the page
cache is uncontrolled. Native backends retain their own durability policies;
the runner does not force durability equivalence.

## Arm Selection and Workload Arguments

| Function | Behavior and boundaries |
| --- | --- |
| `layout_cases()` | Returns three offline-bulk arms with 32, 64 and 128 MiB SSTable targets, lookup/publication batch 16 and online tracing disabled. Returns configuration only; does not run, validate or select a winning layout. |
| `cases(include_bulk=False)` | Returns six traced online Aether publication sweeps (chunks 1/4/8/16/32/64 with lookup groups fixed at 64), two batch-16 traced/untraced controls, then the three native backends from base.BACKENDS[1:]. Default is 11 arms; include_bulk adds a twelfth offline-bulk batch-16 arm. |
| `expected_puts(count, lookup_batch, put_batch)` | Sums ceil(group length / publication chunk) independently for each lookup group. This is not necessarily ceil(count / put_batch): chunking restarts at every lookup. For 1,200 samples, lookup 64, publication 32 gives 38 requests. Caller must provide valid positive batch sizes. |
| `workload_args(manifest, count, size, trusted=False)` | Uses the benchmark parser to configure OCT5K, supplied manifest/count/resize, four preprocessing passes, batch 16 and zero prefetch. trusted adds trust-manifest-hashes; it does not itself verify source integrity. Other benchmark options retain parser defaults. |

## Inclusive Instrumentation Wrappers

| Function | Behavior and boundaries |
| --- | --- |
| `TimedTransform.__init__(args)` | Initializes the shared CanonicalTransform, including its identity/call accounting, then a zero elapsed_ns accumulator. Does not change transformation semantics. |
| `TimedTransform.__call__(item)` | Delegates transformation and accumulates perf_counter_ns elapsed in finally, including time spent in failed calls. This is inclusive source loading/preprocessing time, not an independent partition of population wall. Accumulator is not synchronized for concurrent callers. |
| `TimedCodec.__init__(codec)` | Keeps the delegate and zeroes encode_ns, decode_ns and successful encoded-byte count. Does not select or alter a serialization format. |
| `TimedCodec.encode(value)` | Returns the delegate payload unchanged and counts its length only after successful encoding. finally records elapsed even on failure. No durability, transport or physical-write measurement. |
| `TimedCodec.decode(value)` | Returns the delegate result unchanged; finally records successful or failed decoding time. No payload copy or validation is added by this wrapper. |
| `PublicationClient.__init__(*, put_batch, traced, **kwargs)` | Sets chunk size, record/publication lists and active=None; enables the production client's trace sink and server trace together when traced. Does not validate a positive chunk size itself. |
| `PublicationClient.put_many(values)` | Materializes input and submits successive chunks through the production encoder/exchange. Records artifact/payload counts, then starts timing, and appends a publication only after success. finally clears active for every chunk. A later failure does not undo earlier successful publications; the failed chunk is absent from successful-publication records. Payload-byte summation occurs before its startedNs timestamp. |
| `PublicationClient._round_trip(body, **kwargs)` | While a publication is active and operation byte body[1] is 6, records body-construction elapsed and body length before delegating unchanged. This is body construction, not framing/send/server time. Assumes a well-formed body of at least two bytes on that path. |

The trace sink is records.append. The dataset identity callback returns
item["source_identity"] and deliberately ignores index, retaining source-identity
reuse semantics. Neither is a new storage implementation.

## Trace Reconciliation

| Function | Behavior and boundaries |
| --- | --- |
| `summarize_trace(records, publications, completed)` | Rejects non-complete outcomes or attempt_failed events for every client record, including non-publication operations. For operation 6, subtracts client event marks into framing/send/response phases and sums server publication/write stages, write counters and completed foreground flush stages/causes. Requires no dropped completed traces and equality of client/server trace-ID sets. Adds completed-server stages only for publication IDs. Returns publication totals and explicit timing/transfer/force-attribution caveats. Missing events/fields fail rather than fabricate data. |

Set equality is not a duplicate-ID uniqueness check. This helper does not establish
one publication record per trace, monotonic event ordering or nonnegative stage
durations. run_case separately checks request and operation counts. Repeated event
names overwrite earlier timestamps when building the marks dictionary.

Client and server timings overlap. responseWaitAndRead includes server storage
work; socketSend measures local sendall duration, not isolated network bandwidth.
WAL force can be nested in databaseWriteAndSync. A shared group force is attributed
to the first request, and this diagnostic assumes one serial writer. Do not sum
these fields into an additive end-to-end cost model.

## Population and Validation Worker

| Function | Behavior and boundaries |
| --- | --- |
| `run_case(request)` | Immediately delegates aether_bulk requests to run_bulk_case. For other backends rejects an existing store, loads trusted-manifest sources, installs the timed preprocessing wrapper, starts disk sampling and enters an Aether daemon or native null context. Aether must initially report zero entries, DURABLE, background compaction enabled and immutable-inline-admission-v1. Builds the selected dataset, fetches all lookup groups once, checks transformation and RPC cardinality, drains, then performs excluded full tensor readback/count checks without new preprocessing. Closes dataset/daemon and reports stage times, counters, storage suffix bytes and resource deltas. |
| `run_case.capture_preprocessing(*a, **kw)` | Calls the saved preprocess_sample_with_timing, adds returned counters and returns the original pair. A failing delegate produces no counters here. Installed temporarily on the shared benchmark module, so worker-process isolation matters; this is not a thread-local hook. |

For Aether, expected publication count must match both successful publications
and operation-6 count; operation-5 count must match lookup groups. Traced arms also
require summed server write operations to equal source count. Before trace export,
tracing is disabled and same-connection engine_info acts as a barrier so the final
write's completed trace is available. Trace-sink errors reject the arm.

Unique-artifact counting uses Aether cacheEntries, mmap index size, PersistentDataset
*.pt count or LMDB entries. Readback must match the supplied reference hash and
leave transform.calls unchanged. It is a full dataset check, not model parity.

Reported totalMs = startup + population + quiescence + close. validationMs is
separate and excluded. Population includes adapter opening and the admission scan.
Codec/protocol/cache snapshots are captured before readback; process/disk counters
span readback too. Java resource deltas exclude shutdown; Python deltas extend
through close and final inspection. These observations have different windows.

finally restores preprocessing, stops sampling, closes any remaining dataset and
exits an entered manager. Setup before the try (including sampler startup/resource
snapshot) is not covered by that finally. Cleanup calls are not individually
protected, so one cleanup error can prevent later cleanup. Failed stores are not
rolled back or deleted by this worker.

## Summary, Plot and Campaign Controller

| Function | Behavior and boundaries |
| --- | --- |
| `summarize(reports)` | Groups only recognized case names and emits raw population times plus medians of population, total and preprocessing. Traced and bulk stage medians use the union of observed keys with missing keys treated as zero. Layout reports add warm throughput, incremental admission+drain, table counts and sampled RSS; ratios pair by blockIndex against the 32 MiB arm. Unknown cases are omitted. Does not certify complete blocks, homogeneous protocols or statistical significance. |
| `plot(reports, output)` | Writes population.csv and an Agg-rendered population.png. Left panel shows per-case median population seconds. Ordinary mode draws six publication-size curves for Python body construction, inclusive DB write+sync and nested WAL force; layout mode instead shows warm lookup throughput. Requires an existing output directory and nonempty reports; ordinary curves require every sweep size. Anonymous getter lambdas extract those three fields without changing units until division by 1e9. |
| `main(argv=None)` | Parses parent/worker modes; rejects simultaneous include-bulk and bulk-layout. Worker mode reads a request, runs it and writes JSON, bypassing parent manifest/package/campaign gates. Parent requires frozen counts 1200/1260/1323/1389/1458, seed 20260926, MONAI 1.6.0 and LMDB 2.1.1; computes references, establishes campaign metadata, launches isolated arms, saves results/partial summaries, plots and writes completion. Smoke uses 65 samples/one repetition; normal uses 1200/three. No model training occurs. |

Ordinary arm order is shuffled deterministically per block from seed 20260929.
include-bulk inserts the bulk arm at (block*5)%12 without changing the eleven-arm
relative order. Layout-only order rotates three sizes, balancing their positions
over three repetitions. Layout update uses 68 smoke or 1,260 normal samples,
computed against V1 separately; it remains outside V0 timing.

The optional artifact-provenance.json hash can be null. Protocol strings such as
bulk-deferred-inventory-v2 are declared metadata, not independent proof of the
current verifier implementation. Inspect the delegated bulk writer and frozen
campaign evidence before making implementation or performance claims.

Each parent arm requires available scratch capacity, creates a new output location
and scratch/store path, and runs a checked subprocess with its output in worker.log.
There is no subprocess timeout or completed-arm resume path here. Finally copies
top-level scratch/store.* files, not the whole store directory. Successful validated
stores are removed only after checking the scratch parent and name prefix; failures
retain the scratch store and diagnostics. Existing arm directories fail rather
than silently overwrite evidence.

Layout pairing uses the last 32 MiB report for a duplicate block index and does not
guard a zero baseline throughput. Plot mode is selected from the first report;
mixed/incomplete report collections are not a supported aggregation protocol.

## Coverage and Evidence

The declaration test checks all 18 qualified names against the source AST.
[Population tests](../../scripts/tests/test_profile_population.py) cover arm
construction and unchanged production publication bodies across six chunk sizes.
The all-backend readback/plot test is opt-in with AETHER_JAVA_TEST=1; a default
passing test run is not evidence that it executed a real daemon or GPU workload.

This file's declaration coverage is **18/18 (100%; 0% remaining)**. Other profiling
tools and broader repository function references remain separate work.
