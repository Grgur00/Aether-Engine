"""Benchmark adapter: all artifact storage and lookup go through the Java daemon."""
import time
import json
from types import SimpleNamespace

from .client import AetherTrainingCache, CacheKey, TransformationFingerprint


class JavaArtifactStore:
    engine = "java-training-cache"

    def __init__(self, *, port, namespace="tpds"):
        self.client = AetherTrainingCache(port=port)
        self.namespace = namespace
        self.reset_operation_metrics()

    def _key(self, key):
        # The caller's digest already binds source identity/content and the full
        # deterministic descriptor. No Python index or artifact store is used.
        return CacheKey(self.namespace, key,
                        TransformationFingerprint.from_descriptor("aether-derived-artifact-v1"))

    def engine_info(self):
        return json.loads(self.client._round_trip(bytes([1, 7])))

    def _record(self, operation, started):
        self._metrics.setdefault(operation, []).append((time.perf_counter() - started) * 1000)

    def load_cached_bytes_many(self, keys):
        started = time.perf_counter()
        keys = list(keys)
        result = {}
        for start in range(0, len(keys), 64):
            batch = keys[start:start + 64]
            values = self.client.get_many([self._key(key) for key in batch])
            result.update({key: values[self._key(key)] for key in batch if self._key(key) in values})
        self._record("lookup", started)
        return result

    def cached_artifact_ids(self, keys):
        keys = list(keys)
        result = {}
        for start in range(0, len(keys), 4096):
            batch = keys[start:start + 4096]
            present = self.client.contains_many([self._key(key) for key in batch])
            result.update({key: key for key in batch if self._key(key) in present})
        return result

    def commit_bytes_many(self, entries, **metadata):
        started = time.perf_counter()
        entries = list(entries)
        batch, batch_bytes = [], 0
        for entry in entries:
            size = len(entry["data"])
            if size > 60 * 1024 * 1024:
                raise ValueError("artifact exceeds daemon frame budget")
            if batch and (batch_bytes + size > 60 * 1024 * 1024 or len(batch) >= 64):
                self.client.put_many(batch)
                batch, batch_bytes = [], 0
            batch.append((self._key(entry["cache_key"]), entry["data"]))
            batch_bytes += size + 256
        if batch:
            self.client.put_many(batch)
        self._record("publish", started)
        return [SimpleNamespace(size=len(entry["data"])) for entry in entries]

    def reset_operation_metrics(self):
        self._metrics = {}
        self.client.connections_opened = 0
        self.client.requests_sent = 0
        self.client.operation_counts = {}

    def operation_metrics(self):
        return {key: {"count": len(values), "mean": sum(values) / len(values),
                      "p95": sorted(values)[min(len(values)-1, int(len(values)*.95))],
                      "max": max(values), "unit": "ms"}
                for key, values in self._metrics.items()}

    def close(self):
        self.client.close()
