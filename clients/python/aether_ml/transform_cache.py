import os
import time
import inspect
import logging
from collections import Counter
from typing import Any, Callable, Iterable

from .codecs import ArtifactCodec, AutoCodec
from .exceptions import CacheMissError, CacheUnavailableError, PublishError
from .identity import artifact_key, fingerprint_transform


class AetherTransformCache:
    """Persistent cache for one explicitly identified deterministic transform."""

    def __init__(self, client: Any | Callable[[], Any] | None, transform: Callable[[Any], Any], *, namespace: str,
                 transform_identity: str | bytes | dict | None = None, identity_fn: Callable[[Any, int], str | bytes] | None = None,
                 codec: ArtifactCodec | None = None, artifact_schema_version: str = "1", mode: str = "read-write",
                 on_cache_error: str = "raise", debug: bool = False):
        if mode not in {"read-write", "read-only", "populate", "disabled"}:
            raise ValueError("mode must be read-write, read-only, populate, or disabled")
        if on_cache_error not in {"raise", "fallback"}:
            raise ValueError("on_cache_error must be raise or fallback")
        if client is None:
            from .lifecycle import create_client
            client = create_client
        self._client_source, self._client_pid, self._client = client, None, None
        self.transform, self.namespace, self.identity_fn = transform, namespace, identity_fn
        self.transform_identity = transform_identity if transform_identity is not None else fingerprint_transform(transform)
        self.codec = codec or AutoCodec()
        self.artifact_schema_version, self.mode, self.on_cache_error = artifact_schema_version, mode, on_cache_error
        self.debug = debug
        self._logger = logging.getLogger("aether_ml.transform_cache")
        self._metrics = Counter()
        self._latencies = Counter()
        self._latency_samples = {"lookup": [], "publish": [], "decode": [], "batchPreparation": []}

    def _client_for_process(self):
        pid = os.getpid()
        if self._client_pid != pid:
            if self._client is not None:
                self._client.close()
            self._client = self._client_source() if callable(self._client_source) else self._client_source
            self._client_pid = pid
        return self._client

    def _key(self, source_identity: str | bytes):
        return artifact_key(self.namespace, source_identity, self.transform_identity, self.artifact_schema_version)

    def identity_for(self, source: Any, index: int) -> str | bytes:
        if self.identity_fn is None:
            raise ValueError("identity_fn is required for dataset samples; use explicit source_identity otherwise")
        try:
            inspect.signature(self.identity_fn).bind(source, index)
        except TypeError:
            return self.identity_fn(source)
        return self.identity_fn(source, index)

    def __call__(self, source: Any, *, source_identity: str | bytes) -> Any:
        return self.get_or_compute(source, source_identity=source_identity)

    def get_or_compute(self, source: Any, *, source_identity: str | bytes) -> Any:
        return self.get_many_or_compute([(source, source_identity)])[0]

    def plan(self, source_identities: Iterable[str | bytes], *, estimated_compute_seconds_per_sample: float | None = None) -> dict[str, int | float]:
        """Report reusable entries without transforming or publishing samples."""
        identities = list(source_identities)
        if estimated_compute_seconds_per_sample is not None and estimated_compute_seconds_per_sample < 0:
            raise ValueError("estimated_compute_seconds_per_sample must not be negative")
        if self.mode == "disabled":
            return _with_work_estimate({"total": len(identities), "reusable": 0, "missing": len(identities), "reuseRatio": 0.0},
                                       estimated_compute_seconds_per_sample)
        keys = [self._key(identity) for identity in identities]
        try:
            present = set()
            for start in range(0, len(keys), 4096):
                present.update(self._client_for_process().contains_many(keys[start:start + 4096]))
        except Exception as error:
            raise CacheUnavailableError("Aether cache planning failed") from error
        reusable = sum(key in present for key in keys)
        return _with_work_estimate({"total": len(keys), "reusable": reusable, "missing": len(keys) - reusable,
                        "reuseRatio": reusable / len(keys) if keys else 0.0}, estimated_compute_seconds_per_sample)

    def get_many_or_compute(self, sources: Iterable[tuple[Any, str | bytes]]) -> list[Any]:
        batch_started = time.perf_counter_ns()
        entries = list(sources)
        if len(entries) > 4096:
            result = []
            for start in range(0, len(entries), 4096):
                result.extend(self.get_many_or_compute(entries[start:start + 4096]))
            return result
        if self.mode == "disabled":
            return [self.transform(source) for source, _ in entries]
        keys = [self._key(identity) for _, identity in entries]
        unique = list(dict.fromkeys(keys))
        started = time.perf_counter_ns()
        try:
            cached = self._client_for_process().get_many(unique)
            self._metrics["lookups"] += len(keys)
            self._metrics["hits"] += sum(key in cached for key in keys)
        except Exception as error:
            self._metrics["cacheErrors"] += 1
            if self.on_cache_error == "fallback":
                self._logger.warning("Aether lookup failed; running deterministic transform without cache", exc_info=True)
                return [self.transform(source) for source, _ in entries]
            raise CacheUnavailableError("Aether cache lookup failed") from error
        finally:
            elapsed = time.perf_counter_ns() - started
            self._latencies["lookupNs"] += elapsed
            self._latency_samples["lookup"].append(elapsed)
        missing = [key for key in unique if key not in cached]
        if missing and self.mode == "read-only":
            raise CacheMissError(f"{len(missing)} requested artifacts are missing")
        computed = {}
        if missing:
            source_by_key = dict(zip(keys, (source for source, _ in entries)))
            computed = {key: self.codec.encode(self.transform(source_by_key[key])) for key in missing}
            self._metrics["misses"] += sum(key not in cached for key in keys)
            if self.mode in {"read-write", "populate"}:
                try:
                    publish_started = time.perf_counter_ns()
                    self._client_for_process().put_many(computed.items())
                    self._latency_samples["publish"].append(time.perf_counter_ns() - publish_started)
                    self._metrics["publishes"] += len(computed)
                    self._metrics["bytesPublished"] += sum(len(value) for value in computed.values())
                except Exception as error:
                    self._metrics["cacheErrors"] += 1
                    if self.on_cache_error == "raise":
                        raise PublishError("Aether cache publication failed") from error
                    self._logger.warning("Aether publication failed; returning uncached deterministic output", exc_info=True)
        payloads = {**cached, **computed}
        if self.debug:
            self._logger.debug("Aether batch namespace=%s requested=%d hits=%d misses=%d published=%d",
                               self.namespace, len(keys), sum(key in cached for key in keys),
                               sum(key not in cached for key in keys), len(computed))
        self._metrics["bytesRead"] += sum(len(payloads[key]) for key in keys if key in cached)
        decode_started = time.perf_counter_ns()
        result = [self.codec.decode(payloads[key]) for key in keys]
        self._latency_samples["decode"].append(time.perf_counter_ns() - decode_started)
        self._latency_samples["batchPreparation"].append(time.perf_counter_ns() - batch_started)
        return result

    def stats(self) -> dict[str, int | float]:
        lookups, hits = self._metrics["lookups"], self._metrics["hits"]
        return {**dict(self._metrics), "hitRate": hits / lookups if lookups else 0.0,
                "lookupNs": self._latencies["lookupNs"],
                "latency": {name: _latency_summary(values) for name, values in self._latency_samples.items()}}

    def reproducibility_metadata(self) -> dict[str, str]:
        return {"namespace": self.namespace, "artifactSchema": self.artifact_schema_version,
                "transformIdentity": str(self.transform_identity), "integrityPolicy": "engine-managed"}

    def close(self) -> None:
        if self._client is not None:
            self._client.close()
            self._client = None
            self._client_pid = None

    def __enter__(self):
        return self

    def __exit__(self, exc_type, exc_value, traceback):
        self.close()


def _latency_summary(values: list[int]) -> dict[str, float | int]:
    if not values:
        return {"count": 0, "meanMs": 0.0, "p95Ms": 0.0}
    ordered = sorted(values)
    p95_index = min(len(ordered) - 1, int(len(ordered) * 0.95))
    return {"count": len(values), "meanMs": sum(values) / len(values) / 1_000_000,
            "p95Ms": ordered[p95_index] / 1_000_000}


def _with_work_estimate(result: dict[str, int | float], seconds_per_sample: float | None) -> dict[str, int | float]:
    if seconds_per_sample is None:
        return result
    return {**result, "estimatedWorkAvoidedSeconds": result["reusable"] * seconds_per_sample,
            "estimatedRemainingWorkSeconds": result["missing"] * seconds_per_sample}