"""Crash-safe stage receipts and owned cache checkpoints, separate from measurement."""
import os
import json
import math
import random
import shutil
import time
from pathlib import Path

from evidence import digest
from paper_common import sha256, write_json

BACKENDS = ("aether", "mmap", "monai_persistent", "monai_lmdb")


def backend_order(block, stage, seed):
    order = list(BACKENDS)
    random.Random(seed).shuffle(order)
    offset = (block + stage) % len(order)
    return order[offset:] + order[:offset]


def inventory(directory):
    directory = Path(directory)
    if not directory.is_dir() or directory.is_symlink():
        raise ValueError("missing or unsafe checkpoint directory")
    result = {}
    for path in sorted(directory.rglob("*")):
        if path.is_symlink() or not path.resolve().is_relative_to(directory.resolve()):
            raise ValueError("unsafe cache entry")
        if path.is_file():
            result[path.relative_to(directory).as_posix()] = sha256(path)
    return result


def remove_owned(path, owner):
    if Path(path).is_symlink():
        raise ValueError("refusing symlink deletion")
    path, owner = Path(path).resolve(), Path(owner).resolve()
    if path == owner or not path.is_relative_to(owner):
        raise ValueError("refusing to remove outside owned scratch directory")
    if path.exists():
        shutil.rmtree(path)


def capacity_required(count, image_size):
    # All four live stores + committed and in-progress checkpoints, format overhead and reserve.
    return 4 * count * image_size ** 2 * 3 * 12 + 512 * 1024 ** 2


def check_capacity(root, required, free=None):
    available = shutil.disk_usage(root).free if free is None else free
    if available < required:
        raise RuntimeError(f"insufficient scratch space: require {required} bytes, available {available}")
    return {"requiredBytes": required, "freeBytes": available,
            "scope": "conservative peak estimate, not a reservation; format growth can still exhaust storage"}


def save_receipt(path, report, identity):
    payload = {**report, **identity}
    write_json(path, {"sha256": digest(payload), "payload": payload})
    return payload


def load_receipt(path, identity):
    envelope = json.loads(Path(path).read_text())
    payload = envelope["payload"]
    if envelope["sha256"] != digest(payload) or any(payload.get(k) != v for k, v in identity.items()):
        raise ValueError(f"receipt provenance/checksum mismatch: {path}")
    return payload


def stage_paths(scratch, backend, stage):
    base = Path(scratch) / backend
    return base / "live", base / f"checkpoint-v{stage}"


def pid_alive(pid):
    if os.name == "nt":
        import ctypes
        kernel = ctypes.WinDLL("kernel32", use_last_error=True)
        kernel.OpenProcess.restype = ctypes.c_void_p
        handle = kernel.OpenProcess(0x1000, False, int(pid))
        if not handle:
            if ctypes.get_last_error() == 87:
                return False
            raise RuntimeError("cannot verify interrupted process ownership")
        try:
            code = ctypes.c_ulong()
            if not kernel.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code)):
                raise RuntimeError("cannot query interrupted process")
            return code.value == 259
        finally:
            kernel.CloseHandle(ctypes.c_void_p(handle))
    try:
        os.kill(int(pid), 0)
        return True
    except ProcessLookupError:
        return False


def execute_stage(scratch, reports, backend, stage, identity, worker):
    """A committed receipt binds a closed store snapshot; partial live stores are never trusted."""
    scratch, reports = Path(scratch), Path(reports)
    target = reports / f"v{stage}-{backend}.json"
    stage_identity = {**identity, "backend": backend, "version": stage}
    if target.exists():
        return load_receipt(target, stage_identity)
    live, checkpoint = stage_paths(scratch, backend, stage)
    pending = reports / f"v{stage}-{backend}.pending.json"
    lease = reports / f"v{stage}-{backend}.lease.json"
    if lease.exists() and any(pid_alive(pid) for pid in json.loads(lease.read_text())["pids"]):
        raise RuntimeError("interrupted worker/service is still alive; refusing concurrent store recovery")
    previous = None
    if stage:
        previous = load_receipt(reports / f"v{stage - 1}-{backend}.json",
                                {**identity, "backend": backend, "version": stage - 1})
        old_checkpoint = stage_paths(scratch, backend, stage - 1)[1]
        if inventory(old_checkpoint) != previous["storeInventory"]:
            raise ValueError("committed cache checkpoint changed or is incomplete")
    if pending.exists():
        marker = json.loads(pending.read_text())
        if any(marker.get(k) != v for k, v in stage_identity.items()):
            raise ValueError("interrupted-stage provenance differs")
        # A crashed worker may have changed live even when no result was published.
        remove_owned(live, scratch)
        remove_owned(checkpoint, scratch)
        if previous:
            shutil.copytree(old_checkpoint, live)
    elif stage:
        if inventory(live) != previous["storeInventory"]:
            raise ValueError("unverified live store; refusing silent reuse")
    elif live.exists():
        raise ValueError("initial cache is not fresh")
    live.mkdir(parents=True, exist_ok=True)
    attempt = json.loads(pending.read_text()).get("attempt", 0) + 1 if pending.exists() else 1
    write_json(pending, {**stage_identity, "attempt": attempt})
    result = worker(backend, stage, live)
    started = time.perf_counter()
    fingerprint = inventory(live)
    if checkpoint.exists():
        raise ValueError("uncommitted checkpoint without restart marker")
    shutil.copytree(live, checkpoint)
    if inventory(checkpoint) != fingerprint:
        raise ValueError("checkpoint copy checksum mismatch")
    stats = [p.stat() for p in scratch.rglob("*") if p.is_file()]
    result.update(storeInventory=fingerprint, checkpointMs=(time.perf_counter() - started) * 1000,
                  attempt=attempt, checkpointWorkspaceDisk={
                      "logicalBytes": sum(s.st_size for s in stats),
                      "allocatedBytes": sum(s.st_blocks * 512 for s in stats) if all(hasattr(s, "st_blocks") for s in stats) else None,
                      "scope": "whole block scratch after checkpoint copy, before prior checkpoint removal"})
    saved = save_receipt(target, result, stage_identity)
    pending.unlink()
    if previous:
        remove_owned(old_checkpoint, scratch)
    return saved


def cumulative(stages):
    total, result = 0.0, {}
    for i, stage in enumerate(stages):
        timings = stage["timingsMs"]
        if any(not isinstance(v, (int, float)) or not math.isfinite(v) or v < 0 for v in timings.values()):
            raise ValueError("invalid timing")
        if abs(sum(timings.values()) - stage["fullLifecycleMs"]) > 1e-6:
            raise ValueError("stage timing accounting mismatch")
        total += stage["fullLifecycleMs"]
        result[f"V{i}"] = total
    return result
