"""Shared artifact process management and evidence capture (no silent fallbacks)."""
import contextlib
import hashlib
import json
import os
import platform
import queue
import subprocess
import sys
import threading
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PYTHON_CLIENT = ROOT / "clients" / "python"
sys.path.insert(0, str(PYTHON_CLIENT))


def write_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    os.replace(temporary, path)


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def capture(command):
    try:
        result = subprocess.run(command, cwd=ROOT, capture_output=True, text=True, timeout=30)
        return {"returncode": result.returncode, "stdout": result.stdout.strip(), "stderr": result.stderr.strip()}
    except (OSError, subprocess.TimeoutExpired) as error:
        return {"unavailable": str(error)}


def environment():
    git = ["git", "-c", f"safe.directory={ROOT.as_posix()}"]
    commands = {"gitCommit": git + ["rev-parse", "HEAD"],
                "gitStatus": git + ["status", "--porcelain"],
                "java": ["java", "-version"], "packages": [sys.executable, "-m", "pip", "freeze"],
                "gpu": ["nvidia-smi"], "cpu": ["lscpu"],
                "storage": ["lsblk", "-J", "-o", "NAME,SIZE,FSTYPE,MOUNTPOINTS"],
                "mounts": ["findmnt", "-J"]}
    report = {"python": sys.version, "executable": sys.executable,
              "platform": platform.platform(), "cpuCount": os.cpu_count(),
              "capturedAt": time.time(), "commands": {key: capture(value) for key, value in commands.items()}}
    files = sorted(set(ROOT.glob("clients/python/**/*.py")) | set(ROOT.glob("scripts/*.py")) |
                   set(ROOT.glob("modules/*/src/main/**/*.java")) | set(ROOT.glob("modules/*/*.gradle.kts")) |
                   set(ROOT.glob("build-logic/src/**/*.kts")) | set(ROOT.glob("*.gradle.kts")) |
                   set(ROOT.glob("configs/paper/*.json")) | set(ROOT.glob("env/*.lock")))
    report["sourceSha256"] = {str(path.relative_to(ROOT)): sha256(path) for path in files}
    archive_path = ROOT / "artifact-provenance.json"
    if archive_path.is_file():
        archive = json.loads(archive_path.read_text(encoding="utf-8"))
        verified = bool(archive.get("files"))
        for relative, expected in archive.get("files", {}).items():
            path = (ROOT / relative).resolve()
            if not path.is_relative_to(ROOT) or not path.is_file() or sha256(path) != expected:
                verified = False
                break
        report["archiveProvenance"] = {"verified": verified, "sourceClean": archive.get("sourceClean") is True,
                                       "originatingCommit": archive.get("gitCommit"), "manifestSha256": sha256(archive_path)}
    return report


def java_classpath():
    path = ROOT / "modules/aether-training-cache/build/paper-runtime-classpath.txt"
    if not path.is_file():
        raise RuntimeError("Build Java first: ./gradlew :modules:aether-training-cache:paperRuntimeClasspath")
    return path.read_text(encoding="utf-8").strip()


@contextlib.contextmanager
def java_daemon(directory, *, maximum_bytes=1 << 40, durability="DURABLE"):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    command = ["java", "--enable-preview", "-cp", java_classpath(),
               "io.aetherdb.training.cache.TrainingCacheDaemon", str(directory), "0", str(maximum_bytes), durability]
    flags = subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0
    log_path = directory.with_name(directory.name + ".stderr.log")
    with log_path.open("w", encoding="utf-8") as errors:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors, text=True,
                                   creationflags=flags)
        lines = queue.Queue()
        def read_port():
            for line in process.stdout:
                lines.put(line.strip())
            lines.put(None)
        reader = threading.Thread(target=read_port, daemon=True)
        reader.start()
        try:
            deadline = time.monotonic() + 60
            while True:
                remaining = deadline - time.monotonic()
                if remaining <= 0:
                    raise RuntimeError(f"Java startup timed out; see {log_path}")
                try:
                    line = lines.get(timeout=remaining)
                except queue.Empty as error:
                    raise RuntimeError("Java daemon did not announce a port") from error
                if line is None:
                    raise RuntimeError(f"Java exited during startup; see {log_path}")
                if line.isdecimal() and 0 < int(line) < 65536:
                    yield {"port": int(line), "pid": process.pid, "command": command}
                    break
        finally:
            if process.poll() is None:
                process.terminate()
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    process.kill()
                    process.wait(timeout=15)
            reader.join(timeout=5)
            process.stdout.close()
