# GPU Training Validation

The AetherML GPU validation profile supports NVIDIA CUDA and AMD ROCm through PyTorch's `cuda`
device API. Use `--accelerator-backend cuda` on NVIDIA hosts and `--accelerator-backend rocm` on
AMD ROCm hosts. Leave `--expected-gpu` empty for any compatible GPU, or set it to a substring such
as `NVIDIA` or `AMD Radeon RX 7900` when the report should enforce the hardware identity.

From the project virtual environment, NumPy must import without warnings:

```powershell
C:\Users\Korisnik\Desktop\Projects\Aether-Engine\.venv\Scripts\python.exe -m pip install numpy
C:\Users\Korisnik\Desktop\Projects\Aether-Engine\.venv\Scripts\python.exe -c "import numpy, torch; print('numpy', numpy.__version__); print('torch', torch.__version__)"
```

Run the fast correctness profile:

```powershell
$env:PYTHONPATH='clients\python'
python clients\python\aether_bench.py --profile smoke --output-dir build\aether-bench-smoke
```

Run a quick GPU acceptance profile:

```powershell
$env:PYTHONPATH='clients\python'
python clients\python\aether_bench.py `
  --profile gpu-acceptance `
  --accelerator-backend auto `
  --samples 128 `
  --height 128 `
  --width 128 `
  --resize 128 `
  --batch-size 8 `
  --epochs 2 `
  --workers 0 `
  --output-dir build\aether-bench-gpu-acceptance
```

For your shutdown-constrained local machine, use the corrected 3-run warm steady-state
configuration:

```powershell
$env:PYTHONPATH='clients\python'
python clients\python\aether_bench.py `
  --profile gpu-training `
  --dataset oct `
  --pipeline segmentation `
  --samples 1024 `
  --height 256 `
  --width 256 `
  --resize 256 `
  --batch-size 16 `
  --epochs 5 `
  --workers 0 `
  --changed-percent 30 `
  --seed 42 `
  --runs 3 `
  --preprocess-passes 1 `
  --initial-cache-hit-ratio 100 `
  --prefetch-batches 1 `
  --output-dir build\aether-bench-gpu-warm-prefetch1-corrected
```

The corrected local 3-run result is stored in:

```text
build\aether-bench-gpu-warm-prefetch1-corrected\aetherml-report.json
build\aether-bench-gpu-warm-prefetch1-corrected\gpu-training.json
build\aether-bench-gpu-warm-prefetch1-corrected\tidy-gpu-backends.csv
build\aether-bench-gpu-warm-prefetch1-corrected\tidy-gpu-runs.csv
build\aether-bench-gpu-warm-prefetch1-corrected\tidy-gpu-steps.csv
build\aether-bench-gpu-warm-prefetch1-corrected\tidy-gpu-cache-dynamics.csv
```

`gpu-acceptance` and `gpu-training` default to 3 independent runs so unstable local machines do not
launch a 10-run job by accident. The final evidence audit still requires an explicit `--runs 10`
before declaring a complete GPU training claim.

The combined report contains the important statistics in one JSON file:

```text
keyStats.gpuTraining
reportData.benchmark_gpu_segmentation.runAggregate
reportData.benchmark_gpu_segmentation.runAggregate.aetherOperationMetrics
reportData.benchmark_gpu_segmentation.runAggregate.cacheDynamics
reportData.benchmark_gpu_segmentation.cacheDynamics
reportData.benchmark_gpu_segmentation.outcome
reportData.benchmark_gpu_segmentation.runs
reportData.benchmark_gpu_segmentation.runs[].outcome
reportData.benchmark_gpu_segmentation.runs[].cacheDynamics
reportData.benchmark_gpu_segmentation.runs[].backends[].steps
evidenceAudit
```

Figure-friendly exports are written beside the combined report:

```text
exports.json
tidy-gpu-backends.csv
tidy-gpu-runs.csv
tidy-gpu-steps.csv
tidy-gpu-cache-dynamics.csv
```

For `gpu-training`, the report is valid only when:

```text
accelerator.deviceAvailable = true
configuration.acceleratorBackend = cuda|rocm|auto
accelerator.cudaVersion != null for --accelerator-backend cuda
accelerator.hipVersion != null for --accelerator-backend rocm
accelerator.deviceName matches expectedGpu when expectedGpu is configured
deviceSmokeTestPassed = true
model.device = cuda:0
correctness.allChecksumsEqual = true
validity.deterministicCacheInvalidation.passed = true
protocol.singleGetRequests = 0
protocol.singlePutRequests = 0
```

If those accelerator checks fail, the benchmark writes `status = SKIPPED_UNSUPPORTED_ACCELERATOR`
and does not fall back to CPU.

GPU utilization is sampled with `nvidia-smi` first, then AMD SMI/ROCm SMI tooling when available,
and summarized under each backend's `gpu` field. On WSL, PyTorch ROCm can work through `/dev/dxg`
while `amd-smi`/`rocm-smi` still report `amdgpu` unavailable; in that case the benchmark keeps
running and records `gpu.sampling.status = UNAVAILABLE`.

The GPU profile measures `RAW_RECOMPUTE`, `AETHER_CACHE`, `STATIC_PREPROCESSED_MMAP`, and
`RAM_READY`, with per-step input wait, host-to-device transfer, forward, loss, backward, optimizer,
prefetch wait, and wall-time records. Step records also include normalized CPU tensor layout:
contiguity, stride, expected stride, pinned state, storage offset, memory format, and data pointer
alignment.

## Remote NVIDIA CUDA Quick Start

On an Ubuntu NVIDIA GPU host:

```bash
git clone <your-repository-url> aether-engine
cd aether-engine
bash scripts/cuda-remote-setup.sh
source "$HOME/.venv-aether-cuda/bin/activate"
PYTHONPATH=clients/python python clients/python/aether_bench.py \
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
  --output-dir build/aether-bench-gpu-nvidia-warm-prefetch1-3run
```

The remote NVIDIA 3-run report will be stored in:

```text
build/aether-bench-gpu-nvidia-warm-prefetch1-3run/aetherml-report.json
build/aether-bench-gpu-nvidia-warm-prefetch1-3run/gpu-training.json
build/aether-bench-gpu-nvidia-warm-prefetch1-3run/tidy-gpu-backends.csv
build/aether-bench-gpu-nvidia-warm-prefetch1-3run/tidy-gpu-runs.csv
build/aether-bench-gpu-nvidia-warm-prefetch1-3run/tidy-gpu-steps.csv
build/aether-bench-gpu-nvidia-warm-prefetch1-3run/tidy-gpu-cache-dynamics.csv
```

For Kaggle, use the notebook-specific walkthrough in
[`KAGGLE_GPU_TUTORIAL.md`](KAGGLE_GPU_TUTORIAL.md). It keeps the same corrected 3-run CUDA command,
but uses `/kaggle/working` for persisted reports and avoids replacing Kaggle's preinstalled CUDA
PyTorch build.

## Input-Bound Regime

To test the input-bound regime, increase deterministic preprocessing cost and keep it recorded in
the cache fingerprint:

```bash
PYTHONPATH=clients/python python clients/python/aether_bench.py \
  --profile gpu-training \
  --samples 1024 \
  --height 256 \
  --width 256 \
  --resize 256 \
  --batch-size 16 \
  --epochs 5 \
  --runs 3 \
  --preprocess-passes 8 \
  --initial-cache-hit-ratio 75 \
  --prefetch-batches 2 \
  --accelerator-backend auto \
  --output-dir build/aether-bench-gpu-preprocess-8
```

To generate the remaining GPU experiment matrix without launching every long run:

```bash
PYTHONPATH=clients/python python clients/python/aether_bench.py \
  --profile smoke \
  --sweep-plan gpu-required \
  --plan-only \
  --accelerator-backend cuda \
  --expected-gpu NVIDIA \
  --output-dir build/aether-bench-gpu-sweep-plan
```

The combined report then includes `sweepPlan.variants` with reproducible commands for model-size,
resolution, changed-percent, hit-ratio, preprocessing-pass, worker, and prefetch experiments.
Worker variants are still marked as planned until fair multiprocessing is implemented.

## AMD Radeon RX 7900 Through WSL2

Use Ubuntu WSL2 with the current Windows AMD driver. The existing Windows checkout is mounted in
WSL, so a second clone is unnecessary. From PowerShell, verify WSL:

```powershell
wsl --status
wsl --list --verbose
```

Inside Ubuntu, use the existing Windows checkout and run the ROCm setup script:

```bash
cd /mnt/c/Users/Korisnik/Desktop/Projects/Aether-Engine
AETHER_REPO_ROOT="$PWD" \
AETHER_VENV="$HOME/.venv-rocm" \
bash scripts/rocm-wsl-setup.sh
```

Then run the corrected 3-run profile inside Ubuntu:

```bash
source "$HOME/.venv-rocm/bin/activate"
cd /mnt/c/Users/Korisnik/Desktop/Projects/Aether-Engine
PYTHONPATH=clients/python python clients/python/aether_bench.py \
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
  --accelerator-backend rocm \
  --expected-gpu "AMD Radeon RX 7900" \
  --output-dir build/aether-bench-gpu-warm-prefetch1-corrected
```
