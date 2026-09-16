# Kaggle GPU Tutorial

For the TPDS Java-engine protocol, 24 paired blocks and the current artifact workflow, use
[kaggle/README.md](../../kaggle/README.md). This older tutorial describes the Python prototype
and its results must not be pooled with the Java-engine campaign.

This tutorial runs the corrected AetherML GPU segmentation benchmark on Kaggle with 3 independent
runs. It targets Kaggle's NVIDIA CUDA GPU runtime and stores all reports under `/kaggle/working`,
which Kaggle preserves as notebook output.

## 1. Create The Notebook

In Kaggle:

1. Create a new Python notebook.
2. Open the notebook Settings panel.
3. Set `Accelerator` to `GPU`.
4. Set `Internet` to `On` so `git clone` and `pip install` can run.

Kaggle notebooks have limited session time, so keep the first run at `--runs 3`. The final
publication-scale audit still requires an explicit `--runs 10` on a stable host.

## 2. Verify The GPU

Run this notebook cell:

```python
!nvidia-smi
import torch

print("torch:", torch.__version__)
print("cuda_version:", torch.version.cuda)
print("cuda_available:", torch.cuda.is_available())
if torch.cuda.is_available():
    print("gpu:", torch.cuda.get_device_name(0))
    print("vram_bytes:", torch.cuda.get_device_properties(0).total_memory)
```

Expected result:

```text
cuda_available: True
gpu: <NVIDIA GPU name>
```

If `cuda_available` is `False`, confirm the notebook accelerator is set to GPU and restart the
session.

## 3. Clone Aether

For a public repository:

```python
%cd /kaggle/working
!git clone <your-repository-url> aether-engine
%cd /kaggle/working/aether-engine
```

For a private repository, either attach a Kaggle Dataset containing the repository snapshot, or use
a Kaggle secret/token in the clone URL. Do not hard-code a private token in the notebook.

## 4. Install Python Dependencies

Kaggle GPU images normally include CUDA PyTorch already. Install the local Aether Python client and
small Python dependencies without replacing Kaggle's CUDA PyTorch wheel:

```python
%cd /kaggle/working/aether-engine
!python -m pip install -e clients/python numpy tiktoken
```

Recheck PyTorch:

```python
import torch

assert torch.cuda.is_available(), "Kaggle GPU is not visible to PyTorch"
assert torch.version.cuda, "Installed PyTorch is not a CUDA build"
print(torch.__version__, torch.version.cuda, torch.cuda.get_device_name(0))
```

If this fails after package installation, restart the Kaggle session and rerun the cells without
installing or upgrading `torch`.

## 5. Run A Small Acceptance Gate

This confirms the benchmark executes before the longer 3-run measurement:

```python
%cd /kaggle/working/aether-engine
!PYTHONPATH=clients/python python clients/python/aether_bench.py \
  --profile gpu-acceptance \
  --accelerator-backend cuda \
  --expected-gpu NVIDIA \
  --samples 128 \
  --height 128 \
  --width 128 \
  --resize 128 \
  --batch-size 8 \
  --epochs 2 \
  --workers 0 \
  --output-dir /kaggle/working/aether-bench-gpu-kaggle-acceptance
```

The acceptance report is stored at:

```text
/kaggle/working/aether-bench-gpu-kaggle-acceptance/aetherml-report.json
```

## 6. Run The Corrected 3-Run Benchmark

```python
%cd /kaggle/working/aether-engine
!PYTHONPATH=clients/python python clients/python/aether_bench.py \
  --profile gpu-training \
  --samples 1024 \
  --height 256 \
  --width 256 \
  --resize 256 \
  --batch-size 16 \
  --epochs 5 \
  --workers 0 \
  --changed-percent 30 \
  --seed 42 \
  --runs 3 \
  --preprocess-passes 1 \
  --initial-cache-hit-ratio 100 \
  --prefetch-batches 1 \
  --accelerator-backend cuda \
  --expected-gpu NVIDIA \
  --output-dir /kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run
```

The main outputs are:

```text
/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/aetherml-report.json
/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/gpu-training.json
/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/tidy-gpu-backends.csv
/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/tidy-gpu-runs.csv
/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/tidy-gpu-steps.csv
/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/tidy-gpu-cache-dynamics.csv
```

## 7. Inspect The Result

Run:

```python
import json
from pathlib import Path

report_path = Path("/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run/aetherml-report.json")
report = json.loads(report_path.read_text())
gpu = report["keyStats"]["gpuTraining"]

print("status:", gpu["status"])
print("runs:", gpu["runs"])
print("device:", gpu["accelerator"]["deviceName"])
print("backend:", gpu["accelerator"].get("backend"))
print("RAW samples/s:", gpu["backends"]["RAW_RECOMPUTE"]["samplesPerSecond"])
print("Aether samples/s:", gpu["backends"]["AETHER_CACHE"]["samplesPerSecond"])
print("Aether/RAW:", gpu["outcome"]["samplesPerSecondRatio"]["mean"])
print("cache lookups:", gpu["cacheDynamics"]["lookups"]["mean"])
print("Aether epoch blocks:", len(report["reportData"]["benchmark_gpu_segmentation"]["backends"]["AETHER_CACHE"]["epochWallMs"]))
print("all images contiguous:", report["reportData"]["benchmark_gpu_segmentation"]["backends"]["AETHER_CACHE"]["tensorLayout"]["allImagesContiguous"])
```

Expected sanity checks for this corrected 3-run configuration:

```text
status: PASSED
runs: 3
backend: cuda
cache lookups: 5120
Aether epoch blocks: 5
all images contiguous: True
```

If `cache lookups` is `5312` or `Aether epoch blocks` is `6`, you are not running the corrected
benchmark code.

## 8. Package The Outputs

Kaggle saves files under `/kaggle/working` as notebook output. Create a compact archive for download
or for creating a Kaggle Dataset from the notebook output:

```python
%cd /kaggle/working
!zip -r aether-bench-gpu-kaggle-warm-prefetch1-3run.zip \
  aether-bench-gpu-kaggle-warm-prefetch1-3run \
  aether-bench-gpu-kaggle-acceptance
```

Optional quick file links:

```python
from IPython.display import FileLink

FileLink("/kaggle/working/aether-bench-gpu-kaggle-warm-prefetch1-3run.zip")
```

## 9. Common Failures

`torch.cuda.is_available() = False`

The notebook is not using a GPU runtime, or the session needs a restart after changing the
accelerator.

`nvidia-smi` works but benchmark reports `SKIPPED_UNSUPPORTED_ACCELERATOR`

Check that the command includes:

```text
--accelerator-backend cuda
--expected-gpu NVIDIA
```

Also inspect `skipReason` in `aetherml-report.json`.

The notebook times out

Keep `--runs 3`, avoid larger resolutions, and preserve outputs after each successful run. Move the
10-run final test to a more stable remote GPU host.

Package installation breaks CUDA

Restart the session and do not reinstall `torch`. Kaggle's base GPU image usually already provides
a CUDA-enabled PyTorch build.

## Sources

- Kaggle Notebooks documentation: https://www.kaggle.com/docs/notebooks
- Kaggle Packages and Dependency Manager documentation: https://www.kaggle.com/docs/packages
- Kaggle Datasets documentation for notebook output datasets: https://www.kaggle.com/docs/datasets
