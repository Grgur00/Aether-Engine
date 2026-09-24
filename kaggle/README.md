# Kaggle execution

The paper runner measures the **Java engine**. The older tutorial in `clients/python/` describes a separate Python prototype. Do not combine their results as replicas.

Open [aether_paper.ipynb](aether_paper.ipynb) in Kaggle for the executable notebook workflow. The commands below are also available independently.

## Transfer the current implementation

Build a source archive locally with `python scripts/package_artifact.py`. Upload `build/aether-paper-artifact.zip` as a private Kaggle input and extract it into `/kaggle/working/Aether-Engine`. Alternatively clone a **committed, frozen** revision of the repository. A clone of an older public commit does not contain local uncommitted work.

The archive records the originating Git commit, dirty status and every included file checksum. A dirty source archive is suitable for smoke/pilot work, but is not a confirmatory code freeze. Commit the reviewed source, rebuild the archive and freeze the protocol before confirmatory measurements.

In the notebook, set `SOURCE_ARCHIVE` to the uploaded ZIP to execute local changes. Leaving it `None` uses the Git repository and `GIT_REF` instead. Both branches initialize source provenance before the confirmatory guard; the archive branch verifies every source hash and rejects a different existing extraction.

Enable a CUDA GPU and Internet for dependency setup. Run in a notebook cell:

```python
import subprocess, json
from pathlib import Path
REPO = Path('/kaggle/working/Aether-Engine')
subprocess.run(['bash', str(REPO / 'kaggle/setup.sh')], check=True)
import os
runtime = json.loads(Path('/kaggle/working/aether-paper-runtime.json').read_text())
os.environ['JAVA_HOME'] = runtime['javaHome']
os.environ['PATH'] = runtime['javaHome'] + '/bin:' + os.environ['PATH']
PY = '/kaggle/working/aether-paper-venv/bin/python'
subprocess.run([PY, str(REPO / 'scripts/artifact_smoke.py'),
                '--output', '/kaggle/working/aether-results/smoke'], cwd=REPO, check=True)
```

This uses Kaggle's installed CUDA PyTorch through an isolated environment. It does not replace the notebook's GPU stack. JDK 21 is required. Keep one software/hardware configuration for a campaign; every block records it. Neither OS page-cache flushing nor fixed GPU clocks is claimed on Kaggle.

For a non-Kaggle Linux CUDA host, run `bash scripts/remote-linux-setup.sh` from the repository root. Add `--with-dali` when preparing the separate DALI campaign. The script builds the Java runtime classpath and runs a CUDA kernel before any benchmark begins.

## Attach and prepare data

Use your actual OCT5K V1/V2 manifests (1170/1505 rows, 1003 reusable). Image and mask paths must resolve to the attached data; SHA-256 values are verified. Place configuration **outside the Git checkout** so local path customization does not dirty the frozen source:

If starting from a full OCT manifest, `scripts/prepare_evolution.py --manifest FULL.csv --output /kaggle/working/aether-data/oct5k` creates exactly these membership counts with a recorded seed. Point the configuration at its `v1.csv` and `v2.csv`. Existing valid manifests should be retained for direct protocol replication.

```python
import json
config = json.loads((REPO / 'configs/paper/datasets.json').read_text())
config['oct5k']['manifestV1'] = '/kaggle/working/aether-data/oct5k-v1.csv'
config['oct5k']['manifestV2'] = '/kaggle/working/aether-data/oct5k-v2.csv'
CONFIG = Path('/kaggle/working/aether-datasets.json')
CONFIG.write_text(json.dumps(config, indent=2))
```

Validate every attached path and required cardinality before starting a campaign:

```bash
python scripts/validate_manifests.py --config /kaggle/working/aether-datasets.json
```

Build additional manifests from datasets you are authorized to access:

```bash
python scripts/prepare_vision.py --dataset coco --image-root /kaggle/input/coco/train2017 --annotations /kaggle/input/coco/annotations/instances_train2017.json --output /kaggle/working/aether-data/coco-v2.csv
python scripts/prepare_vision.py --dataset imagenet --image-root /kaggle/input/imagenet/train --output /kaggle/working/aether-data/imagenet-v2.csv
```

COCO uses multi-label object-category presence; ImageNet uses single-label classification. Both use RGB decoding, bilinear resize, optional Gaussian passes, divide-by-255 normalization and fp16 artifact serialization. The model consumes fp32 tensors reconstructed identically for all backends. OCT remains a systems segmentation workload, with no clinical claim. Update V1 paths/cardinalities in the config, or use an explicit reuse sweep to derive V1 subsets from V2. No command downloads or redistributes licensed source images.

## Pilot and primary runs

The completed [20-epoch pilot](PILOT-20EPOCH.md) is separate from the current
[24-pair superiority campaign](CONFIRMATORY.md). The primary endpoint is now
Aether versus incremental mmap at 20 epochs with 1430 reusable and 75 new samples.
The pilot commands below retain the earlier workload for replication.

```python
def run(script, *args):
    subprocess.run([PY, str(REPO / 'scripts' / script), *map(str, args)], cwd=REPO, check=True)

run('run_matrix.py', '--config', CONFIG, '--datasets', 'oct5k', '--repeats', 10,
    '--output', '/kaggle/working/aether-results/pilot', '--resume')
run('analyze.py', '--input', '/kaggle/working/aether-results/pilot', '--pilot',
    '--output', '/kaggle/working/aether-results/pilot-analysis')

# After the clean commit/tag and protocol freeze; a fresh output is mandatory:
run('reproduce.py', 'primary', '--config', REPO / 'configs/paper/oct5k-confirmatory-v2.json', '--epochs', 20, '--prefetch-depth', 0,
    '--output', '/kaggle/working/aether-results/primary')
```

The matrix creates a fresh Java/mmap pair per block, populates V1, restarts Java, measures V2 in seeded random backend order, then retains raw reports and removes generated stores to bound disk usage. `--retain-stores` keeps caches. `--resume` skips only valid completed blocks from the same protocol and environment. An interrupted block is preserved and restarted in full; an observation timeout alone must not trigger a second runner. A changed GPU or software environment requires a separate output campaign.

The primary design fixes exactly 24 fresh blocks and twenty epochs. See
[the confirmatory protocol](CONFIRMATORY.md) for the primary mmap superiority
test, secondary equivalence analysis, manifest preparation and source freeze.
Do not change the sample count after inspecting confirmatory results.

## Scaling and failure campaigns

Use the CLI families listed in `configs/paper/evaluation.json`, for example:

```bash
python scripts/run_matrix.py --config /kaggle/working/aether-datasets.json --workers 0,2,4,8 --repeats 10 --output /kaggle/working/aether-results/workers
python scripts/run_matrix.py --config /kaggle/working/aether-datasets.json --gpu-counts 1,2 --repeats 10 --output /kaggle/working/aether-results/gpu-scaling
python scripts/concurrency_matrix.py --clients 1,2,4 --workers 0,2,4,8 --repeats 10 --output /kaggle/working/aether-results/concurrency
python scripts/fault_injection.py --trials-per-point 100 --output /kaggle/working/aether-results/durability
python scripts/transform_evolution.py --repeats 10 --output /kaggle/working/aether-results/evolution
```

Loader worker startup and inter-epoch synchronization are included in training wall time. Two GPUs use single-node `DataParallel`; this is not multi-node scaling. The concurrent-client script measures a separate synthetic storage workload with real processes, checksum verification and possible duplicate preprocessing; it is not GPU training throughput. Failure tests use external SIGKILL on Kaggle, test both single/batch publication and record visible/acknowledged artifacts, corruption, orphan bytes and restart time. They do not simulate power failure.

Large sweeps require enough real manifest rows and disk. Use `--backends raw,aether,mmap` for cardinality scaling without the RAM upper bound: the reference is then generated incrementally with bounded tensor memory. The supplied size campaign selects this mode. Four-backend runs explicitly bound reference/RAM memory; `--max-reference-bytes` can be raised on a capable host. Record any excluded points before final analysis.

Set notebook `SCRATCH_ROOT`, or pass `--scratch-root /your/writable/scratch` to `run_matrix.py`, `dali_comparison.py` or `reproduce.py`, to place generated Java/mmap stores on another filesystem. Reports stay under `--output`. The scratch path is frozen into the protocol. Each block records its free space and an uncompressed payload lower bound before it starts; metadata and temporary writes can require more space. Insufficient capacity stops the requested experiment without reducing its cardinality. Completed stores are removed unless retained explicitly; interrupted stores remain at the path in the workspace report for inspection. Scratch retention is not a substitute for saving the results directory.

## Save evidence

The final export cell in either standalone notebook creates
`/kaggle/working/aether-results-only.zip`, including all reports, logs, manifests,
block files, runtime metadata and `SHA256SUMS`. Run that cell after collecting
results, or manually after a failed earlier cell to preserve partial results.
It keeps the working results directory available for interactive use.
Prepared VS Code/CLI notebooks export automatically, including on Python-level
setup/experiment failures, and remove their temporary directories after archive
verification. Download just the ZIP using `scripts/kaggle_remote.py outputs`.

After extracting the ZIP, its contents are the original result-directory layout.
You can verify them with the checksum script:

```bash
python scripts/checksums.py /kaggle/working/aether-results --verify
```

Return those outputs for analysis/manuscript completion. `submission_gate.py` stays false until the required evidence and independent reproduction exist. Missing results are never replaced by the preliminary numbers from the research plan.

The gate reports automated measurement coverage and support for the planned statistical claims separately. `submissionReady` remains false pending human review, including independent reproduction. A failed equivalence test does not erase otherwise complete measurements.

Use `--resume` for the concurrency and fault commands to continue the same frozen campaign. Saved results and environment metadata are verified before reuse, and concurrent runners cannot share one output directory. A new Kaggle host, software change or source change requires a separate campaign; previous results remain available. Always rerun the Gradle classpath task after rebuilding or extracting code: managed Java launches reject stale source/classes/dependency hashes.

## DALI runtime validation and separate comparison

After the basic Java smoke, install the optional CUDA 12 DALI dependency and test the integration with generated fixtures:

```python
subprocess.run([PY, '-m', 'pip', 'install', '-r', str(REPO / 'env/requirements-dali.lock')], check=True)
subprocess.run([PY, str(REPO / 'scripts/validate_gpu.py'), '--require-dali'], cwd=REPO, check=True)
run('dali_smoke.py', '--output', RESULTS / 'dali-smoke')
```

Here `RESULTS` is your `/kaggle/working/aether-results` path (defined in the notebook). This smoke needs no licensed dataset. Once it passes, configure real COCO/ImageNet manifests and run:

```python
run('dali_comparison.py', '--config', CONFIG, '--datasets', 'coco,imagenet', '--repeats', 12,
    '--resume', '--output', RESULTS / 'dali')
run('dali_analyze.py', '--input', RESULTS / 'dali', '--output', RESULTS / 'dali/processed')
```

This separate workload compares direct GPU DALI against caches of identical DALI outputs. Exact canonical-payload and final-model checks are mandatory; incompatible runtime behavior stops the campaign. Install DALI before freezing the software environment for the affected campaign. Its figures explicitly label `DALI direct`; generated smoke fixtures cannot enter research analysis.
# VS Code remote runs

For local login, private notebook submission, logs and result downloads, follow
[Run Aether on Kaggle from VS Code](VSCODE.md). The installed tasks use the isolated
Kaggle CLI and a verified source snapshot of your working tree.
