# Frozen 20-epoch append-only superiority campaign

Protocol ID: `oct5k-20epoch-append75-superiority-v2`.
This specification is fixed before any confirmatory block is collected.
The completed [10-pair pilot](PILOT-20EPOCH.md) is planning evidence only.
The [earlier equivalence protocol](CONFIRMATORY-V1.md) is a separate design.

## Primary question

Under an append-only update from 1430 to 1505 OCT5K samples, is Aether's
20-epoch V2 training throughput greater than the incremental mmap baseline?

For each of exactly **24 fresh paired blocks**, compute
`log(Aether effective throughput / mmap effective throughput)`.
Test H0: mean log ratio <= 0 against H1: mean log ratio > 0 with a one-sided
paired t-test at alpha=0.05 (23 degrees of freedom). Success requires
`p < 0.05`, equivalently a one-sided 95% lower confidence bound above 1.
Report the geometric mean ratio, percentage difference, paired log SD,
one-sided p-value and lower bound, and a two-sided 95% CI.

This is the only primary hypothesis. Freeze N=24 without interim inference,
optional stopping, additional blocks after failure, or post-hoc endpoint
selection. A failed primary test remains a reportable result. Preserve
interrupted evidence and restart an interrupted paired block in full; never
drop a completed block because its timings are unfavorable.

## Fixed configuration

| Setting | Frozen value |
| --- | --- |
| Dataset | OCT5K, additive V1 -> V2 update |
| V1 / V2 | 1430 / 1505 samples |
| Reusable / new / removed or changed | 1430 / 75 / 0 |
| Reuse | 95.0166% (approximately 95%) |
| Epochs / paired blocks | 20 / 24 |
| Batch / workers / prefetch | 16 / 0 / 0 |
| Server trace / asynchronous compaction | OFF / ON |
| Integrity policy / durability | `immutable-inline-admission-v1` / DURABLE |
| Preprocessing / image size / split | 4 passes / 256 / train |
| Model / augmentation / pipeline | small / light / `paper-v1` |
| Normalization / OCT transform | scale 1, offset 0 / `oct5k-v1` |
| Hardware | one CUDA GPU per backend, same environment for all blocks |
| Backends | raw, Aether, incremental mmap, RAM-ready |
| Backend order | unchanged seeded random permutation within each block |
| Fresh block seeds | 20260924 through 20260947 inclusive |

The pilot used seeds 20260904 through 20260913. Its measurements are never
imported, pooled or relabeled. Each confirmatory block starts with new stores,
populates V1, restarts Java, then trains on V2 with all four backends paired
on the same dataset, model seed and configuration. The engine, model and
preprocessing implementation must match the pilot snapshot.

Use the pilot's exact membership and unchanged source contents. The frozen
config is `configs/paper/oct5k-confirmatory-v2.json`; generation uses the same
V2 source manifest and membership seed 20260924 as the pilot. The runner
rejects different manifest bytes before collecting blocks:

| Manifest | SHA-256 |
| --- | --- |
| V1 | `ca84f70fac9c253c612fd09b69fb6640e1788131f58470a44daecbe7f8d00979` |
| V2 | `892141e67b567f3443f4397a55e4a95b6d3fde53e8814baad4dbfd8bb1d09dc5` |

## Timing boundary

The primary endpoint is `steadyState.effectiveSamplesPerSecond`: 30100
processed samples divided by total 20-epoch V2 training wall time. Epoch 1,
inline admission of the 75 new artifacts, input loading, accelerator transfer
and model training are included for both backends. The mmap store is
incremental; it also reuses 1430 artifacts and appends only 75.

V1 population, daemon startup, reference/checksum validation and the final
background-compaction drain are excluded. The reported result is therefore
end-to-end **V2 training throughput within this timing boundary**, not total
deployment, data-preparation or update-only latency. Preserve lifecycle and
compaction reports separately. No timing instrumentation changes from pilot.

All 1505 samples are consumed every epoch. Only the newly added 75 require
deterministic preprocessing and publication, in both Aether and mmap.

## Secondary analyses

- Aether/mmap equivalence: paired log-ratio TOST with bounds log(0.97) and
  log(1.03), nominal alpha=0.05; report the two-sided 90% CI and whether it
  is strictly within 0.97-1.03. This cannot substitute for primary superiority.
- Aether/raw one-sided paired log-ratio superiority at nominal alpha=0.05.
- Cumulative epochs 1, 5, 10 and 20, and all epoch timings, descriptive only.
- RAM-ready is a descriptive reference, with no hypothesis of outperforming it.

Secondary p-values are nominal and exploratory, with no familywise claim.
They do not change the sole primary test or receive a Holm adjustment with it.
Cumulative curves sum epoch walls, excluding inter-epoch overhead; their
epoch-20 ratio can differ slightly from the primary total-wall ratio.

## Source freeze and launch

Test the protocol guards and analysis, then commit and tag the exact source
snapshot `aether-paper-20ep-superiority-v1`. Use an isolated clean checkout
when the main working tree contains unrelated work. Record the commit SHA,
tag, archive hash, notebook hash and pilot provenance before submission.
Regenerate `artifact-provenance.json` from the clean committed source.
Do not tune the engine, model, transforms or configuration after inspecting
confirmatory data. Any change requires a separately identified campaign.

Prepare from that frozen checkout (PowerShell):

```powershell
build\kaggle-venv\Scripts\python.exe scripts\kaggle_remote.py prepare --mode primary --epochs 20 --prefetch-depth 0 --no-server-trace --dataset-config configs/paper/oct5k-confirmatory-v2.json --dataset-source grgur321/aether-oct5k-pilot
```

The remote runner prepares the exact membership before collection, verifies
the clean source archive and manifest hashes, and writes fresh evidence.
Upload that archive and launch the prepared private notebook. Stop monitoring
after Kaggle reports it running; do not inspect partial effect estimates.

Equivalent command after setup and manifest preparation in Kaggle:

```bash
/kaggle/working/aether-paper-venv/bin/python scripts/reproduce.py primary \
  --config configs/paper/oct5k-confirmatory-v2.json \
  --epochs 20 --prefetch-depth 0 \
  --output /kaggle/working/aether-results/confirmatory-20ep-append75-v2
```

`primary` fixes exactly 24 fresh blocks and the new seed range. Resume only
an interrupted campaign with unchanged source, protocol and environment.
Analyze only after all 24 complete blocks validate. Preserve the results ZIP
whether the primary hypothesis succeeds or fails. No CV performance claim
is upgraded on the basis of the pilot.
