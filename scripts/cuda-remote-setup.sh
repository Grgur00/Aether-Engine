#!/usr/bin/env bash
set -euo pipefail

repo_root="${AETHER_REPO_ROOT:-$PWD}"
venv="${AETHER_VENV:-$HOME/.venv-aether-cuda}"
torch_index="${AETHER_TORCH_INDEX:-https://download.pytorch.org/whl/cu124}"

cd "$repo_root"

if ! command -v nvidia-smi >/dev/null 2>&1; then
  echo "nvidia-smi was not found. Install the NVIDIA driver on the host before setting up PyTorch." >&2
  exit 1
fi

echo "[1/4] NVIDIA driver/device"
nvidia-smi

echo "[2/4] Python virtual environment: $venv"
python3 -m venv "$venv"
source "$venv/bin/activate"
python -m pip install --upgrade pip setuptools wheel

echo "[3/4] Installing Aether Python client and CUDA PyTorch"
python -m pip install -e clients/python numpy tiktoken
python -m pip install --upgrade --index-url "$torch_index" torch torchvision

echo "[4/4] Validating CUDA"
python - <<'PY'
import torch

print("torch:", torch.__version__)
print("cuda_version:", torch.version.cuda)
print("hip_version:", getattr(torch.version, "hip", None))
print("cuda_available:", torch.cuda.is_available())
if not torch.cuda.is_available() or not torch.version.cuda:
    raise SystemExit("CUDA PyTorch cannot access an NVIDIA GPU")
print("gpu:", torch.cuda.get_device_name(0))
print("vram_bytes:", torch.cuda.get_device_properties(0).total_memory)
x = torch.ones((256, 256), device="cuda")
y = x @ x
torch.cuda.synchronize()
print("matmul_sum:", float(y.sum().item()))
PY

cat <<EOF
CUDA environment is ready.

Recommended corrected 3-run warm steady-state command:

source "$venv/bin/activate"
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
EOF
