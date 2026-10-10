# Bulk JFR and Background Compaction Drivers

[Function index](FUNCTION-INDEX.md) | [JFR analysis](JFR-ANALYSIS-FUNCTIONS.md) | [Population diagnostics](POPULATION-PROFILE-FUNCTIONS.md)

Sources: [profile_bulk_jfr.py](../../scripts/profile_bulk_jfr.py) and
[profile_background_compaction.py](../../scripts/profile_background_compaction.py).
This page covers all **3 explicit declarations** across the two modules. Their
two main functions are listed under their owning module below.

## Recording and Reopen Architecture

```text
bulk JFR driver
  -> common source/reference preflight
  -> fresh store: control-before -> fresh store: jfr-run -> fresh store: control-after
     or candidate-only: one jfr-run
  -> isolated population worker -> durable finish -> ordinary-reader validation
  -> export events/summary -> analyzer -> completion -> output checksums

background diagnostic
  -> synthetic batch publications -> shared daemon helper drains -> shutdown
  -> inspect drain receipt -> reopen same store -> batch byte parity
  -> reject foreground compaction traces -> summary
```

These are storage diagnostics, not GPU training or persistent-daemon dataset
revision experiments. Bulk arms each get a fresh scratch store. The background
diagnostic intentionally reopens one store after shutdown. Their timings, receipts
and checks cannot be substituted for H2 cumulative lifecycle results.

## Bulk JFR Driver Functions

| Function | Behavior and boundaries |
| --- | --- |
| `run(output, manifest, samples=1200, scratch_root=None, smoke=False, candidate_only=False)` | Requires jfr on PATH, resolves output, computes canonical 256-pixel reference tensors from the supplied manifest, creates scratch root and enters an evidence campaign. Launches one or three isolated population workers with fixed 32 MiB SSTable target, batch/lookup 16 and tracing off. Only jfr-run requests a recording with stock profile settings. Saves receipts, exports JFR events at stack depth 256, invokes analyze, writes completion and finally hashes output files. |
| `main()` | Parses output/scratch/smoke/candidate-only, verifies the configured longitudinal manifest set and chooses V0. Rejects candidate-only plus smoke, then calls run with 65 smoke or 1,200 normal samples. The CLI enforces the full candidate workload; direct run callers can supply other manifests/counts and must establish their own validity. |

Reference preprocessing is common preflight, outside the worker population timer.
Its integrity/reference scan can warm source data. Protocol metadata identifies
the offline BulkArtifactWriter JVM as the target; the ordinary restart reader is
not profiled. V0 population includes finish/quiescence; readback is outside that
timer. The driver delegates those actual boundaries to the population/bulk worker.
It performs no model training and does not control OS-cache coldness.

Default sequence is control-before, jfr-run, control-after, each a separate store.
Candidate-only uses one recording and calls profile_bulk_verification.validate_report
with candidate/reference before saving its receipt. Control mode relies on worker
correctness and later analyzer gates rather than that extra candidate validator.
Fixed sequence is not randomized; analyzer overhead ratios remain descriptive.
Candidate metadata retains priorPerformanceGate='failed; unchanged', not a new
performance decision. verificationPolicy strings are declarations, not independent
verification of a current implementation; inspect the worker and receipt.

Every arm checks 1 GiB available capacity, creates a bulk-jfr-prefixed scratch
directory and writes a request/log/worker result. The checked worker subprocess
has no timeout here. Finally copies top-level scratch/store.* files to named
output diagnostics, not the whole store directory. Success removes scratch only
after checking its resolved parent and prefix; failure retains it. The JFR file
is written directly under output, so successful scratch cleanup does not remove it.

After all workers, checked jfr print and summary subprocesses write directly to
their destination files. They can leave partial output on failure; stderr is not
redirected into those files. run has no independent nonempty-recording check,
export-hash binding or DataLoss rejection; subprocess/worker/analyzer gates provide
the actual checks. Candidate analysis additionally requires a unique authoritative
verification marker. completion.json follows successful analysis.

checksums.sha256 is produced **after** exiting the campaign context and covers all
then-existing output files recursively, excluding itself, sorted by path. It is
not independently verified here. A hash/write failure can occur after completion
has already been written. The driver does not resume completed arms or choose an
optimization automatically.

## Background Compaction Diagnostic Function

| Function | Behavior and boundaries |
| --- | --- |
| `main()` | Parses output, samples (default 1440) and payload bytes (default 196849); creates a new output directory and deterministic synthetic keys/payload. Publishes in batches of 16 with client/server tracing. After daemon-helper drain/shutdown saves requests, reads the last sorted compaction receipt and requires completed>=1 with no failures. Reopens the same store, verifies requested bytes in batches, rejects compactions reported in foreground server traces, then writes summary.json and prints its path. |

Keys use namespace background-diagnostic and the shared
background-compaction-diagnostic-v1 transformation fingerprint. Every key's value
is the same byte pattern i%251. This tests durable byte parity after reopen, not
source identity evolution, distinct tensor contents, segmentation accuracy or
training throughput. The script does not validate positive sample/payload counts;
negative ranges can produce empty inputs and later fail the compaction gate.

The shared paper_common.java_daemon helper defaults to DURABLE, waits for background
compaction on exit, writes store.compaction-<time_ns>.json and rejects drain failure
or failed work before termination. Therefore this script's first shutdown is also
its drain boundary. Reopening creates another daemon and its own exit drain receipt.
The summary uses the **first** daemon's receipt captured before reopening, not the
later one. A missing receipt raises at drains[-1]; there is no fallback diagnostic.

Reopen checks require every requested key to map to the expected payload, but do
not reject extra stored keys or independently count unique artifacts. Foreground
compaction detection examines only available server_trace events. The script
does not enforce trace counts, trace_errors, complete outcomes, retry absence or
server/client trace-ID reconciliation. Empty trace evidence can therefore pass the
foreground check; foregroundCompactionCount=0 is a checked conclusion only over
the traces received, not an independent observation of all work.

Flush count and totalNs/1e6 values come from received foreground trace records.
There is no additional completed-flush assertion or isolated background timing
measurement. requests.json is saved before compaction/reopen gates; failures retain
the directory, store and available diagnostics, without a successful summary.
Existing output directories fail rather than overwrite or resume.

## Coverage and Evidence

AST coverage checks both modules separately against their sections. Existing
[bulk JFR tests](../../scripts/tests/test_bulk_jfr.py) exercise candidate-only
orchestration with subprocess doubles and verify analyzer constraints. Live
recording/pipe tests are opt-in. Focused driver contracts additionally check CLI
rejection and synthetic publication/drain/reopen control flow without a real JVM.

These two files have **3/3 declarations covered (100%; 0% remaining)**. Other
profilers, operational tooling and repository subsystems remain in scope.
