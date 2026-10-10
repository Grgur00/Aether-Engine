# H2 Confirmatory Lifecycle Experiment

This protocol tests lifecycle cost under approximately 95% additive artifact reuse.
It is not an SSTable benchmark or a test of segmentation accuracy. Pilot observations
and protocol-validation runs are excluded from the confirmatory sample.

## Frozen Treatment

The successful five-block pilot is preserved at source commit
`5ad8e6f367227ed1a31c849325803d1f1c08b76d`, with storage commit
`00760e5f31fa31a17e69522539a3b60318ca9bf0` (`aether-v0-streaming-v1`).
The original pilot worker, adapters, model and same-JVM bootstrap are used unchanged.
Do not run the older longitudinal worker from an unrelated checkout: it can omit
V0 training and use online initial admission.

Settings: inline artifacts, 32 MiB SSTable target, bulk V0, admission batch 16,
one authoritative streaming-v1 verification, no bulk WAL payload duplication,
immutable-inline-admission-v1, DURABLE, existing asynchronous compaction, one
Aether JVM across V0-V4, no JFR and no detailed server trace.

Versions contain 1200, 1260, 1323, 1389 and 1458 samples. Every version includes
20 epochs, batch 16 and prefetch 0. The pilot's small convolutional segmentation
model, AdamW (0.001), BCEWithLogitsLoss, four preprocessing passes, 12 warmup
steps and fresh model/optimizer per version remain unchanged. Model initialization,
ordered batches, augmentation seed, tensor hashes and final model hashes must
match between paired backends.

The pilot's verified source archive digest is
`0d22d51d83a55908169c049d7c2404525a2b357ed19b2111d105f58be3a926bb`.
The recovered raw pilot gives mean cumulative seconds of Aether 916.99, mmap
943.92, PersistentDataset 945.82 and LMDBDataset 933.92. The proposal's 945.83
is a rounding difference; archived raw timings are authoritative.

## Measurement And Ordering

The endpoint is the sum of `startup`, `modelSetup`, `scanAdmission`, `training`,
`drain` and `close` over all five stages. Aether startup is charged to V0;
shutdown is charged to V4. Required quiescence remains included. Shared input
preflight, process launch/import, tensor/model identity validation, instrumentation
and archival are excluded, as in the successful pilot.

The confirmatory ordering deliberately differs from the pilot's interleaved
versions: finish a complete backend V0-V4 lifecycle before starting another.
All 24 backend permutations occur exactly once, shuffled with recorded seed
20261008. Each backend occupies each position six times. Block seeds are
20260926 through 20260949. No OS page-cache clearing is performed.

## Audit And Freeze

Keep the candidate and the confirmatory harness in separate directories. Candidate
source must be clean at the recorded commit or match a verified source archive.
The new harness never modifies the frozen candidate. Example from the workspace:

```powershell
.venv/Scripts/python.exe scripts/h2_confirmatory.py --candidate-root build/h2-streaming/source audit-pilot --pilot-output build/h2-confirmatory/pilot-evidence/longitudinal-persistent-pilot
.venv/Scripts/python.exe scripts/h2_confirmatory.py --candidate-root build/h2-streaming/source freeze --pilot-output build/h2-confirmatory/pilot-evidence/longitudinal-persistent-pilot --output build/h2-confirmatory/frozen-v2
```

Freeze writes the immutable protocol, balanced schedule, source manifest, exact
commit, runtime versions, pilot audit, pilot receipts, `source.zip`, `harness.zip`
and a checksummed freeze receipt. Source and harness have separate identities.
Any later change requires a new freeze and a new output, never an edited receipt.

For deployment extract `source.zip` into a fresh candidate directory and
`harness.zip` into a separate harness directory. Keep the complete frozen bundle
alongside them. Build the candidate's `paperRuntimeClasspath` with its own
Gradle wrapper before measurement. Install the candidate's locked requirements,
including MONAI/LMDB, into the training environment. The runtime guard requires
the pilot's Python, Java and relevant package versions exactly. The source archive
has provenance for deployments without Git metadata.

### Read-Only Input Mount Binding

Kaggle can attach OCT5K at an owner-qualified directory rather than the original
`/kaggle/input/aether-oct5k-pilot` path. Pass the actual mount using `--input-root`.
The separately frozen harness binds the existing OCT5K metadata resolver to that
directory before the worker's measured phases. The worker source, data files,
CSV bytes, original manifest digests, artifact identities, ordering and hash
validation remain unchanged. Ordinary physical `Path` objects reach preprocessing;
no path translation runs during image reads or the timed training loop.

The excluded input preflight resolves the same binding, hashes the original source
manifest and every image/mask, and checks the original tensor hashes. Every worker
records its binding and must match that preflight. Same-host resume rejects a changed
binding. Paths outside the declared dataset, traversal and escaping symlinks are
rejected. Neither aliases in `/kaggle/input` nor copies of raw data are created.
This deployment adaptation requires a new harness freeze; earlier freeze receipts
remain unchanged. The successful Python/CUDA image is pinned separately in notebook
metadata, with exact runtime checks still mandatory.

## GPU Preflight And Execution

The source dataset must remain available at the configured manifest path,
`/kaggle/input/aether-oct5k-pilot/manifests/v2.csv`. Complete manifests and expected
prepared-artifact hashes are checked before measurement. Do not replace missing
inputs with synthetic samples. A CUDA GPU is mandatory for scientific runs.

```bash
python /harness/scripts/h2_confirmatory.py --candidate-root /candidate run \
  --frozen /frozen --output /results/h2-confirmatory --scratch-root /scratch \
  --preflight-only

python /harness/scripts/h2_confirmatory.py --candidate-root /candidate run \
  --frozen /frozen --output /results/h2-confirmatory --scratch-root /scratch \
  --resume
```

Preflight executes all 100 epochs for each backend and validates the complete
lifecycle; its timings never enter inference. Only proceed after it passes.
The runner then executes 24 valid whole blocks. Progress reports completion
counts only; no intermediate significance is calculated.

At the pilot's speed, 24 blocks take about 25 GPU-hours, plus preflight and
excluded overhead. This first execution protocol requires a long-lived host.
The existing campaign guard rejects resume on a different host/runtime. A
multi-session Kaggle campaign needs a separately frozen session-assignment and
environment policy before starting; do not weaken resume checks midway through
a campaign or shorten epochs to fit a session.

### Bounded 12-Block Launch

The user-authorized risk-taking launch uses `--stop-after-blocks 12`. It runs
the first 12 assignments of the frozen 24-block schedule after the full excluded
preflight. This is a partial campaign, not a new 12-block significance test.
All treatment settings and the requirement for 24 valid blocks remain unchanged.
The estimated measurement time alone exceeds 12 hours; setup, preflight and
archival add time. The platform may terminate it before all 12 blocks finish.

Use `--checkpoint-zip /kaggle/working/aether-results-only.zip` to publish a
checksum-verified ZIP atomically after preflight and after each complete block.
Checkpoint archival is synchronous and outside backend measurement phases.
A hard kill during a subsequent block leaves the previous verified ZIP intact;
availability of output files after platform termination still depends on Kaggle.
Only whole completed blocks count. No inference is computed at the launch limit.
The existing same-host resume guard remains enforced. These archives must not
be pooled with a different-host run under this single-host protocol.

## Failure, Resume And Evidence

Crashes, missing/corrupt results, invalid identities, incomplete epochs, lost
service state and failed mandatory correctness invalidate the entire four-backend
attempt. Retry every backend with the same assigned order and seed in fresh
stores. Three technical attempts per block are predeclared; failed attempts and
reasons remain archived. Partial lifecycles are never resumed. Live process
leases prohibit competing retries. High but valid elapsed time is not a failure.
Valid completed blocks are checksum-verified and reused, never overwritten or
retried. Altered completed evidence causes a hard stop.

Per-version receipts record all phase times, hashes, counts, reuse, losses,
sample requests, service PID/start/stop, compaction drains, GPU observations,
disk/process counters and bytes written where the pilot counters support them.
`artifact-identities.json` records source digests and version membership.
Backend summaries expose seconds and cumulative endpoints. Missing resource
counters are explicit nulls, not invented measurements.

## Analysis

```bash
python /harness/scripts/h2_confirmatory.py --candidate-root /candidate analyze \
  --output /results/h2-confirmatory
```

Analysis requires the validated excluded preflight and exactly 24 complete,
correct, paired blocks with all permutations. It reads the archived protocol and
receipts and checks their inventories before computing results. The primary
method is the one-sided paired t-test on `ln(mmap/Aether)` at alpha 0.05,
with a two-sided 95% t confidence interval. Report the geometric ratio, percent
time reduction `100 * (1 - 1 / ratio)`, p-value and win count. Sensitivity tests
do not replace the primary test. Zero estimated variance is reported as
inconclusive rather than manufacturing a t-test result.

MONAI comparisons remain descriptive. Report per-block and aggregate break-even,
including V0 and any later loss of advantage. Generate five PNG/PDF figures from
raw results: cumulative lifecycle, relative performance, all paired blocks,
cost breakdown and cumulative Aether-minus-mmap time. The completion inventory,
raw receipts, `analysis.json` and `results-and-limitations.json` are the evidence,
not a manually reconstructed summary.

Limit claims to this workload, dataset evolution and tested integrity policy.
OS page-cache state is imperfectly controlled, resource counters are sampled,
and PersistentDataset does not offer the same fsync durability guarantee.

## Local Checks

```powershell
$env:PYTHONPATH = 'scripts'
.venv/Scripts/python.exe -m pytest scripts/tests/test_h2_confirmatory.py scripts/tests/test_longitudinal_comparison.py scripts/tests/test_persistent_service.py -q
```

These exercise orchestration, invariants, retry/resume and analysis. Synthetic
test timings and CPU fixtures are not scientific observations. Only a passing
GPU preflight certifies the deployed execution path; only 24 valid paired blocks
complete the confirmatory experiment.
