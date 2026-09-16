"""Exclusive experiment ownership and immutable GPU campaign metadata."""
import contextlib
import json
import os
from pathlib import Path

from paper_common import write_json


@contextlib.contextmanager
def exclusive_output(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    with (root / ".experiment.lock").open("a+b") as lock:
        lock.seek(0, os.SEEK_END)
        if lock.tell() == 0:
            lock.write(b"0")
            lock.flush()
        lock.seek(0)
        if os.name == "nt":
            import msvcrt
            acquire = lambda: msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
            release = lambda: msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
        else:
            import fcntl
            acquire = lambda: fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            release = lambda: fcntl.flock(lock.fileno(), fcntl.LOCK_UN)
        try:
            acquire()
        except OSError as error:
            raise RuntimeError("another runner owns this experiment output") from error
        try:
            yield
        finally:
            lock.seek(0)
            release()


def freeze_metadata(root, plan, environment_id, provenance, resume):
    root = Path(root)
    protocol = root / "protocol.json"
    environment = root / f"environment-{environment_id}.json"
    previous = list(root.glob("environment-*.json"))
    if protocol.exists():
        if not resume:
            raise FileExistsError("experiment already exists; use --resume with unchanged protocol/environment")
        if json.loads(protocol.read_text(encoding="utf-8")) != plan:
            raise ValueError("different frozen protocol; choose a new output directory")
        if previous != [environment]:
            raise ValueError("missing or changed frozen environment; choose a new output directory")
        saved = json.loads(environment.read_text(encoding="utf-8"))
        if (saved["measurementIdentity"] != provenance["measurementIdentity"]
                or saved["sourceSha256"] != provenance["sourceSha256"]):
            raise ValueError("altered frozen environment metadata")
    else:
        if previous or any(root.rglob("block-*.json")) or any(root.rglob("block.json")):
            raise ValueError("existing measurements have no frozen protocol; preserve them and choose a new output")
        write_json(environment, provenance)
        write_json(protocol, plan)
