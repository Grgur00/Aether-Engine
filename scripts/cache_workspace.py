"""Owned temporary stores, separate from durable experiment reports."""
import contextlib
import shutil
import tempfile
from pathlib import Path

from paper_common import write_json


def payload_floor(dataset, samples, image_size, num_classes=1000):
    """Two uncompressed payload stores; excludes framing, metadata and transient writes."""
    if min(samples, image_size, num_classes) < 1:
        raise ValueError("positive dimensions required for cache capacity estimate")
    payload = (3 * image_size ** 2 if dataset == "oct5k" else
               6 * image_size ** 2 + (num_classes if dataset == "coco" else 1))
    return 2 * samples * payload


@contextlib.contextmanager
def cache_workspace(report_directory, *, scratch_root=None, retain=False, payload_bytes=0, name="cache-workspace"):
    report_directory = Path(report_directory).resolve()
    report_directory.mkdir(parents=True, exist_ok=True)
    base = Path(scratch_root).resolve() if scratch_root is not None else report_directory
    base.mkdir(parents=True, exist_ok=True)
    free = shutil.disk_usage(base).free
    reserve = 64 * 1024 ** 2
    record = {"scratchRoot": str(base), "freeBytesBefore": free, "payloadFloorBytes": payload_bytes,
              "metadataReserveBytes": reserve, "retained": retain,
              "capacityScope": "uncompressed payload lower bound plus a small reserve; not a disk reservation or full metadata/temporary-space guarantee"}
    report_path = report_directory / (name + ".json")
    if free < payload_bytes + reserve:
        write_json(report_path, {**record, "status": "insufficient-capacity"})
        raise RuntimeError(f"cache filesystem has {free} free bytes; payloads alone need {payload_bytes} bytes "
                           f"plus reserve {reserve}. Select a larger --scratch-root; requested sample count is unchanged.")
    owned = Path(tempfile.mkdtemp(prefix="aether-store-", dir=base)).resolve()
    record["ownedDirectory"] = str(owned)
    write_json(owned / "owner.json", {"reportDirectory": str(report_directory)})
    write_json(report_path, {**record, "status": "running"})
    try:
        yield owned
    except BaseException:
        write_json(report_path, {**record, "status": "interrupted", "retained": True})
        raise
    else:
        # Resolve and verify both the generated child and its parent before recursive removal.
        if owned.is_symlink() or owned.resolve().parent != base or not owned.name.startswith("aether-store-"):
            raise ValueError("generated cache path escapes its owned scratch root")
        for log in owned.glob("*.log"):
            shutil.copy2(log, report_directory / (name + "-" + log.name))
        for diagnostic in owned.glob("*.compaction-*.json"):
            shutil.copy2(diagnostic, report_directory / (name + "-" + diagnostic.name))
        if not retain:
            shutil.rmtree(owned)
        write_json(report_path, {**record, "status": "complete", "freeBytesAfter": shutil.disk_usage(base).free})
