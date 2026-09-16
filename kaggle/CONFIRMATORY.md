# Frozen OCT5K confirmatory campaign

This protocol is separate from every pilot, including the previous ten-repeat
campaigns. No confirmatory measurements were collected while preparing it.

The sole primary claim is throughput equivalence of Aether and pre-materialized
mmap over ten V2 training epochs following V1 population and restart. Each block
uses OCT5K V1=1170, V2=1505, reusable=1003 and new/changed=502. Collect exactly
24 fresh paired blocks, with seeded randomized order of raw, Aether, mmap and RAM
within each block. Hold batch size at 16, epochs at 10, prefetch depth at 0,
server tracing off, asynchronous compaction on, and checksum policy at
`immutable-inline-admission-v1`. The established controls remain workers=0,
preprocessing passes=4, image size=256, train split and one CUDA GPU.

`scripts/confirmatory.py` is the machine-readable specification. Changes to the
implementation or protocol require a new campaign; never tune after inspecting
confirmatory results. Exactly 24 complete blocks are required before the primary
analysis runs. A failed equivalence test is a reportable result, not a reason to
collect more blocks. Preserve interrupted evidence; the runner restarts an
interrupted paired block in full. Resume only the same frozen protocol and
environment. Never import pilot blocks, even if their settings match.

## Endpoint and analysis

For each paired block use the existing `steadyState.effectiveSamplesPerSecond`
field: 15050 processed samples divided by total ten-epoch V2 training wall time.
The field name does not mean that the first epoch is discarded. Aether's inline
admission of 502 new/changed samples is included. V1 population, mmap
pre-materialization, reference/checksum validation, daemon startup and the
post-training background-compaction drain are excluded. This claim compares
training throughput against pre-materialized mmap; it is not equivalence of
total setup-plus-training cost. Report the saved lifecycle costs separately.

Analyze paired `log(Aether/mmap)` using a t-based TOST at alpha=0.05 against
`log(0.97)` and `log(1.03)`. Pass only if the exponentiated 90% CI is strictly
inside 0.97–1.03. There is one primary hypothesis; no Holm adjustment with the
secondary result. The secondary Aether/raw test is a one-sided paired log-ratio
t-test of H0: mean log ratio <= 0 against H1: mean log ratio > 0 at alpha=0.05.
RAM is descriptive, with no hypothesis of outperforming it.

The analyzer also derives descriptive cumulative epoch 1 through 10 curves from
these same blocks, both with and without V2 preparation cost. Summed epoch walls
exclude inter-epoch overhead, so these curves are explicitly distinct from the
primary total-training-wall endpoint. No epoch is selected after seeing results.

## Local source freeze and notebook preparation

Preserve generated pilot outputs under ignored `build/` or `results/` before
cleaning the source tree. Run Python orchestration/statistics tests and the
engine, SSTable and training-cache Java tests. Commit the final implementation,
tag it `aether-paper-v1`, and record `git rev-parse HEAD`. Require an empty
`git status --porcelain`. Do not reuse a tag for revised source.

Prepare the notebook from that clean commit (PowerShell, repository root):

```powershell
build\kaggle-venv\Scripts\python.exe scripts\kaggle_remote.py prepare --mode primary --epochs 10 --prefetch-depth 0 --no-server-trace
```

This uses the existing local Kaggle username and attached-data configuration.
Preparation creates `build/kaggle/source/aether-paper-artifact.zip` with a fresh
`artifact-provenance.json`, records the commit and every packaged source hash,
and creates `build/kaggle/notebook/aether.ipynb`. `prepared.json` binds the notebook
and source archive together. Preparation is local and uploads nothing.

After reviewing the prepared files, upload that exact archive and run that exact
notebook. Keep source unchanged. The prepared runner verifies source hashes and
requires clean source provenance before collecting confirmatory evidence.

## Equivalent Kaggle command

From the extracted, verified source root after running `kaggle/setup.sh`:

```bash
/kaggle/working/aether-paper-venv/bin/python scripts/reproduce.py primary \
  --config /kaggle/input/aether-oct5k-pilot/aether-datasets.json \
  --epochs 10 --prefetch-depth 0 \
  --output /kaggle/working/aether-results/confirmatory-v1
```

The input dataset slug contains `pilot`; it supplies manifests and source data,
not measurements. Use the same validated manifests. The output must be a fresh
campaign directory. `primary` fixes N=24 and enables `--confirmatory`; no repeat
flag is needed. It runs the analysis and figures after all blocks complete.
Configuration, source, manifest hashes and environment are frozen into evidence.
Preserve all raw reports and the final results ZIP, whether the claim passes or
fails. Local validation does not establish a throughput result.
