# Longitudinal OCT5K Pilot

This exploratory experiment is separate from the five-pair MONAI pilot and the
24-pair append75 confirmation. Their measurements are not pooled. The original
`monai_comparison.py`, `system_campaign.py`, cache implementations, codecs, and
Java engine remain unchanged. The verified prior MONAI receipt is
`paper/evidence/monai-five-pair-v1.json`; its exact source and results ZIPs are
preserved in `build/preserved-campaigns/monai-five-pair-v1`.

## Frozen Workload

| Version | Samples | New | Reused |
| --- | ---: | ---: | ---: |
| V0 | 1200 | 1200 | 0 |
| V1 | 1260 | 60 | 1200 |
| V2 | 1323 | 63 | 1260 |
| V3 | 1389 | 66 | 1323 |
| V4 | 1458 | 69 | 1389 |

`configs/paper/oct5k-longitudinal/` contains the five frozen CSV files, hashes,
and offline generation timing. Membership uses seed 20260926 to shuffle sorted
source identities once, then takes ordered prefixes. Retained image/mask hashes,
paths, sample IDs and transform identities do not change. Forty-seven source
samples are unused. Version labels do not enter artifact keys.

Five fresh paired blocks compare Aether, incremental mmap, MONAI PersistentDataset
1.6.0 and MONAI LMDBDataset 1.6.0 (lmdb 2.1.1). There are 20 epochs per update,
batch 16, prefetch 0, tracing off, and four preprocessing passes at 256x256.
Each backend requests 108,600 training samples per complete block. Aether remains
DURABLE with asynchronous compaction and immutable-inline-admission-v1 checksums.

The same seeded backend permutation is rotated by `(block + version) % 4`.
Every backend occupies every position once per four blocks; five blocks are
balanced to within one position count. Samples retain manifest order each epoch.
Each update uses a fresh small segmentation model and AdamW optimizer, seed
`20260926 + block + version`, with the same 12 zero-input warmup steps as the
original pilot. Model setup and warmup are now charged to the lifecycle.

## Timing

The pilot's main endpoint is cumulative measured lifecycle through V4, including
V0. The primary comparison is Aether vs incremental mmap; both MONAI comparisons
are secondary. Each version sums disjoint measured intervals:

```text
startup + scanAdmission + modelSetup + training + drain + close
```

`scanAdmission` includes native dataset/store opening, index reconstruction,
missing-entry lookup, and preparation. MONAI LMDB populates eagerly in its
constructor, so these costs cannot honestly be separated without changing the
backend. Startup measures Aether service startup; other native reopening work
is inside `scanAdmission`. V0 has no model/training interval. Explicit drain
intervals require IDLE, zero debt and no compaction failures. Close includes
dataset closure and daemon shutdown, including its final diagnostic drain.
These intervals do not overlap or double-count an earlier drain.

Input content hashes are verified once per campaign invocation; canonical
references are prepared once and reused across blocks. This common preflight and
offline manifest-generation cost are reported separately. An alternative analysis
adds the common cost once per backend lifecycle, not once per week. A resume
revalidates inputs and records that overhead separately without replacing the
original common-cost measurement.

Excluded harness costs: Python worker imports/launch, reference/model checksum
validation, receipt/checkpoint copy and hashing, plotting, and result archiving.
Worker wall time and checkpoint overhead are retained separately. Thus the
endpoint is the sum of specified operational phases, not notebook execution time.
Sampled GPU utilization and synchronous input-wait time are proxies, not direct
hardware GPU-idle measurements. Linux process counters include validation and
instrumentation; Java process counters exclude its startup and exit.

Native serialization and durability differences remain as in the previous pilot:
Aether/mmap TensorDictCodec vs MONAI torch serialization; Aether durable admission,
mmap fsync, LMDB synchronous transactions, and no matching PersistentDataset fsync
guarantee. This is not a serialization/durability equivalence experiment.

## Persistence And Resume

Each `(block, version, backend)` runs in a fresh Python process; Aether gets a
new Java daemon each time. Existing cache directories survive within a block.
Each stage must admit exactly the expected delta, retain all unique artifacts,
perform zero training-time preprocessing, and match canonical tensor checksums.
Paired initial/final model hashes, seeds and sample order must also match.

Completed stages have atomic checksummed receipts bound to protocol, source and
environment hashes. Closed stores have independently hashed checkpoints.
An interrupted, uncommitted live store is discarded and restored from the last
verified checkpoint before retry. A living interrupted worker/service, altered
receipt, changed checkpoint, source drift, or environment drift fails closed.
Completed stages are skipped only after receipt validation. Checkpoints are
removed only after newer receipts commit; all caches are removed only after a
complete paired block validates and its result-only ZIP is written.

Checkpoints are recovery instrumentation, not a commercial cache requirement.
Their copying/hashing warms OS page caches and is excluded from the endpoint.
Process restart is not an OS page-cache reset. Resuming requires retained scratch
space and the same environment; a result-only ZIP cannot reconstruct caches.

Capacity preflight budgets four stores plus checkpoint copies, format overhead,
and reserve. This is a conservative estimate, not a filesystem reservation.
Logical/allocated bytes and one-second sampled live-store peak bytes are recorded;
the peak is a sampled lower bound, not an exact instantaneous maximum. Whole-block
scratch bytes after each checkpoint are also recorded separately.

## Execution

```powershell
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py prepare --mode longitudinal --no-server-trace --dataset-source grgur321/aether-oct5k-pilot
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py upload-source --update
build/kaggle-venv/Scripts/python.exe scripts/kaggle_remote.py run
```

Prepare requires a clean committed snapshot. Source and notebook manifest hashes
must match before launch. The notebook first runs one complete V0-V4 GPU smoke
block with one epoch/update, then starts five fresh 20-epoch pilot blocks only if
the smoke passes. Smoke results and stores are separate and excluded from pilot
statistics. No additional image dataset is uploaded: the existing OCT5K dataset
is reused, with frozen membership CSVs included in the source snapshot.

`longitudinal_comparison.py --resume` validates the existing receipts and stores;
it does not import blocks from another campaign. A result-only checkpoint ZIP is
written after every complete block. Final plots show cumulative wall time,
paired log-ratio means with descriptive 95% CIs, and per-version phase costs.
Observed break-even is descriptive. No confirmatory or financial payback claim
is licensed by this pilot, regardless of its outcome.
