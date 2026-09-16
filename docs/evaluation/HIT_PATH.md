# Hit-path diagnostics and controlled prefetch

The final ten-epoch, depth-zero design is specified in
[the confirmatory protocol](../../kaggle/CONFIRMATORY.md). The diagnostic and pilot
commands below remain exploratory and must not supply confirmatory blocks.

This campaign measures a completely populated V2 cache. It produces
`steady-state-hit-path-diagnostic` reports, never paper `block.json` files or
confirmatory inference. It does not train a model. Existing pilots are unchanged.

## Run on Kaggle or the prepared Linux host

From the repository root, build the current Java runtime without running tests:

```bash
./gradlew :modules:aether-training-cache:paperRuntimeClasspath -x test --console=plain
```

First isolate the hit path at the existing training batch size, without prefetch:

```bash
python scripts/run_matrix.py \
  --config /kaggle/input/aether-oct5k-pilot/aether-datasets.json \
  --datasets oct5k --backends aether,mmap \
  --hit-path-profile --repeats 5 --epochs 10 --server-trace \
  --batch-size 16 --request-sizes 16 --prefetch-depths 0 \
  --output /kaggle/working/aether-results/hit-path-baseline
```

Use the actual dataset configuration path if your Kaggle input slug differs.
The configuration supplies the complete V2 manifest and expected sample count;
the default OCT5K configuration expects 1505 unique entries. Java, NumPy, PyTorch
and the existing image preprocessing dependencies must be installed. GPU access
is not required for the input-only diagnostic.

Then collect the request-size and prefetch-depth sweeps in a fresh output:

```bash
python scripts/run_matrix.py \
  --config /kaggle/input/aether-oct5k-pilot/aether-datasets.json \
  --datasets oct5k --backends aether,mmap \
  --hit-path-profile --repeats 1 --epochs 10 --server-trace \
  --batch-size 16 --request-sizes 1,4,8,16,32,64 \
  --prefetch-depths 0,1,2,4,8 \
  --output /kaggle/working/aether-results/hit-path-sweep
```

Windows uses `./gradlew.bat` and local configuration/output paths; the Python
arguments are the same (put the command on one line or use PowerShell backticks).
`--plan-only` writes a plan without opening stores or performing measurements.
`--scratch-root` selects temporary cache storage and `--retain-stores` retains
generated stores. Completed stores are otherwise cleaned by the existing owned
workspace helper; interrupted stores and partial evidence are retained.

Reports under `/kaggle/working/aether-results` are included by the existing
notebook's results ZIP export. Run its export cell after a manual diagnostic.

## What is held constant

Each repetition creates fresh Aether and mmap stores, runs the existing
deterministic preprocessing and artifact encoding once per V2 sample, and writes
identical bytes to both. Cardinality, presence, hashes and byte equality are
verified before timing. A normal graceful database close seals residual memtable
data before any layer starts. Every trial opens the same persisted layout and
requires compaction state `IDLE`, zero debt and zero failures.

Each repetition has one seeded sample permutation, reused across every epoch and
layer. Trial order is also seeded and randomized. `--hit-warmup-epochs` defaults
to one untimed read epoch before each trial. Preprocessing, verification,
warmup, daemon/JVM startup, and trace export are excluded from active epoch
times. OS page-cache state remains uncontrolled and is explicitly reported;
these are warm-hit diagnostics, not cold-I/O or confirmatory measurements. Java
trials use separate JVM lifetimes, so warmup does not guarantee equal JIT state.

The three layers are:

| Layer | Work inside a batch |
| --- | --- |
| Java database | Repeated `database.get` calls for the same encoded keys; the database has no native batch API |
| Python bytes | `get_many_values` and response parsing, without NumPy/tensor reconstruction; mmap retrieves the same artifacts locally |
| Full input | Retrieval, artifact decoding, array stacking and production CPU tensor normalization |

Java database values contain cache encoding; segment-backed artifacts return
segment metadata at this layer, while RPC returns the artifact payload. Read the
reported byte scope before comparing layers. The reported RPC-minus-Java and
full-minus-RPC means are descriptive differences between separate trials,
including validation/packing and cache/JIT effects. They are not causal
decompositions. The Java subtraction is omitted for segment-backed artifacts.

Request-size sweeps apply to Java and bytes-only retrieval. Full-input trials
always consume the unchanged `--batch-size`; that size is automatically included
in the bytes sweep for a matching baseline. Prefetch adds one worker and a queue
bounded by the selected depth. There can additionally be one in-flight prepared
batch and the consumer's current batch. The worker uses the same persistent
connection and never chooses its own sample order. Each epoch is drained and
closed before the next worker starts. Early cancellation interrupts Aether reads
and prevents retries; normal epoch completion preserves the connection.

The full-input diagnostic has no GPU compute to overlap. It checks overhead,
ordering and queue behavior; a throughput change here is not a GPU training
speedup. No storage policy, preprocessing, model, training batch size or TCP
setting is changed by this work.

## Evidence and invalidation

`protocol.json` and `environment.json` record source hashes, manifest hashes,
configuration and source cleanliness. Each repetition saves `population.json`,
the exact ordered artifacts, `keys.bin`, `trial-order.json`, individual trial
JSON/logs and `hit-summary.json`. `hit-path-summary.json` reports completion or
invalidation. Failed trials preserve available records and cannot be treated as
successful measurements. Resuming or reusing a prior output is rejected.

Per-batch records include sample/byte counts, duration, epoch and batch index.
Summaries contain mean, median, p95, p99, milliseconds per sample, bytes per
request, samples/s and bytes/s. Client records split request encoding, send,
receive and response decoding. Full input adds artifact decoding and CPU tensor
materialization. `traceIds` join each batch to its client/server records.

Server response-write completion cannot appear inside the response it measures.
Opcode 9 drains bounded completed traces **after each epoch**, outside timing.
`serverCompleted` carries those timings, joined by the original `traceId`.
The response envelope explicitly marks its own timing as pre-write. Buffer
overflow, missing/duplicate trace IDs or connection reopen invalidates a trial.
Server and storage stage durations are nested/inclusive; do not sum them as
independent contributions. Request-read timing excludes connection idle time.

Timed reads have no preprocessing/publication fallback. Missing entries, changed
artifact sizes/IDs, publishes, flushes, background-compaction starts, failures,
or missing instrumentation invalidate the run. Monotonic engine counters catch
activity even when a compaction starts and finishes between snapshots.

Prefetch metrics include depth, batches requested/consumed, queue empty/full wait,
lookup/decode time, consumer wait and maximum/mean queue depth. Mean depth is the
arithmetic mean sampled after successful enqueue/dequeue, weighted by sample
count across epochs; it is not a time-weighted occupancy estimate. Depth zero
runs preparation synchronously and reports its wait cost.

## Follow with a training pilot

Choose the smallest useful depth from diagnostics. For example, to measure depth
2 in one fresh GPU training block (existing five epochs and batch size 16):

```bash
python scripts/run_matrix.py \
  --config /kaggle/input/aether-oct5k-pilot/aether-datasets.json \
  --datasets oct5k --repeats 1 --epochs 5 --batch-size 16 \
  --workers 0 --prefetch-depth 2 --server-trace \
  --output /kaggle/working/aether-results/prefetch-d2-diagnostic
```

After correctness and trace review, repeat with `--repeats 10`, omit
`--server-trace`, and choose another fresh output. The same pilot option is
available through `scripts/reproduce.py pilot --prefetch-depth 2`; that existing
entry point also runs its normal test prerequisite. No commands were executed
as part of implementing this change except compilation/syntax checks.

Do not label these results confirmatory. Freeze the final implementation and
sample count, commit/tag a clean source snapshot, and collect a completely new
confirmatory campaign only after the pilot decision.

Added test sources cover hit-only rejection, prefetch order, outputs/tensors,
worker errors, cancellation and epoch transitions. They are intentionally left
for the user to run.

## Follow-up after the checksum policy change

Rebuild and repeat the baseline command above in a fresh output directory.
Warm inline SSTable batches should report `artifactValidationReuses` in their
storage counters, with `cacheChecksum` absent when no new admission is needed.
First admissions still check CRC32C and SHA-256. Reloads receive new validation
state; native memtable copies and segment reads continue to validate. INFO and
trial JSON identify this as `immutable-inline-admission-v1`.

After reviewing that diagnostic, run separate fresh ten-repeat pilots for depths
0 and 2. This loop is for the user to execute; it has not been run:

```bash
for depth in 0 2; do
  python scripts/run_matrix.py \
    --config /kaggle/input/aether-oct5k-pilot/aether-datasets.json \
    --datasets oct5k --repeats 10 --epochs 5 --batch-size 16 \
    --workers 0 --prefetch-depth "$depth" \
    --output "/kaggle/working/aether-results/checksum-pilot-d${depth}"
done
```

Keep those outputs separate from both earlier pilots and future confirmatory
data. No speedup is established by compilation alone.
