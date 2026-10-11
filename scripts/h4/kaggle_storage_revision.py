"""CPU-only Linux capacity validation of the separate H4 storage candidate."""
import argparse
import json
from pathlib import Path
import subprocess
import zipfile

from . import kaggle_cpu_preflight as baseline_launcher
from .common import digest, write_json

ROOT = Path(__file__).resolve().parents[2]
WORK = ROOT / "build/h4-kaggle-storage-v1"
DATASET = "grgur321/aether-h4-storage-revision-source"
KERNEL = "grgur321/aether-h4-bounded-storage-cpu-validation"

OLD_GATE = """    status['stage'] = 'online-admission-1gib'
    code = run([python, '-m', 'h4.storage_preflight', '--scratch', '/kaggle/working',
                '--output', str(results / 'online-1gib'), '--gib', '1', '--exception-log'],
               allowed=(0, 2), timeout=3600)
    status.update(stage='completed', admission_1gib_passed=code == 0,
                  larger_stores_attempted=False)
"""
NEW_GATE = """    prior = []
    capacity = {}
    for gib in (1, 2, 4, 8):
        status['stage'] = 'online-admission-' + str(gib) + 'gib'
        output = results / ('online-' + str(gib) + 'gib')
        command = [python, '-m', 'h4.storage_preflight', '--scratch', '/kaggle/working',
                   '--output', str(output), '--gib', str(gib), '--exception-log', '--heap-samples']
        for receipt in prior:
            command.extend(['--prior-preflight', receipt])
        code = run(command, allowed=(0, 2), timeout=10800)
        capacity[str(gib)] = code == 0
        status['capacity_gates'] = capacity
        if code != 0:
            break
        prior.append(str(output / 'preflight.json'))
    status.update(stage='completed', all_capacity_gates_passed=all(capacity.get(str(g)) for g in (1, 2, 4, 8)),
                  primary_h4_eligible=False, out_of_core_verified=False)
"""


def remote():
    source = baseline_launcher.REMOTE
    if source.count(OLD_GATE) != 1:
        raise ValueError("baseline launcher changed; review capacity stage template")
    source = source.replace(OLD_GATE, NEW_GATE)
    build = "run(['bash', 'gradlew', '--no-daemon', ':modules:aether-training-cache:paperRuntimeClasspath'])"
    if source.count(build) != 1:
        raise ValueError("baseline Java build template changed")
    source = source.replace(build, "run(['bash', 'gradlew', '--no-daemon', '--continue', "
        "':modules:aether-sstable:test', ':modules:aether-lsm:test', ':modules:aether-engine:test', "
        "':modules:aether-training-cache:test', ':modules:aether-training-cache:paperRuntimeClasspath'])")
    source = source.replace("(work / 'h4-upload-inventory.json').read_text()",
                            "(work / 'h4-storage-upload-inventory.json').read_text()")
    marker = "    for name in ('paper-runtime-build.json', 'paper-runtime-classpath.txt'):"
    reports = """    for module in ('aether-sstable', 'aether-lsm', 'aether-engine', 'aether-training-cache'):
        destination = results / 'java-tests' / module
        destination.mkdir(parents=True)
        for report in (work / 'modules' / module / 'build/test-results/test').glob('TEST-*.xml'):
            (destination / report.name).write_bytes(report.read_bytes())
"""
    if source.count(marker) != 1:
        raise ValueError("runtime-report template changed")
    return source.replace(marker, reports + marker)


def notebook(config, pinned):
    code = "CONFIG = " + repr(config) + "\n" + remote()
    compile(code, "h4-storage-revision", "exec")
    write_json(WORK / "notebook/h4.ipynb", {
        "nbformat": 4, "nbformat_minor": 5,
        "metadata": {"kernelspec": {"display_name": "Python 3", "language": "python", "name": "python3"}},
        "cells": [{"cell_type": "code", "id": "h4-storage-revision", "metadata": {},
                   "execution_count": None, "outputs": [], "source": code.splitlines(keepends=True)}]})
    write_json(WORK / "notebook/kernel-metadata.json", {
        "id": KERNEL, "title": "Aether H4 Bounded Storage CPU Validation", "code_file": "h4.ipynb",
        "language": "python", "kernel_type": "notebook", "is_private": True,
        "enable_gpu": False, "enable_internet": True,
        "dataset_sources": [pinned], "competition_sources": [], "kernel_sources": []})


def prepare(candidate):
    candidate = Path(candidate).resolve()
    if WORK.exists():
        raise ValueError("preserve prior submission; use a new deployment identity")
    receipt = json.loads((candidate / "h4-candidate-inventory.json").read_text())
    names = dict(receipt["files"])
    names["h4-candidate-inventory.json"] = digest(candidate / "h4-candidate-inventory.json")
    for name, expected in names.items():
        path = (candidate / name).resolve()
        if not path.is_relative_to(candidate) or digest(path) != expected:
            raise ValueError("candidate snapshot changed: " + name)
    transport = WORK / "source/h4-bounded-storage-v1.bin"
    transport.parent.mkdir(parents=True)
    inventory = {"files": names, "candidate_receipt": receipt,
                 "scope": "H4-only CPU synthetic capacity validation; no H2 or PCam benchmark"}
    with zipfile.ZipFile(transport, "w", zipfile.ZIP_DEFLATED) as archive:
        for name in names:
            archive.write(candidate / name, name)
        archive.writestr("h4-storage-upload-inventory.json", json.dumps(inventory, indent=2))
    config = {"transport": transport.name, "sha256": digest(transport),
              "launcher_sha256": digest(Path(__file__)),
              "baseline_launcher_sha256": digest(Path(baseline_launcher.__file__)),
              "dataset": DATASET, "kernel": KERNEL}
    write_json(WORK / "source/dataset-metadata.json", {
        "id": DATASET, "title": "Aether H4 Bounded Storage Candidate Source", "licenses": [{"name": "other"}]})
    write_json(WORK / "config.json", config)
    write_json(WORK / "source-inventory.json", inventory)
    notebook(config, DATASET)
    print("Prepared CPU-only H4 candidate " + config["sha256"], flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("prepare", "upload", "run", "status"))
    parser.add_argument("--candidate", type=Path)
    args = parser.parse_args()
    if args.action == "prepare":
        if args.candidate is None:
            parser.error("--candidate required")
        prepare(args.candidate)
        return
    from kaggle_remote import cli
    config = json.loads((WORK / "config.json").read_text())
    if args.action == "status":
        submission = json.loads((WORK / "submission.json").read_text())
        print(cli("kernels", "status", submission.get("actual_kernel", submission["kernel"]), capture=True))
        return
    transport = WORK / "source" / config["transport"]
    if (digest(transport) != config["sha256"] or digest(Path(__file__)) != config["launcher_sha256"]
            or digest(Path(baseline_launcher.__file__)) != config["baseline_launcher_sha256"]):
        raise ValueError("prepared transport or launcher changed")
    if args.action == "upload":
        cli("datasets", "create", "-p", WORK / "source", "--dir-mode", "skip")
    elif args.action == "run":
        state = json.loads(cli("datasets", "status", DATASET, "--format", "json(status,current_version_number)", capture=True))
        if state.get("status") != "ready":
            raise ValueError("private upload not ready")
        pinned = DATASET + "/" + str(state["current_version_number"])
        destination = WORK / "remote-verification"
        destination.mkdir(exist_ok=False)
        cli("datasets", "download", pinned, "-f", config["transport"], "-p", destination)
        if digest(destination / config["transport"]) != config["sha256"]:
            raise ValueError("remote transport mismatch; no launch")
        notebook(config, pinned)
        cli("kernels", "push", "-p", WORK / "notebook")
        write_json(WORK / "submission.json", {**config, "pinned_dataset": pinned, "gpu_requested": False,
                   "remote_transport_verified": True, "primary_h4_eligible": False})


if __name__ == "__main__":
    main()
