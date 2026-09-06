"""Indexed, append-only baseline with actual read-only mmap payload reads.

Index batches use a checksummed append journal, so a publish does not rewrite all
previous metadata. Shared mode serializes publication and replays only new index
records. Incomplete journal tails are invisible. Power-loss behavior is untested.
"""
import hashlib
import json
import mmap
import os
import struct
import threading
import time
import contextlib
from pathlib import Path


class PersistentMmapStore:
    def __init__(self, root, *, shared=False, durable=False):
        self.root = Path(root)
        self.root.mkdir(parents=True, exist_ok=True)
        self.data_path = self.root / "data.bin"
        self.index_path = self.root / "index.json"
        self.journal_path = self.root / "index.journal"
        self._journal_offset = 0
        self.index = self._load_index()
        self._mapping = None
        self._stream = None
        self._lock = threading.RLock()
        self.shared = shared
        self.durable = durable
        self._index_version = None
        self.reset_metrics()
        with self._transaction():
            self._replay_journal()

    @contextlib.contextmanager
    def _transaction(self):
        with self._lock:
            if not self.shared:
                yield
                return
            with (self.root / "writer.lock").open("a+b") as lock:
                lock.seek(0, 2)
                if lock.tell() == 0:
                    lock.write(b"0")
                    lock.flush()
                lock.seek(0)
                if os.name == "nt":
                    import msvcrt
                    while True:
                        try:
                            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                            break
                        except OSError:
                            time.sleep(.01)
                else:
                    import fcntl
                    fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
                try:
                    stat = self.journal_path.stat() if self.journal_path.exists() else None
                    version = (stat.st_mtime_ns, stat.st_size) if stat else None
                    if version != self._index_version:
                        self.close()
                        self._replay_journal()
                        self._index_version = version
                    yield
                finally:
                    # Release all Windows mappings before another process appends.
                    if os.name == "nt":
                        self.close()
                        lock.seek(0)
                        msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
                    else:
                        fcntl.flock(lock.fileno(), fcntl.LOCK_UN)

    def _load_index(self):
        if not self.index_path.exists():
            return {}
        value = json.loads(self.index_path.read_text(encoding="utf-8"))
        if not isinstance(value, dict):
            raise ValueError("mmap index must be an object")
        size = self.data_path.stat().st_size if self.data_path.exists() else 0
        for entry in value.values():
            if entry["offset"] < 0 or entry["size"] < 4 or entry["offset"] + entry["size"] > size:
                raise ValueError("mmap index references a truncated or invalid record")
        return value

    def _replay_journal(self):
        if not self.journal_path.exists():
            return
        if self.journal_path.stat().st_size < self._journal_offset:
            raise ValueError("mmap journal shrank behind an open reader")
        data_size = self.data_path.stat().st_size if self.data_path.exists() else 0
        with self.journal_path.open("rb") as stream:
            stream.seek(self._journal_offset)
            while True:
                length = stream.read(4)
                if len(length) < 4:
                    break
                size = struct.unpack("<I", length)[0]
                if size > 64 * 1024 * 1024:
                    raise ValueError("mmap index journal record exceeds format limit")
                payload, checksum = stream.read(size), stream.read(32)
                if len(payload) != size or len(checksum) != 32:
                    break  # Unacknowledged partial batch; next writer discards this tail.
                if hashlib.sha256(length + payload).digest() != checksum:
                    raise ValueError("mmap index journal checksum mismatch")
                published = json.loads(payload)
                if not isinstance(published, dict):
                    raise ValueError("mmap journal batch must be an object")
                for key, entry in published.items():
                    if entry["offset"] < 0 or entry["size"] < 4 or entry["offset"] + entry["size"] > data_size:
                        raise ValueError("mmap journal references a truncated or invalid record")
                    if key in self.index and self.index[key] != entry:
                        raise ValueError("mmap journal overwrites an immutable entry")
                self.index.update(published)
                self._journal_offset = stream.tell()

    def _save_index(self, published):
        payload = json.dumps(published, sort_keys=True, separators=(",", ":")).encode()
        if len(payload) > 64 * 1024 * 1024:
            raise ValueError("mmap index batch exceeds format limit")
        framed = struct.pack("<I", len(payload)) + payload
        created = not self.journal_path.exists()
        with self.journal_path.open("r+b" if not created else "w+b") as stream:
            stream.truncate(self._journal_offset)
            stream.seek(self._journal_offset)
            stream.write(framed + hashlib.sha256(framed).digest())
            stream.flush()
            if self.durable:
                os.fsync(stream.fileno())
            new_offset = stream.tell()
        if self.durable and created and os.name != "nt":
            descriptor = os.open(self.root, os.O_RDONLY)
            try:
                os.fsync(descriptor)
            finally:
                os.close(descriptor)
        self._journal_offset = new_offset
        stat = self.journal_path.stat()
        self._index_version = (stat.st_mtime_ns, stat.st_size)

    def contains(self, key):
        return self.lookup(key) is not None

    def clear(self):
        """Explicit fresh-cache setup; callers must not have active readers."""
        with self._transaction():
            self.close()
            for path in (self.data_path, self.index_path, self.journal_path):
                path.unlink(missing_ok=True)
            self.index = {}
            self._journal_offset = 0
            self._index_version = None
            self.reset_metrics()

    def lookup(self, key):
        with self._transaction():
            entry = self.index.get(key)
            return None if entry is None else (entry["offset"], entry["size"])

    def reset_metrics(self):
        self.metrics = dict(bytesRead=0, bytesAppended=0, entriesAppended=0,
                            readMs=0.0, appendMs=0.0, mmapReads=0, remaps=0)

    def get(self, key):
        with self._transaction():
            started = time.perf_counter()
            entry = self.index.get(key)
            if entry is None:
                return None
            if self._mapping is None:
                self._stream = self.data_path.open("rb")
                self._mapping = mmap.mmap(self._stream.fileno(), 0, access=mmap.ACCESS_READ)
                self.metrics["remaps"] += 1
            offset, size = entry["offset"], entry["size"]
            data = self._mapping[offset:offset + size]
            if len(data) != size or struct.unpack("<I", data[:4])[0] != size - 4:
                raise ValueError("mmap payload is truncated or has an invalid length")
            if entry.get("sha256") and hashlib.sha256(data).hexdigest() != entry["sha256"]:
                raise ValueError("mmap payload checksum mismatch")
            self.metrics["bytesRead"] += len(data)
            self.metrics["mmapReads"] += 1
            self.metrics["readMs"] += (time.perf_counter() - started) * 1000
            return data

    def put(self, key, payload):
        return self.put_many([(key, payload)])[key]

    def put_many(self, entries):
        with self._transaction():
            started = time.perf_counter()
            pending = {}
            result = {}
            for key, payload in entries:
                if key in pending and pending[key] != payload:
                    raise ValueError("conflicting values for an immutable mmap key")
                if key in self.index:
                    entry = self.index[key]
                    checksum = hashlib.sha256(struct.pack("<I", len(payload)) + payload).hexdigest()
                    if entry.get("sha256") != checksum:
                        raise ValueError("cannot overwrite an immutable mmap key (legacy indices require fresh population)")
                    result[key] = (entry["offset"], entry["size"])
                else:
                    pending[key] = payload
            if not pending:
                return result
            self.close()
            published = {}
            with self.data_path.open("ab") as stream:
                for key, payload in pending.items():
                    record = struct.pack("<I", len(payload)) + payload
                    offset = stream.tell()
                    stream.write(record)
                    published[key] = {"offset": offset, "size": len(record),
                                      "sha256": hashlib.sha256(record).hexdigest()}
                    result[key] = (offset, len(record))
                stream.flush()
                if self.durable:
                    os.fsync(stream.fileno())
            self.index.update(published)
            try:
                self._save_index(published)
            except BaseException:
                for key in published:
                    del self.index[key]
                raise
            self.metrics["bytesAppended"] += sum(entry["size"] for entry in published.values())
            self.metrics["entriesAppended"] += len(published)
            self.metrics["appendMs"] += (time.perf_counter() - started) * 1000
            return result

    def close(self):
        if self._mapping is not None:
            self._mapping.close()
            self._mapping = None
        if self._stream is not None:
            self._stream.close()
            self._stream = None
