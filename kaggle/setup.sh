#!/usr/bin/env bash
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
paper_venv="${AETHER_PAPER_VENV:-/kaggle/working/aether-paper-venv}"
if [[ ! -d /kaggle/working ]]; then
  echo 'This setup script targets a Kaggle notebook. For another host, use paper/README.md.' >&2
  exit 1
fi
java_version="$(java -version 2>&1 || true)"
if ! grep -q 'version "21\.' <<< "$java_version"; then
  apt-get update
  apt-get install -y openjdk-21-jdk-headless
  export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
  export PATH="$JAVA_HOME/bin:$PATH"
fi
java_binary="$(readlink -f "$(command -v java)")"
export JAVA_HOME="$(dirname "$(dirname "$java_binary")")"
export PATH="$JAVA_HOME/bin:$PATH"
python -m venv --system-site-packages "$paper_venv"
"$paper_venv/bin/python" -m pip install -r "$repo_root/env/requirements.lock"
cd "$repo_root"
chmod +x gradlew
./gradlew :modules:aether-training-cache:test :modules:aether-training-cache:paperRuntimeClasspath --no-daemon
"$paper_venv/bin/python" - <<'PY'
import json
import os
from pathlib import Path
import torch
assert torch.cuda.is_available() and torch.version.cuda, 'Kaggle CUDA GPU is required for training experiments'
print('PyTorch:', torch.__version__, 'CUDA:', torch.version.cuda)
for index in range(torch.cuda.device_count()):
    print(index, torch.cuda.get_device_name(index))
Path('/kaggle/working/aether-paper-runtime.json').write_text(json.dumps({'javaHome': os.environ['JAVA_HOME']}, indent=2))
PY
echo "Ready: $paper_venv/bin/python"
echo 'Next: run artifact_smoke.py and prepare your external dataset configuration; see kaggle/README.md.'
