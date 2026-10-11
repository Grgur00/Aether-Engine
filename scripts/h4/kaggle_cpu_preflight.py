"""Prepare, privately upload, and launch the CPU-only H4 diagnostic."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import zipfile

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/h4-kaggle-cpu-v3"


def sha(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


REMOTE = r'''
import hashlib, json, os, pathlib, signal, subprocess, sys, threading, time, traceback, zipfile
from pathlib import Path
results = Path('/kaggle/working/h4-preflight-results')
results.mkdir(exist_ok=False)
work = Path('/kaggle/working/Aether-H4-Preflight')
status = dict(stage='source-verification', primary_h4_eligible=False,
              gpu_requested=False, full_pcam_benchmark=False)
def save_status():
    (results / 'run-status.json').write_text(json.dumps(status, indent=2) + '\n')
def digest(path):
    with path.open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()
def run(command, *, allowed=(0,), timeout=1800):
    print('$ ' + ' '.join(map(str, command)), flush=True)
    save_status()
    with (results / 'execution.log').open('a') as log:
        process = subprocess.Popen(command, cwd=work, env=env, start_new_session=True,
                                   stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                   text=True)
        expired = threading.Event()
        def terminate():
            expired.set()
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        timer = threading.Timer(timeout, terminate)
        timer.start()
        try:
            for line in process.stdout:
                log.write(line)
                log.flush()
                print(line, end='', flush=True)
            process.wait()
        finally:
            timer.cancel()
        if expired.is_set():
            raise subprocess.TimeoutExpired(command, timeout)
    if process.returncode not in allowed:
        raise subprocess.CalledProcessError(process.returncode, command)
    return process.returncode
try:
    candidates = [p for p in Path('/kaggle/input').rglob(CONFIG['transport'])
                  if p.is_file() and digest(p) == CONFIG['sha256']]
    if len(candidates) != 1:
        raise ValueError('Expected exactly one byte-verified H4 transport bundle')
    print('Verified CPU preflight bundle: ' + str(candidates[0]), flush=True)
    with zipfile.ZipFile(candidates[0]) as archive:
        for item in archive.infolist():
            target = (work / item.filename).resolve()
            if not target.is_relative_to(work.resolve()) or item.is_dir():
                raise ValueError('Unsafe transport member')
        archive.extractall(work)
    inventory = json.loads((work / 'h4-upload-inventory.json').read_text())
    for name, expected in inventory['files'].items():
        if digest(work / name) != expected:
            raise ValueError('Source inventory mismatch: ' + name)
    (results / 'source-inventory.json').write_text(json.dumps(inventory, indent=2) + '\n')
    env = os.environ.copy()
    env.update(PYTHONPATH=str(work / 'scripts') + ':' + str(work / 'clients/python'),
               CUDA_VISIBLE_DEVICES='', OMP_NUM_THREADS='1', MKL_NUM_THREADS='1',
               OPENBLAS_NUM_THREADS='1', PYTHONUNBUFFERED='1')
    status['stage'] = 'environment-setup'
    run(['apt-get', 'update'])
    run(['apt-get', 'install', '-y', 'openjdk-21-jdk-headless'])
    java_home = Path('/usr/lib/jvm/java-21-openjdk-amd64')
    if not (java_home / 'bin/java').is_file():
        raise ValueError('Java 21 installation missing')
    env['JAVA_HOME'] = str(java_home)
    env['PATH'] = str(java_home / 'bin') + ':' + env['PATH']
    # Keep Kaggle's installed CPU PyTorch available without requesting a GPU.
    # Kaggle's system Python can omit ensurepip; virtualenv seeds its own pip.
    run([sys.executable, '-m', 'pip', 'install', '--user', 'virtualenv==21.14.1'])
    run([sys.executable, '-m', 'virtualenv', '--system-site-packages', '/kaggle/working/h4-venv'])
    python = '/kaggle/working/h4-venv/bin/python'
    run([python, '-m', 'pip', 'install', '-r', 'scripts/h4/requirements.lock'])
    run(['java', '-version'])
    run([python, '-m', 'pip', 'freeze'])
    status['stage'] = 'java-build'
    run(['bash', 'gradlew', '--no-daemon', ':modules:aether-training-cache:paperRuntimeClasspath'])
    for name in ('paper-runtime-build.json', 'paper-runtime-classpath.txt'):
        source = work / 'modules/aether-training-cache/build' / name
        (results / name).write_bytes(source.read_bytes())
    status['stage'] = 'memory-control-discovery'
    run([python, '-m', 'h4.kaggle_preflight', '--scratch', '/kaggle/working',
         '--output', str(results / 'resources'), '--attempt-memory-control'], allowed=(0, 2))
    status['stage'] = 'online-smoke-32'
    run([python, '-m', 'h4.storage_preflight', '--scratch', '/kaggle/working',
         '--output', str(results / 'online-smoke-32'), '--samples', '32'], timeout=600)
    status['stage'] = 'online-admission-1gib'
    code = run([python, '-m', 'h4.storage_preflight', '--scratch', '/kaggle/working',
                '--output', str(results / 'online-1gib'), '--gib', '1', '--exception-log'],
               allowed=(0, 2), timeout=3600)
    status.update(stage='completed', admission_1gib_passed=code == 0,
                  larger_stores_attempted=False)
except Exception as error:
    status.update(stage='failed', error_type=type(error).__name__, error=str(error))
    (results / 'failure.txt').write_text(traceback.format_exc())
    print(traceback.format_exc(), flush=True)
    raise
finally:
    save_status()
    inventory = {p.relative_to(results).as_posix(): digest(p)
                 for p in sorted(results.rglob('*')) if p.is_file() and p.name != 'sha256.json'}
    (results / 'sha256.json').write_text(json.dumps(inventory, indent=2) + '\n')
    output = Path('/kaggle/working/aether-h4-preflight-results.zip')
    with zipfile.ZipFile(output, 'w', zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(results.rglob('*')):
            if path.is_file():
                archive.write(path, path.relative_to(results).as_posix())
    with zipfile.ZipFile(output) as archive:
        if archive.testzip() is not None:
            raise ValueError('Results ZIP verification failed')
    print('Verified CPU-only H4 results ZIP: ' + str(output), flush=True)
'''


def prepare():
    if WORK.exists():
        raise ValueError("Preserve the existing deployment; use a new WORK directory")
    sys.path.insert(0, str(ROOT / "scripts"))
    from paper_common import java_build_sources
    files = {ROOT / name for name in java_build_sources(ROOT)}
    files.update(ROOT / name for name in ("gradlew", "gradlew.bat", "jreleaser.yml", "scripts/paper_common.py"))
    files.update(p for pattern in ("scripts/h4/**/*", "clients/python/**/*.py",
                                  "examples/*/*.gradle.kts", "examples/*/src/main/**/*",
                                  "examples/*/aether-schemas/*.json")
                 for p in ROOT.glob(pattern) if p.is_file() and "__pycache__" not in p.parts)
    names = {p.relative_to(ROOT).as_posix(): sha(p) for p in sorted(files)}
    commit = subprocess.check_output(["git", "-c", f"safe.directory={ROOT.as_posix()}",
                                      "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    inventory = dict(files=names, originating_commit=commit, snapshot_is_worktree=True,
                     scope="CPU-only synthetic H4 preflight, no PCam campaign")
    transport = WORK / "source/h4-cpu-preflight-v3.bin"
    transport.parent.mkdir(parents=True)
    with zipfile.ZipFile(transport, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            archive.write(ROOT / name, name)
        archive.writestr("h4-upload-inventory.json", json.dumps(inventory, indent=2))
    config = dict(transport=transport.name, sha256=sha(transport), launcher_sha256=sha(Path(__file__)),
                  dataset="grgur321/aether-h4-cpu-preflight-source",
                  kernel="grgur321/aether-h4-cpu-preflight")
    write(WORK / "source/dataset-metadata.json", dict(id=config["dataset"],
          title="Aether H4 CPU Preflight Source", licenses=[dict(name="other")]))
    write(WORK / "config.json", config)
    write(WORK / "source-inventory.json", inventory)
    notebook(config, config["dataset"])
    print(f"Prepared {len(names)} allowlisted source files; transport SHA256 {config['sha256']}")


def notebook(config, dataset):
    code = "CONFIG = " + repr(config) + "\n" + REMOTE
    compile(code, "h4-cpu-preflight", "exec")
    write(WORK / "notebook/h4.ipynb", dict(nbformat=4, nbformat_minor=5,
          metadata=dict(kernelspec=dict(display_name="Python 3", language="python", name="python3")),
          cells=[dict(cell_type="code", id="h4-cpu-preflight", metadata={}, execution_count=None,
                      outputs=[], source=code.splitlines(keepends=True))]))
    write(WORK / "notebook/kernel-metadata.json", dict(id=config["kernel"],
          title="Aether H4 CPU Preflight", code_file="h4.ipynb", language="python",
          kernel_type="notebook", is_private=True, enable_gpu=False, enable_internet=True,
          dataset_sources=[dataset], competition_sources=[], kernel_sources=[]))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "upload", "run", "status"))
    args = parser.parse_args()
    if args.action == "prepare":
        prepare()
        return
    sys.path.insert(0, str(ROOT / "scripts"))
    from kaggle_remote import cli
    config = json.loads((WORK / "config.json").read_text())
    transport = WORK / "source" / config["transport"]
    if sha(transport) != config["sha256"]:
        raise ValueError("Prepared transport changed")
    if sha(Path(__file__)) != config["launcher_sha256"]:
        raise ValueError("Launcher changed after preparation; preserve and prepare a fresh deployment")
    if args.action == "upload":
        cli("datasets", "version", "-p", WORK / "source", "--dir-mode", "skip",
            "-m", "CPU preflight v3: include Gradle example projects; no storage changes")
    elif args.action == "run":
        state = json.loads(cli("datasets", "status", config["dataset"], "--format",
                               "json(status,current_version_number)", capture=True))
        if state.get("status") != "ready":
            raise ValueError("Private source upload is not yet ready")
        pinned = config["dataset"] + "/" + str(state["current_version_number"])
        destination = WORK / "remote-verification"
        destination.mkdir(exist_ok=False)
        cli("datasets", "download", pinned, "-f", config["transport"], "-p", destination)
        if sha(destination / config["transport"]) != config["sha256"]:
            raise ValueError("Downloaded private upload differs; no launch")
        notebook(config, pinned)
        cli("kernels", "push", "-p", WORK / "notebook")
        write(WORK / "submission.json", dict(**config, pinned_dataset=pinned,
              gpu_requested=False, remote_transport_verified=True))
    else:
        print(cli("kernels", "status", config["kernel"], capture=True))


if __name__ == "__main__":
    main()
