# Archived OCT5K 20-epoch pilot

The completed pilot informed the [new confirmatory protocol](CONFIRMATORY.md).
The commands below document the pilot only; never reuse its blocks as confirmation.

Run one fresh **pilot**, with 10 paired blocks and 20 epochs per backend.
The main pilot endpoint is the complete 20-epoch V2 training lifecycle.
Retain every epoch and report cumulative Aether/mmap throughput at epochs
1, 5, 10 and 20 from those same runs. These are pilot results, not the paper's
final confirmatory claim. A separate confirmatory campaign must be preregistered
after this pilot, with new measurements and a frozen implementation.

## Configuration

| Setting | Value |
| --- | --- |
| Dataset | OCT5K, additive V1 -> V2 update |
| V1 samples / reusable in V2 | 1430 / 1430 |
| V2 samples | 1505 |
| New samples | Exactly 75 (4.983%, approximately 5%) |
| Removed or changed samples | 0 |
| Epochs / paired blocks | 20 / 10 |
| Batch size / workers / prefetch depth | 16 / 0 / 0 |
| Server trace / asynchronous compaction | OFF / ON |
| Checksum policy | `immutable-inline-admission-v1` |
| Preprocessing passes / image size / split / GPUs | 4 / 256 / train / 1 CUDA GPU |
| Main pilot comparison | Aether/mmap over all 20 epochs |
| Secondary comparisons | Aether/raw; RAM-ready descriptive reference |

Exactly 75 new samples requires 1430 reusable samples. The old V1=1170,
reusable=1003 manifests cannot represent this update. Use a new V1 subset of
the existing 1505-sample V2, with unchanged sources and transforms. The
10-epoch checkpoint matches the old experiment's duration, but **not its
502-new/changed-sample workload**.

Each paired block starts with fresh stores, populates V1, restarts Java, and
uses seeded randomized backend order. All four backends see the same V2,
model seed and training configuration within a block. Do not import old
blocks. Keep the configuration fixed throughout this campaign.

## Prepare the revised manifests

After extracting the current source and running `kaggle/setup.sh`, run this
notebook cell from the repository root. It uses the attached dataset's V2
manifest as the source pool. Preparation refuses to overwrite manifests.

```python
import json
import subprocess
from pathlib import Path

PY = '/kaggle/working/aether-paper-venv/bin/python'
old = json.loads(Path('/kaggle/input/aether-oct5k-pilot/aether-datasets.json').read_text())
subprocess.run([
    PY, 'scripts/prepare_evolution.py',
    '--manifest', old['oct5k']['manifestV2'],
    '--output', '/kaggle/working/aether-data/oct5k-75-new',
    '--v1-size', '1430', '--v2-size', '1505', '--reusable', '1430',
    '--seed', '20260924',
], check=True)
subprocess.run([
    PY, 'scripts/validate_manifests.py',
    '--config', 'configs/paper/oct5k-pilot-20ep.json', '--datasets', 'oct5k',
], check=True)
```

`evolution.json` records the seed, counts and manifest hashes. Keep it with
the source archive and results. The runner retains each block's manifests
and hashes. Resolve any changed attachment paths before starting the pilot.

## Run the pilot

From the extracted source root, after the manifest preparation above:

```bash
/kaggle/working/aether-paper-venv/bin/python scripts/reproduce.py pilot \
  --config configs/paper/oct5k-pilot-20ep.json \
  --pilot-repeats 10 --epochs 20 --prefetch-depth 0 \
  --output /kaggle/working/aether-results/pilot-20ep-75new-v1
```

Tracing is off by default. Start with a fresh output directory. Resume only
with the same source, manifests, settings and environment. The runner rejects
incompatible evidence and preserves interrupted blocks. Use `pilot` for this
campaign. The current `primary` protocol is documented in CONFIRMATORY.md.

## Report the measurements

Analysis runs automatically after all 10 blocks. Retain every `training.json`,
plus `pilot-analysis/analysis.json`, `amortization.csv` (all 20 epochs) and
`checkpoints.csv` (1, 5, 10, 20).

| Cumulative checkpoint | Interpretation |
| --- | --- |
| Epoch 1 | Initial admission and first training pass |
| Epochs 1-5 | Early amortization |
| Epochs 1-10 | Same duration as the earlier experiment |
| Epochs 1-20 | Main pilot training duration |

For block i and checkpoint k, throughput is `(1505 * k) / cumulative epoch
seconds`. The paired Aether/mmap ratio is mmap's cumulative time divided by
Aether's cumulative time. Report geometric means and descriptive t-based
90% CIs of paired log ratios. These checkpoint CIs are exploratory; do not
select a favorable epoch or treat a CI inside 0.97-1.03 as confirmation.

The main endpoint uses `steadyState.effectiveSamplesPerSecond`: 30100 samples
divided by total 20-epoch V2 training wall time, including epoch 1. Curves sum
`epochWallMs`, excluding inter-epoch overhead, so their epoch-20 ratio can
differ slightly. V1 population, reference/checksum validation, daemon startup
and post-training compaction drain are outside the training endpoint; report
the saved lifecycle costs separately.

## Incremental mmap and update costs

The existing mmap baseline is **incremental**: it reuses 1430 V1 artifacts and
prepares/appends only 75 missing artifacts inline during epoch 1. Its enum name
`STATIC_PREPROCESSED_MMAP` does not mean all of V2 was materialized before
timing. Aether also admits missing artifacts inline. Neither side rebuilds
all of V2. Check `mmapDynamics.initialReusableEntries`, `initialMissingEntries`,
`entriesAppended` and Aether's `cacheDynamics` in the raw reports.

`secondaryUpdateCosts` reports each block's first-epoch wall time, input
preparation time, source/preprocessing component and initial reuse for both
backends. Input preparation includes cache hits and reuse checks as well as
new-sample admission. It is **not standalone total dataset-update latency**;
the source/preprocessing component is already included, so do not add it again.
A separate total-update cost claim requires an update-only measurement with
equivalent timing boundaries and durability completion for both stores.
Do not estimate it by subtracting later epoch times.

More epochs may amortize preparation cost; they do not make retrieval faster.
Report both epoch 1 and epoch 20, including unfavorable results.

## Later confirmation

The earlier [10-epoch confirmatory V1 protocol](CONFIRMATORY-V1.md) is archived
separately. Before a future 20-epoch confirmatory run, freeze the workload, N,
endpoint, +/-3% margin and hypotheses in both code and the preregistration.
Then test, commit, tag, record the SHA and regenerate `artifact-provenance.json`
and the notebook from the exact clean source. Upload that snapshot and collect
fresh blocks without tuning on the results. Pilot observations cannot become
confirmatory evidence.
