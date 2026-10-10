# Longitudinal Pilot Runner and Analysis Functions

[Function index](FUNCTION-INDEX.md) | [Stage worker](LONGITUDINAL-WORKER-FUNCTIONS.md) | [H2 execution](H2-EXECUTION-FUNCTIONS.md)

Sources: [longitudinal_comparison.py](../../scripts/longitudinal_comparison.py)
and [longitudinal_analyze.py](../../scripts/longitudinal_analyze.py). These entries
cover all 11 explicit functions, including the nested subprocess worker. This is
an exploratory five-version protocol, not H2 confirmatory execution. V0 is
population only; versions V1-V4 each train. Both restart-per-version and the older
interleaved persistent-per-block service policy are supported.

## Architecture

```text
config -> pinned packages -> frozen membership + actual-input/tensor preflight
       -> protocol/campaign lock -> owned scratch capacity/identity
       -> block -> V0, V1, V2, V3, V4
            -> rotated four-backend order within each version
            -> stage worker -> validate counts/times/hashes -> weekly receipt
       -> paired block receipt -> descriptive summary -> checkpoint ZIP
       -> remove owned block stores -> figures -> completion
```

This is version-major interleaving, unlike H2's complete backend-major lifecycles.
A persistent Aether JVM remains resident while baseline jobs run. Its residence
time is reported separately from the measured phase sum. OS page cache is not
cleared; copying/hash-checking closed-store checkpoints can warm it. Process
restart is not a cold-cache guarantee.

## Configuration and Preflight Functions

| Function | Behavior and boundaries |
| --- | --- |
| `bundle_checkpoint(output, destination)` | Creates a temporary snapshot beside output, copytrees results while excluding campaign lock files, then delegates verified archive creation. Temporary copy is cleaned on exit. Does not lock every result file or produce a snapshot of concurrently mutating stores; controller sequencing provides the intended boundary. Archive failure propagates. |
| `validate_config(config, fixture=False, smoke=False)` | Requires known lifecycle, five sorted unique increasing-positive-start version sizes, prefetch zero, trace false, confirmatory false and batch 16. Nonfixture additionally fixes counts, image size 256, seed 20260926 and source pool 1505. Smoke requires one block/epoch; ordinary GPU pilot fixes five blocks/20 epochs. Fixture mode relaxes dataset/repetition/epoch restrictions, not every common invariant. Direct missing fields raise KeyError; no exhaustive field/type validation. |
| `preflight(config)` | Verifies version CSV hashes/prefixes, matches receipt counts/seed/pool size/current source CSV hash, loads the source pool through workload hashing, maps final-version identities back to original rows, and compares sample/digest fields and resolved image/mask paths. Preprocesses final-version samples in memory, hashes each requested prefix and returns paths plus timing/transform/input receipts. No cache population or model training. Missing identities/paths can raise. Retains all final tensors during reference generation rather than streaming bounded memory. |
| `validate_stage(report, config, stage)` | Checks exact new admissions, zero reported training misses, unique count, reuse count, total sample requests and epoch-list length; V0 must have no requests/epochs. Each epoch requests every sample; every recorded loss must be finite. Calls cumulative on one report for phase accounting. Does not require a nonempty/full batch-loss list, inspect GPU/device, validate model/tensor identity, or independently prove reported miss counters. Those responsibilities are elsewhere. |

## Block and Controller Functions

| Function | Behavior and boundaries |
| --- | --- |
| `run_block(index, output, scratch, meta, config, paths, reference, worker=None)` | For ordinary lifecycle delegates directly. Persistent policy builds block identity and enters PersistentService, treating paired.json existence as completed for service freshness handling. The inner function must validate that receipt; existence alone is not accepted evidence. Imported service implements same-PID and single-start/stop accounting. |
| `_run_block(index, output, scratch, meta, config, paths, reference, worker=None, service=None)` | Creates report directory and identity. Completed fast path loads paired and all 20 stage receipts, checks per-stage accounting/equality to paired entries, verifies five weekly stage-hash receipts and optional service lifecycle, then returns without rerunning. Fresh/partial path runs five versions in rotated backend order, delegates checkpoint/service stage ownership, validates counts/reference/pair identity, writes weekly receipts and a paired receipt with cumulative sums. No built-in technical retry loop or H2 block-attempt seal. |
| `_run_block.subprocess_worker(backend, stage, live)` | Writes a per-stage request containing store/manifest/reference, seed config.seed plus block plus stage, CPU flag, lease and optional service. Runs the current Python interpreter on longitudinal_worker.py in repository cwd, appending combined output to stage log. In a finally copies live.* diagnostic files to reports. Loads worker JSON and adds workerProcessWallMs, which includes launch/import and diagnostic handling but is excluded from phase sums. No subprocess timeout; diagnostic-copy errors can mask child failure. |
| `main(argv=None)` | Parses configuration/output/scratch/resume/smoke/fixture, applies smoke settings, validates package pins/configuration and resolves paths. Executes input preflight before acquiring campaign lock. Binds protocol, creates/reopens receipt-owned scratch and capacity checks, preserves first preflight plus later resume receipts, runs blocks, updates descriptive summary and checkpoints after each, deletes owned block stores, plots and writes completion. Records and rethrows BaseException inside the block loop, preserving state for explicit resume. Earlier setup failures do not reach that failure recorder. |

Fresh `_run_block` compares tensor, final/initial model, sample-order and seed
fields across all backends at each version, and transform identity to preflight.
It also checks each tensor against the expected reference. The completed fast
path does **not** repeat those reference/pair/transform comparisons or recompute
the paired cumulative map; it verifies saved receipt relationships and selected
stage/service invariants. Unkeyed receipt hashes are not independent authority
against rewriting a mutually consistent evidence set. H2 has a separate stronger
gate; do not infer it from these helpers.

Stage ownership can commit a stage receipt before later cross-backend pairing
fails. Ordinary closed-store mode can resume through validated stages; persistent
mode rejects partial blocks because an interrupted JVM lifetime cannot be
recreated. Completed persistent blocks may be reused after receipt validation.
Source/protocol/host campaign identity and scratch owner receipts still apply.

`main` hashes artifact-provenance.json when present; that protocol field is an
archive-provenance receipt hash, distinct from the source CSV hash in the input
reference. Common identification cost equals the original preflight total plus
optional offline manifest-generation timing; resume preflight is recorded but
does not replace that first accounting value. CPU mode is correctness-only and
still pins MONAI/LMDB. Missing packages fail before the campaign begins.

## Analysis Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ratio_summary(ratios)` | Converts values to float NumPy array and rejects empty, nonfinite or nonpositive ratios. Returns geometric mean, paired values and a descriptive log-scale t interval for n greater than one; n=1 has no interval. Assumes a one-dimensional paired sequence without explicitly checking dimensionality. No significance test; extreme exponentiation can overflow. |
| `analyze(blocks, common_ms=0.)` | Recomputes each supplied backend result's cumulative map and rejects disagreement. For three baselines computes all five cumulative baseline/Aether ratios, four direct per-update ratios and ratios with common_ms added once to each cumulative endpoint. Reports first observed ratio >=1 per block and first geometric-mean crossing. No lost-advantage/sustained-crossing field, schedule/receipt/device validation or confirmatory inference. Expects all backends/five versions; division by zero or missing structure may raise before ratio_summary. |
| `plot(blocks, directory, role="Exploratory longitudinal pilot")` | Uses noninteractive Agg, creates output directory, calls analyze, writes cumulative.png with arithmetic mean lifecycle seconds and geometric paired ratios/available intervals, then phases.png with mean six-component stacked stage times. Closes figures on normal completion, not in a failure finally. Existing names can be overwritten; output is not transactionally published. Plot role labels do not certify evidence. |

Ratio direction is **baseline time / Aether time**: greater than one favors
Aether. Exact equality counts as observed break-even; a later disadvantage does
not erase the first crossing. No interpolated version or future-cost prediction
is computed. Per-update ratios exclude earlier V0 costs; cumulative ratios include
all preceding measured phases. Common-cost ratios are additional descriptive
views, not extra costs added once per version.

The direct analysis checks cumulative-map equality but does not load receipts or
validate sample/model identities. It accepts supplied consistent timings and
relies on caller gates. common_ms is not independently required finite/nonnegative;
bad ratios are rejected only after arithmetic. The primary label V4/mmap does
not turn exploratory intervals into a confirmatory test.

## Verification Scope

[Longitudinal tests](../../scripts/tests/test_longitudinal_comparison.py) exercise
membership, stage checkpoints, process-death recovery and accounting.
[Documentation contracts](../../scripts/tests/test_longitudinal_documented_contracts.py)
add config relaxations, stage-validation limits, fresh/complete block receipt
paths, ratio/tie/common-cost semantics and disposable figure generation.
Injected workers are not CUDA or Java evidence. The preserved H2 candidate is a
different snapshot; no guide authorizes combining the protocols' measurements.
