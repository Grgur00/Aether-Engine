"""Immutable protocol, environment and result receipts for systems experiments."""
import contextlib
import json
import os
import platform
from pathlib import Path

from evidence import digest
from paper_common import environment, java_classpath, sha256, write_json


def measurement_identity(report):
    cpu = report["commands"]["cpu"].get("stdout", "")
    cpu = "\n".join(line for line in cpu.splitlines() if not any(
        field in line for field in ("MHz", "BogoMIPS", "CPU(s) scaling")))
    return {"host": platform.node(), "platform": report["platform"], "cpu": cpu,
            "cpuCount": report["cpuCount"], "python": report["python"],
            "java": report["commands"]["java"], "packages": report["commands"]["packages"],
            "performanceEnvironment": report["performanceEnvironment"]}


def load_campaign(root):
    root = Path(root)
    meta = json.loads((root / "campaign.json").read_text(encoding="utf-8"))
    report = json.loads((root / "environment.json").read_text(encoding="utf-8"))
    if (meta.get("schema") != "aether-system-campaign-v1"
            or meta["protocolHash"] != digest(meta["protocol"])
            or meta["environmentSha256"] != sha256(root / "environment.json")
            or meta["environmentId"] != digest(report["measurementIdentity"])
            or meta["sourceSha256"] != report["sourceSha256"]):
        raise ValueError("systems campaign provenance is missing or altered")
    return meta


@contextlib.contextmanager
def campaign(root, protocol, resume=False):
    root = Path(root).resolve()
    root.mkdir(parents=True, exist_ok=True)
    # Kernel ownership expires when a process dies; a stale lock file needs no deletion.
    with (root / ".campaign.lock").open("a+b") as lock:
        lock.seek(0, os.SEEK_END)
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise RuntimeError("another process owns this systems campaign") from error
        try:
            java_classpath()  # Validate the executable before recording its source provenance.
            current = environment()
            identity = measurement_identity(current)
            current["measurementIdentity"] = identity
            if (root / "campaign.json").exists():
                if not resume:
                    raise FileExistsError("campaign already exists; use --resume with the same protocol and environment")
                meta = load_campaign(root)
                if (meta["protocol"] != protocol or meta["sourceSha256"] != current["sourceSha256"]
                        or meta["environmentId"] != digest(identity)):
                    raise ValueError("resume requires the same protocol, source and host environment; choose a new output")
            else:
                if any(path.name != ".campaign.lock" for path in root.iterdir()):
                    raise FileExistsError("unversioned or incomplete campaign directory; preserve it and choose a new output")
                write_json(root / "environment.json", current)
                meta = {"schema": "aether-system-campaign-v1", "protocol": protocol,
                        "protocolHash": digest(protocol), "sourceSha256": current["sourceSha256"],
                        "environmentId": digest(identity), "environmentSha256": sha256(root / "environment.json")}
                write_json(root / "campaign.json", meta)
            yield meta
        finally:
            lock.seek(0)
            if os.name == "nt":
                msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def save_result(path, report, meta):
    path = Path(path)
    report = {**report, "protocolHash": meta["protocolHash"], "environmentId": meta["environmentId"]}
    write_json(path, report)
    write_json(path.with_suffix(".receipt.json"), {"sha256": sha256(path)})
    return report


def load_result(path, meta):
    path = Path(path)
    receipt = json.loads(path.with_suffix(".receipt.json").read_text(encoding="utf-8"))
    report = json.loads(path.read_text(encoding="utf-8"))
    if (receipt.get("sha256") != sha256(path) or report.get("protocolHash") != meta["protocolHash"]
            or report.get("environmentId") != meta["environmentId"]):
        raise ValueError(f"altered or unrelated systems trial: {path}")
    return report
