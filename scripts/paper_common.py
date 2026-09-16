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
    report["performanceEnvironment"] = {key: os.environ.get(key) for key in (
        "OMP_NUM_THREADS", "MKL_NUM_THREADS", "OPENBLAS_NUM_THREADS", "CUDA_VISIBLE_DEVICES",
        "CUDA_DEVICE_ORDER", "CUBLAS_WORKSPACE_CONFIG", "NVIDIA_TF32_OVERRIDE", "PYTORCH_CUDA_ALLOC_CONF")}
    files = sorted(set(ROOT.glob("clients/python/**/*.py")) | set(ROOT.glob("scripts/*.py")) |
                   set(ROOT.glob("modules/*/src/main/**/*.java")) | set(ROOT.glob("modules/*/*.gradle.kts")) |
                   set(ROOT.glob("build-logic/src/**/*.kts")) | set(ROOT.glob("*.gradle.kts")) |
                   set(ROOT.glob("configs/paper/*.json")) | set(ROOT.glob("env/*.lock")))
    report["sourceSha256"] = {str(path.relative_to(ROOT)): sha256(path) for path in files}
    build_manifest = ROOT / "modules/aether-training-cache/build/paper-runtime-build.json"
    if build_manifest.is_file():
        report["javaBuild"] = {"manifestSha256": sha256(build_manifest),
                              "manifest": json.loads(build_manifest.read_text(encoding="utf-8"))}
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


def java_build_sources(root):
    patterns = ("*.gradle.kts", "gradle.properties", "gradle/**/*",
                "modules/*/*.gradle.kts", "modules/*/src/main/**/*",
                "build-logic/*.gradle.kts", "build-logic/gradle.properties", "build-logic/src/**/*")
    files = {path for pattern in patterns for path in root.glob(pattern) if path.is_file()}
    return {path.relative_to(root).as_posix(): sha256(path) for path in sorted(files)}


def java_runtime_record(path):
    if path.is_file():
        return {"kind": "file", "sha256": sha256(path)}
    if path.is_dir():
        return {"kind": "directory", "files": {
            item.relative_to(path).as_posix(): sha256(item)
            for item in sorted(path.rglob("*")) if item.is_file()}}
    return {"kind": "absent"}


def java_classpath():
    path = ROOT / "modules/aether-training-cache/build/paper-runtime-classpath.txt"
    manifest_path = path.with_name("paper-runtime-build.json")
    rebuild = "Rebuild Java: ./gradlew :modules:aether-training-cache:paperRuntimeClasspath"
    if not path.is_file() or not manifest_path.is_file():
        raise RuntimeError(rebuild)
    try:
        manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
        classpath = path.read_text(encoding="utf-8").strip()
        if (manifest.get("schema") != "aether-java-build-v1"
                or manifest.get("classpathSha256") != sha256(path)
                or manifest.get("sources") != java_build_sources(ROOT)):
            raise ValueError("source or classpath changed since the Java build")
        runtime = {str(Path(entry).absolute()): java_runtime_record(Path(entry))
                   for entry in classpath.split(os.pathsep) if entry}
        if not runtime or runtime != manifest.get("runtime"):
            raise ValueError("compiled classes, resources or dependency jars changed since the Java build")
    except (OSError, ValueError, TypeError, AttributeError) as error:
        raise RuntimeError(f"Java build provenance validation failed: {error}. {rebuild}") from error
    return classpath


@contextlib.contextmanager
def java_daemon(directory, *, maximum_bytes=1 << 40, durability="DURABLE", jvm_options=()):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    command = ["java", *jvm_options, "--enable-preview", "-cp", java_classpath(),
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
                    port = int(line)
                    try:
                        yield {"port": port, "pid": process.pid, "command": command}
                    finally:
                        # Export background work after the measured region and before
                        # terminating the daemon. A bounded drain prevents hidden debt
                        # from being discarded between otherwise independent blocks.
                        from aether_training_cache.client import AetherTrainingCache
                        with AetherTrainingCache(port=port) as diagnostics_client:
                            drain = diagnostics_client.wait_for_background_compaction()
                        diagnostics = drain["backgroundCompaction"]
                        write_json(directory.with_name(directory.name + f".compaction-{time.time_ns()}.json"),
                                   drain)
                        if not drain["drained"]:
                            raise RuntimeError("background compaction did not drain within 60 seconds")
                        if diagnostics.get("failed", 0):
                            raise RuntimeError("background compaction failed: " + diagnostics.get("lastFailure", "see diagnostics"))
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
