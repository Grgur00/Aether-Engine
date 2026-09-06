#!/usr/bin/env bash
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
venv="${AETHER_PAPER_VENV:-$repo_root/.venv-paper}"
with_dali=false
torch_index="${AETHER_TORCH_INDEX:-https://download.pytorch.org/whl/cu121}"

for argument in "$@"; do
  case "$argument" in
    --with-dali) with_dali=true ;;
    *) echo "Unknown option: $argument" >&2; exit 2 ;;
  esac
done

command -v java >/dev/null || { echo "JDK 21 is required" >&2; exit 1; }
java_version="$(java -version 2>&1 || true)"
grep -q 'version "21\.' <<< "$java_version" || { echo "JDK 21 is required; found: $java_version" >&2; exit 1; }
command -v python3 >/dev/null || { echo "Python 3.11 is required" >&2; exit 1; }

python3 -m venv "$venv"
"$venv/bin/python" -m pip install --upgrade pip
"$venv/bin/python" -m pip install -r "$repo_root/env/requirements.lock"
"$venv/bin/python" -m pip install torch==2.13.0 --index-url "$torch_index"
if [[ "$with_dali" == true ]]; then
  "$venv/bin/python" -m pip install -r "$repo_root/env/requirements-dali.lock"
fi

cd "$repo_root"
chmod +x gradlew
./gradlew :modules:aether-training-cache:test :modules:aether-training-cache:paperRuntimeClasspath --no-daemon
if [[ "$with_dali" == true ]]; then
  "$venv/bin/python" scripts/validate_gpu.py --require-dali
else
  "$venv/bin/python" scripts/validate_gpu.py
fi

echo "Remote environment ready: $venv/bin/python"
echo "Set PYTHONPATH=$repo_root/clients/python:$repo_root/scripts before invoking individual scripts."
