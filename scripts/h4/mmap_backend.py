"""Durable fixed-width contiguous mmap baseline; metadata publication is atomic."""
import hashlib
import json
import mmap
import os
from contextlib import contextmanager
from pathlib import Path

from .common import write_json
from .prepare_pcam_manifest import PAYLOAD_BYTES, canonical_hash


class MmapBackend:
    def __init__(self, directory):
        self.directory = Path(directory).resolve()
        self.directory.mkdir(parents=True, exist_ok=True)
        self.data_path = self.directory / "tensors.bin"
        self.index_path = self.directory / "index.json"
        self.mapping = None
        self.stream = None
        self.rows = self._load_index()
        self.offsets = {row["sample_id"]: position * PAYLOAD_BYTES for position, row in enumerate(self.rows)}

    def _load_index(self):
        if not self.index_path.exists():
            if self.data_path.exists() and self.data_path.stat().st_size:
                raise ValueError("unpublished data exists without an index; explicit recovery required")
            return []
        publication = json.loads(self.index_path.read_text(encoding="utf-8"))
        body = publication["index"]
        if publication["sha256"] != canonical_hash(body) or body["schema"] != "aether-h4-mmap-v1":
            raise ValueError("mmap index integrity/schema mismatch")
        rows = body["rows"]
        if body["payload_bytes"] != PAYLOAD_BYTES or len({row["sample_id"] for row in rows}) != len(rows):
            raise ValueError("mmap width or membership mismatch")
        if not self.data_path.exists() or self.data_path.stat().st_size < len(rows) * PAYLOAD_BYTES:
            raise ValueError("mmap data is shorter than committed index")
        for row in rows:
            if type(row["sample_id"]) is not int or row["sample_id"] < 0:
                raise ValueError("invalid mmap sample ID")
            for field in ("artifact_id", "artifact_sha256"):
                if len(row[field]) != 64 or bytes.fromhex(row[field]).hex() != row[field]:
                    raise ValueError("invalid artifact identity/checksum")
        return rows

    def append(self, samples):
        """Publish an iterable of (manifest row, payload), reusing matching IDs.

        A stale writer lock is never removed automatically. Existing committed
        bytes are immutable; orphan append tails are truncated under the lock.
        """
        self.close()
        lock_path = self.directory / "writer.lock"
        descriptor = os.open(lock_path, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
        try:
            os.close(descriptor)
            old = self._load_index()
            existing = {row["sample_id"]: row for row in old}
            rows, reused, appended = list(old), 0, 0
            with self.data_path.open("r+b" if self.data_path.exists() else "w+b") as stream:
                stream.truncate(len(old) * PAYLOAD_BYTES)
                stream.seek(0, os.SEEK_END)
                seen = set()
                for row, payload in samples:
                    sample_id = row["sample_id"]
                    if type(sample_id) is not int or sample_id < 0 or sample_id in seen:
                        raise ValueError("invalid or duplicate input sample ID")
                    seen.add(sample_id)
                    record = {key: row[key] for key in ("sample_id", "artifact_id", "artifact_sha256")}
                    for field in ("artifact_id", "artifact_sha256"):
                        if len(record[field]) != 64 or bytes.fromhex(record[field]).hex() != record[field]:
                            raise ValueError("invalid artifact identity/checksum")
                    if len(payload) != PAYLOAD_BYTES or hashlib.sha256(payload).hexdigest() != record["artifact_sha256"]:
                        raise ValueError("mmap input payload size/checksum mismatch")
                    if sample_id in existing:
                        if record != existing[sample_id]:
                            raise ValueError("existing sample identity changed; cannot overwrite committed data")
                        reused += 1
                        continue
                    stream.write(payload)
                    rows.append(record)
                    appended += 1
                stream.flush()
                os.fsync(stream.fileno())
            body = {"schema": "aether-h4-mmap-v1", "payload_bytes": PAYLOAD_BYTES, "rows": rows}
            write_json(self.index_path, {"index": body, "sha256": canonical_hash(body)})
            self.rows = rows
            self.offsets = {row["sample_id"]: position * PAYLOAD_BYTES for position, row in enumerate(rows)}
            return {"samples": len(rows), "appended": appended, "reused": reused,
                    "logical_bytes_written": appended * PAYLOAD_BYTES,
                    "publication": "tensor/index fsync then atomic index replacement",
                    "parent_directory_fsync": os.name == "posix"}
        finally:
            lock_path.unlink()

    def get(self, sample_id):
        offset = self.offsets[sample_id]
        if self.mapping is None:
            self.stream = self.data_path.open("rb")
            try:
                self.mapping = mmap.mmap(self.stream.fileno(), len(self.rows) * PAYLOAD_BYTES, access=mmap.ACCESS_READ)
            except BaseException:
                self.stream.close()
                self.stream = None
                raise
        return memoryview(self.mapping)[offset:offset + PAYLOAD_BYTES]

    def validate(self, expected_rows):
        expected_rows = list(expected_rows)
        expected = {row["sample_id"]: row for row in expected_rows}
        if len(expected) != len(expected_rows):
            raise ValueError("duplicate mmap manifest sample ID")
        if set(expected) != set(self.offsets):
            raise ValueError("mmap complete membership differs from manifest")
        for row in self.rows:
            sample_id = row["sample_id"]
            if any(row[key] != expected[sample_id][key] for key in ("artifact_id", "artifact_sha256")):
                raise ValueError("mmap committed identity differs from manifest")
            with self.get(sample_id) as payload:
                if hashlib.sha256(payload).hexdigest() != row["artifact_sha256"]:
                    raise ValueError("mmap stored artifact checksum mismatch")
        return {"correctness_passed": True, "validated_samples": len(self.rows)}

    @contextmanager
    def get_many(self, sample_ids):
        sample_ids = list(sample_ids)
        if not 0 < len(sample_ids) <= 16:
            raise ValueError("H4 lookup batch must contain 1 to 16 IDs")
        views = []
        try:
            for sample_id in sample_ids:
                views.append(self.get(sample_id))
            yield views
        finally:
            for view in views:
                view.release()

    def metrics(self):
        files = [path for path in self.directory.iterdir() if path.is_file()]
        return {"samples": len(self.rows), "logical_payload_bytes": len(self.rows) * PAYLOAD_BYTES,
                "store_file_bytes": sum(path.stat().st_size for path in files),
                "store_allocated_bytes": (sum(path.stat().st_blocks * 512 for path in files)
                                          if all(hasattr(path.stat(), "st_blocks") for path in files) else None),
                "unpublished_tail_bytes": (self.data_path.stat().st_size - len(self.rows) * PAYLOAD_BYTES
                                           if self.data_path.exists() else 0)}

    def close(self):
        if self.mapping is not None:
            self.mapping.close()
            self.mapping = None
        if self.stream is not None:
            self.stream.close()
            self.stream = None

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.close()
