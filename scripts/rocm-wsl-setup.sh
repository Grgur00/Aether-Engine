#!/usr/bin/env bash
set -euo pipefail

if [[ ! -e /dev/dxg ]]; then
  echo "AMD GPU paravirtualization is unavailable: /dev/dxg was not found." >&2
  echo "Install/update the Windows AMD driver and use WSL2." >&2
  exit 1
fi

write_probe="${TMPDIR:-/tmp}/aether-rocm-write-probe.$$"
if ! touch "$write_probe" 2>/dev/null; then
  echo "The WSL filesystem is read-only or unavailable." >&2
  echo "From PowerShell run: wsl --shutdown, then retry this script." >&2
  exit 1
fi
rm -f "$write_probe"

sudo apt-get update
sudo apt-get install -y python3-venv python3-pip git build-essential wget ca-certificates

source /etc/os-release
case "${VERSION_CODENAME:-}" in
  jammy)
    amdgpu_deb="https://repo.radeon.com/amdgpu-install/6.4.1/ubuntu/jammy/amdgpu-install_6.4.60401-1_all.deb"
    ;;
  noble)
    amdgpu_deb="https://repo.radeon.com/amdgpu-install/6.4.1/ubuntu/noble/amdgpu-install_6.4.60401-1_all.deb"
    ;;
  *)
    echo "Unsupported Ubuntu release for this scripted ROCm WSL setup: ${PRETTY_NAME:-unknown}" >&2
    echo "Use Ubuntu 22.04 (jammy) or 24.04 (noble), then rerun." >&2
    exit 1
    ;;
esac

if ! command -v rocminfo >/dev/null 2>&1 || [[ ! -e /opt/rocm/lib/libhsa-runtime64.so.1 ]]; then
  echo "[rocm] installing AMD WSL ROCm stack for ${PRETTY_NAME}"
  installer="/tmp/$(basename "$amdgpu_deb")"
  wget -O "$installer" "$amdgpu_deb"
  sudo apt-get install -y "$installer"
  sudo amdgpu-install -y --usecase=wsl,rocm --no-dkms
else
  echo "[rocm] existing ROCm WSL stack detected"
fi

if ! command -v rocminfo >/dev/null 2>&1; then
  echo "rocminfo is still unavailable after ROCm package installation." >&2
  exit 1
fi

if ! rocminfo | grep -E 'Name:|Marketing Name:' | grep -E 'gfx1100|Radeon RX 7900 XTX' >/dev/null; then
  echo "rocminfo did not report the expected RX 7900 XTX/gfx1100 agent." >&2
  echo "Check that the Windows AMD Adrenalin driver supports ROCm on WSL2, then run: wsl --shutdown" >&2
  exit 1
fi

repo_root="${AETHER_REPO_ROOT:-/mnt/c/Users/Korisnik/Desktop/Projects/Aether-Engine}"
venv="${AETHER_VENV:-$HOME/.venv-rocm}"
available_bytes="$(df --output=avail -B1 "$(dirname "$venv")" | tail -1 | tr -d ' ')"
if [[ "${available_bytes:-0}" -lt 8000000000 ]]; then
  echo "At least 8 GB free is required for the ROCm PyTorch environment." >&2
  echo "Target filesystem: $(dirname "$venv")" >&2
  exit 1
fi
echo "[1/5] preparing virtual environment: $venv"

if [[ -x "$venv/bin/python" ]] && "$venv/bin/python" -c 'import torch' >/dev/null 2>&1; then
  echo "[2/5] existing Torch installation is importable"
else
  echo "[2/5] creating fresh virtual environment"
  rm -rf "$venv"
  python3 -m venv "$venv"
fi

source "$venv/bin/activate"
echo "[3/5] upgrading pip"
python -m pip install --upgrade pip

# Install the ROCm PyTorch build matching the AMD WSL ROCm 6.4 runtime.
echo "[4/5] installing ROCm PyTorch; the wheel is approximately 5 GB"
python -m pip install --no-cache-dir --progress-bar on \
  "torch==2.9.0+rocm6.4" \
  "torchvision==0.24.0+rocm6.4" \
  --index-url https://download.pytorch.org/whl/rocm6.4
python -m pip install tiktoken numpy

torch_location="$(python -m pip show torch | awk -F ': ' '/^Location:/ {print $2}')"
torch_lib="${torch_location}/torch/lib"
if [[ -d "$torch_lib" && -e /opt/rocm/lib/libhsa-runtime64.so.1 ]]; then
  echo "[4/5] replacing bundled PyTorch HSA runtime with WSL-compatible ROCm runtime"
  rm -f "$torch_lib"/libhsa-runtime64.so*
  cp /opt/rocm/lib/libhsa-runtime64.so.1 "$torch_lib/libhsa-runtime64.so"
fi

export PYTHONPATH="$repo_root/clients/python"
echo "[5/5] validating ROCm and the GPU"
python - <<'PY'
import torch
print("torch:", torch.__version__)
print("hip:", torch.version.hip)
print("cuda_available:", torch.cuda.is_available())
if not torch.cuda.is_available() or not torch.version.hip:
    raise SystemExit("ROCm PyTorch cannot access the AMD GPU")
print("gpu:", torch.cuda.get_device_name(0))
print("vram_bytes:", torch.cuda.get_device_properties(0).total_memory)
x = torch.ones((256, 256), device="cuda")
print("matmul:", float((x @ x)[0, 0]))
torch.cuda.synchronize()
PY

echo "ROCm WSL environment is ready. Run the benchmark with --accelerator-backend rocm."
