from __future__ import annotations

import functools
import hashlib
import json
import os
import pickle
import platform
import shutil
import statistics
import subprocess
import sys
import tempfile
import threading
import time
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any, Callable, Iterable, Sequence


Serializer = Callable[[Any], bytes]
Deserializer = Callable[[bytes], Any]


def open(path: str | os.PathLike[str]) -> "AetherMLStore":
    return AetherMLStore(path)


def environment_report(
    *,
    dataset_version: str | None = None,
    code_commit: str | None = None,
    root: str | os.PathLike[str] | None = None,
) -> dict[str, Any]:
    return {
        "cpu": {
            "processor": platform.processor(),
            "machine": platform.machine(),
            "cores": os.cpu_count(),
        },
        "gpu": _gpu_report(),
        "ram": _ram_report(),
        "disk": _disk_report(root),
        "os": {
            "platform": platform.platform(),
            "system": platform.system(),
            "release": platform.release(),
        },
        "javaVersion": _java_version(),
        "pythonVersion": platform.python_version(),
        "pythonExecutable": sys.executable,
        "pytorchVersion": _pytorch_version(),
        "datasetVersion": dataset_version or "unknown",
        "gitCommit": code_commit or os.environ.get("AETHER_CODE_COMMIT") or _git_commit(root),
    }


@dataclass(frozen=True)
class ArtifactMetadata:
    artifact_id: str
    content_hash: str
    source_artifact_ids: list[str]
    transformation_name: str
    transformation_version: str
    parameters: dict[str, Any]
    created_at: float
    code_commit: str
    python_version: str
    library_versions: dict[str, str]
    storage_location: str
    size: int
    checksum: str
    cache_key: str


@dataclass(frozen=True)
class DatasetSnapshot:
    snapshot_id: int
    name: str
    artifact_ids: list[str]
    configuration: dict[str, Any]
    created_at: float


@dataclass(frozen=True)
class ExperimentRecord:
    experiment_id: str
    dataset_snapshot: int
    model: str
    training_parameters: dict[str, Any]
    random_seed: int | None = None
    environment: dict[str, Any] = field(default_factory=dict)
    start_time: float | None = None
    end_time: float | None = None
    metrics: dict[str, Any] = field(default_factory=dict)
    output_artifacts: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class RecoveryReport:
    temporary_files: list[str] = field(default_factory=list)
    dangling_cache_entries: list[str] = field(default_factory=list)
    corrupt_cache_entries: list[str] = field(default_factory=list)
    missing_artifacts: list[str] = field(default_factory=list)
    checksum_mismatches: list[str] = field(default_factory=list)
    missing_parent_artifacts: list[str] = field(default_factory=list)
    missing_snapshot_artifacts: list[str] = field(default_factory=list)
    missing_experiment_snapshots: list[str] = field(default_factory=list)
    orphan_artifact_files: list[str] = field(default_factory=list)

    @property
    def is_consistent(self) -> bool:
        return not any(asdict(self).values())


@dataclass(frozen=True)
class SnapshotVerificationReport:
    snapshot_id: int
    verified_artifact_ids: list[str] = field(default_factory=list)
    missing_metadata: list[str] = field(default_factory=list)
    missing_artifacts: list[str] = field(default_factory=list)
    checksum_mismatches: list[str] = field(default_factory=list)

    @property
    def is_reproducible(self) -> bool:
        return not self.missing_metadata and not self.missing_artifacts and not self.checksum_mismatches


class CachedTransform:
    """Callable transform that persists deterministic outputs in an AetherMLStore."""

    def __init__(
        self,
        store: "AetherMLStore",
        function: Callable[..., Any],
        *,
        version: str,
        name: str | None = None,
        parameters: dict[str, Any] | None = None,
    ):
        self.store = store
        self.function = function
        self.name = name or f"{function.__module__}.{function.__qualname__}"
        self.version = str(version)
        self.parameters = parameters or {}
        functools.update_wrapper(self, function)

    def __call__(self, *args, **kwargs):
        value, _ = self.apply(*args, **kwargs)
        return value

    def apply(self, *args, source_artifact_ids: Iterable[str] = (), **kwargs) -> tuple[Any, ArtifactMetadata]:
        cache_key, inferred_source_artifact_ids = self.cache_key_for_call(*args, **kwargs)
        source_ids = list(dict.fromkeys([*inferred_source_artifact_ids, *source_artifact_ids]))
        cached = self.store.load_cached(cache_key)
        if cached is not None:
            artifact_id = self.store.cached_artifact_id(cache_key)
            if artifact_id is None:
                raise IOError(f"cache index disappeared for {cache_key}")
            return cached, self.store.artifact_metadata(artifact_id)
        result = self.function(*args, **kwargs)
        metadata = self.store.commit_artifact(
            result,
            cache_key=cache_key,
            source_artifact_ids=source_ids,
            transformation_name=self.name,
            transformation_version=self.version,
            parameters=self.parameters,
        )
        return result, metadata

    def cache_key_for_call(self, *args, **kwargs) -> tuple[str, list[str]]:
        input_hash, source_artifact_ids = self.store._hash_inputs(args, kwargs)
        cache_key = self.store.transformation_key(
            input_hash=input_hash,
            transformation_name=self.name,
            transformation_version=self.version,
            parameters=self.parameters,
        )
        return cache_key, source_artifact_ids

    def aether_cache_key(self, *args, **kwargs) -> str:
        cache_key, _ = self.cache_key_for_call(*args, **kwargs)
        return cache_key

    def aether_artifact_id(self, *args, **kwargs) -> str | None:
        return self.store.cached_artifact_id(self.aether_cache_key(*args, **kwargs))


class AetherDataset:
    """Dataset wrapper that applies AetherML cached transforms from any indexable source."""

    def __init__(
        self,
        source: Sequence | Any,
        transforms: Iterable[Callable[[Any], Any]] = (),
        *,
        return_artifact_ids: bool = False,
    ):
        if not hasattr(source, "__len__") or not hasattr(source, "__getitem__"):
            raise TypeError("source must provide __len__ and __getitem__")
        self.source = source
        self.transforms = list(transforms)
        self.return_artifact_ids = return_artifact_ids

    def __len__(self) -> int:
        return len(self.source)

    def __getitem__(self, index: int):
        value = self.source[index]
        artifact_ids = []
        for transform in self.transforms:
            transform_input = value
            if isinstance(transform, CachedTransform):
                value, metadata = transform.apply(transform_input, source_artifact_ids=artifact_ids[-1:])
                artifact_ids.append(metadata.artifact_id)
            else:
                value = transform(value)
                artifact_id = getattr(transform, "aether_artifact_id", lambda *_args, **_kwargs: None)(transform_input)
                if artifact_id is not None:
                    artifact_ids.append(artifact_id)
        if self.return_artifact_ids:
            return value, artifact_ids
        return value


class _SequentialAetherDataLoader:
    def __init__(
        self,
        dataset: Sequence | Any,
        *,
        batch_size: int = 1,
        shuffle: bool = False,
        drop_last: bool = False,
        collate_fn: Callable[[list[Any]], Any] | None = None,
    ):
        if batch_size <= 0:
            raise ValueError("batch_size must be greater than zero")
        if not hasattr(dataset, "__len__") or not hasattr(dataset, "__getitem__"):
            raise TypeError("dataset must provide __len__ and __getitem__")
        self.dataset = dataset
        self.batch_size = batch_size
        self.shuffle = shuffle
        self.drop_last = drop_last
        self.collate_fn = collate_fn or (lambda batch: batch)

    def __len__(self) -> int:
        full_batches, remainder = divmod(len(self.dataset), self.batch_size)
        return full_batches if self.drop_last or remainder == 0 else full_batches + 1

    def __iter__(self):
        indexes = list(range(len(self.dataset)))
        if self.shuffle:
            import random

            random.shuffle(indexes)
        for offset in range(0, len(indexes), self.batch_size):
            batch_indexes = indexes[offset : offset + self.batch_size]
            if self.drop_last and len(batch_indexes) < self.batch_size:
                continue
            yield self.collate_fn([self.dataset[index] for index in batch_indexes])


class AetherDataLoader:
    """PyTorch-compatible data loader entrypoint for AetherDataset pipelines."""

    def __new__(
        cls,
        dataset: Sequence | Any,
        *,
        batch_size: int = 1,
        shuffle: bool = False,
        num_workers: int = 0,
        drop_last: bool = False,
        collate_fn: Callable[[list[Any]], Any] | None = None,
        **kwargs,
    ):
        if num_workers or kwargs:
            try:
                from torch.utils.data import DataLoader as TorchDataLoader
            except ImportError as exc:
                raise ImportError(
                    "PyTorch is required for AetherDataLoader options beyond the sequential fallback"
                ) from exc
            return TorchDataLoader(
                dataset,
                batch_size=batch_size,
                shuffle=shuffle,
                num_workers=num_workers,
                drop_last=drop_last,
                collate_fn=collate_fn,
                **kwargs,
            )
        return _SequentialAetherDataLoader(
            dataset,
            batch_size=batch_size,
            shuffle=shuffle,
            drop_last=drop_last,
            collate_fn=collate_fn,
        )


class AetherMLStore:
    """Crash-conscious local artifact and provenance store for ML preprocessing."""

    def __init__(
        self,
        root: str | os.PathLike[str],
        serializer: Serializer | None = None,
        deserializer: Deserializer | None = None,
        code_commit: str | None = None,
        library_versions: dict[str, str] | None = None,
    ):
        self.root = Path(root)
        self.artifacts_dir = self.root / "artifacts"
        self.metadata_dir = self.root / "metadata" / "artifacts"
        self.snapshots_dir = self.root / "metadata" / "snapshots"
        self.experiments_dir = self.root / "metadata" / "experiments"
        self.cache_dir = self.root / "metadata" / "cache"
        self.tmp_dir = self.root / "tmp"
        self.serializer = serializer or pickle.dumps
        self.deserializer = deserializer or pickle.loads
        self.code_commit = code_commit or os.environ.get("AETHER_CODE_COMMIT", "unknown")
        self.library_versions = dict(library_versions or {})
        self._lock = threading.RLock()
        self._operation_latencies: dict[str, list[int]] = {
            "artifact_commit": [],
            "artifact_retrieval": [],
            "cache_lookup": [],
            "metadata_lookup": [],
            "snapshot_create": [],
        }
        self._ensure_layout()

    def __enter__(self) -> "AetherMLStore":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        return None

    def _ensure_layout(self) -> None:
        for directory in (
            self.artifacts_dir,
            self.metadata_dir,
            self.snapshots_dir,
            self.experiments_dir,
            self.cache_dir,
            self.tmp_dir,
        ):
            directory.mkdir(parents=True, exist_ok=True)

    def cached_transform(
        self,
        version: str,
        *,
        name: str | None = None,
        parameters: dict[str, Any] | None = None,
    ):
        def decorate(function):
            return self.cached_transform_function(
                function,
                version=version,
                name=name,
                parameters=parameters,
            )

        return decorate

    def cached_transform_function(
        self,
        function: Callable[..., Any],
        *,
        version: str,
        name: str | None = None,
        parameters: dict[str, Any] | None = None,
    ) -> CachedTransform:
        return CachedTransform(self, function, version=version, name=name, parameters=parameters)

    def transformation_key(
        self,
        *,
        input_hash: str,
        transformation_name: str,
        transformation_version: str,
        parameters: dict[str, Any] | None = None,
    ) -> str:
        descriptor = {
            "code_commit": self.code_commit,
            "input_hash": input_hash,
            "library_versions": self.library_versions,
            "parameters": parameters or {},
            "python_version": platform.python_version(),
            "transformation_name": transformation_name,
            "transformation_version": transformation_version,
        }
        return hashlib.sha256(_canonical_json(descriptor)).hexdigest()

    def commit_artifact(
        self,
        value: Any,
        *,
        cache_key: str,
        source_artifact_ids: Iterable[str] = (),
        transformation_name: str,
        transformation_version: str,
        parameters: dict[str, Any] | None = None,
    ) -> ArtifactMetadata:
        data = self.serializer(value)
        return self.commit_bytes(
            data,
            cache_key=cache_key,
            source_artifact_ids=source_artifact_ids,
            transformation_name=transformation_name,
            transformation_version=transformation_version,
            parameters=parameters,
        )

    def commit_file(
        self,
        path: str | os.PathLike[str],
        *,
        cache_key: str,
        source_artifact_ids: Iterable[str] = (),
        transformation_name: str,
        transformation_version: str,
        parameters: dict[str, Any] | None = None,
    ) -> ArtifactMetadata:
        return self.commit_bytes(
            Path(path).read_bytes(),
            cache_key=cache_key,
            source_artifact_ids=source_artifact_ids,
            transformation_name=transformation_name,
            transformation_version=transformation_version,
            parameters=parameters,
        )

    def commit_bytes(
        self,
        data: bytes,
        *,
        cache_key: str,
        source_artifact_ids: Iterable[str] = (),
        transformation_name: str,
        transformation_version: str,
        parameters: dict[str, Any] | None = None,
    ) -> ArtifactMetadata:
        started = time.perf_counter_ns()
        try:
            with self._lock:
                return self._commit_bytes_locked(
                    data,
                    cache_key=cache_key,
                    source_artifact_ids=source_artifact_ids,
                    transformation_name=transformation_name,
                    transformation_version=transformation_version,
                    parameters=parameters,
                )
        finally:
            self._record_latency("artifact_commit", started)

    def commit_bytes_many(
        self,
        entries: Iterable[dict[str, Any]],
        *,
        transformation_name: str,
        transformation_version: str,
        parameters: dict[str, Any] | None = None,
    ) -> list[ArtifactMetadata]:
        started = time.perf_counter_ns()
        try:
            result = []
            with self._lock:
                for entry in entries:
                    result.append(
                        self._commit_bytes_locked(
                            entry["data"],
                            cache_key=entry["cache_key"],
                            source_artifact_ids=entry.get("source_artifact_ids", ()),
                            transformation_name=transformation_name,
                            transformation_version=transformation_version,
                            parameters=entry.get("parameters", parameters),
                        )
                    )
            return result
        finally:
            self._record_latency("artifact_commit_batch", started)

    def _commit_bytes_locked(
        self,
        data: bytes,
        *,
        cache_key: str,
        source_artifact_ids: Iterable[str] = (),
        transformation_name: str,
        transformation_version: str,
        parameters: dict[str, Any] | None = None,
    ) -> ArtifactMetadata:
        data = bytes(data)
        content_hash = hashlib.sha256(data).hexdigest()
        artifact_identity = hashlib.sha256(f"{cache_key}:{content_hash}".encode("utf-8")).hexdigest()
        artifact_id = f"aether:{artifact_identity}"
        relative_storage = _artifact_relative_path(content_hash)
        artifact_path = self.root / relative_storage
        metadata_path = self._metadata_path(artifact_id)
        cache_path = self._cache_path(cache_key)

        if not artifact_path.exists():
            artifact_path.parent.mkdir(parents=True, exist_ok=True)
            self._atomic_write_bytes(artifact_path, data)

        metadata = ArtifactMetadata(
            artifact_id=artifact_id,
            content_hash=content_hash,
            source_artifact_ids=list(source_artifact_ids),
            transformation_name=transformation_name,
            transformation_version=str(transformation_version),
            parameters=parameters or {},
            created_at=time.time(),
            code_commit=self.code_commit,
            python_version=platform.python_version(),
            library_versions=self.library_versions,
            storage_location=relative_storage.as_posix(),
            size=len(data),
            checksum=content_hash,
            cache_key=cache_key,
        )
        if not metadata_path.exists():
            self._atomic_write_json(metadata_path, asdict(metadata))
        self._atomic_write_json(cache_path, {
            "artifact_id": artifact_id,
            "storage_location": metadata.storage_location,
            "checksum": metadata.checksum,
            "size": metadata.size,
        })
        return metadata

    def load_cached(self, cache_key: str) -> Any | None:
        artifact_id = self.cached_artifact_id(cache_key)
        if artifact_id is None or not self._metadata_path(artifact_id).exists():
            return None
        return self.load_artifact(artifact_id)

    def cached_artifact_id(self, cache_key: str) -> str | None:
        started = time.perf_counter_ns()
        try:
            cache_path = self._cache_path(cache_key)
            if not cache_path.exists():
                return None
            try:
                return json.loads(cache_path.read_text(encoding="utf-8"))["artifact_id"]
            except (KeyError, json.JSONDecodeError):
                return None
        finally:
            self._record_latency("cache_lookup", started)

    def cached_artifact_ids(self, cache_keys: Iterable[str]) -> dict[str, str]:
        started = time.perf_counter_ns()
        try:
            result = {}
            for cache_key in cache_keys:
                cache_entry = self._cache_entry(cache_key)
                if cache_entry is not None:
                    result[cache_key] = cache_entry["artifact_id"]
            return result
        finally:
            self._record_latency("cache_lookup_batch", started)

    def load_cached_bytes_many(self, cache_keys: Iterable[str]) -> dict[str, bytes]:
        started = time.perf_counter_ns()
        try:
            result = {}
            for cache_key in cache_keys:
                cache_entry = self._cache_entry(cache_key)
                if cache_entry is None:
                    continue
                try:
                    result[cache_key] = self._load_cache_entry_bytes(cache_entry)
                except (KeyError, FileNotFoundError, IOError):
                    continue
            return result
        finally:
            self._record_latency("cache_payload_retrieval_batch", started)

    def _cache_entry(self, cache_key: str) -> dict[str, Any] | None:
        cache_path = self._cache_path(cache_key)
        if not cache_path.exists():
            return None
        try:
            return json.loads(cache_path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return None

    def _load_cache_entry_bytes(self, cache_entry: dict[str, Any]) -> bytes:
        storage_location = cache_entry.get("storage_location")
        checksum = cache_entry.get("checksum")
        if storage_location is None or checksum is None:
            artifact_id = cache_entry["artifact_id"]
            metadata = self.artifact_metadata(artifact_id)
            storage_location = metadata.storage_location
            checksum = metadata.checksum
        data = (self.root / storage_location).read_bytes()
        if hashlib.sha256(data).hexdigest() != checksum:
            raise IOError(f"artifact checksum mismatch for cached artifact {cache_entry.get('artifact_id')}")
        return data

    def load_artifact(self, artifact_id: str) -> Any:
        return self.deserializer(self.load_artifact_bytes(artifact_id))

    def load_artifact_bytes(self, artifact_id: str) -> bytes:
        started = time.perf_counter_ns()
        try:
            metadata = self.artifact_metadata(artifact_id)
            artifact_path = self.root / metadata.storage_location
            data = artifact_path.read_bytes()
            checksum = hashlib.sha256(data).hexdigest()
            if checksum != metadata.checksum:
                raise IOError(f"artifact checksum mismatch for {artifact_id}")
            return data
        finally:
            self._record_latency("artifact_retrieval", started)

    def load_artifact_bytes_many(self, artifact_ids: Iterable[str]) -> dict[str, bytes]:
        started = time.perf_counter_ns()
        try:
            result = {}
            for artifact_id in artifact_ids:
                metadata = self.artifact_metadata(artifact_id)
                artifact_path = self.root / metadata.storage_location
                data = artifact_path.read_bytes()
                checksum = hashlib.sha256(data).hexdigest()
                if checksum != metadata.checksum:
                    raise IOError(f"artifact checksum mismatch for {artifact_id}")
                result[artifact_id] = data
            return result
        finally:
            self._record_latency("artifact_retrieval_batch", started)

    def artifact_path(self, artifact_id: str) -> Path:
        metadata = self.artifact_metadata(artifact_id)
        path = self.root / metadata.storage_location
        if not path.exists():
            raise FileNotFoundError(path)
        return path

    def artifact_metadata(self, artifact_id: str) -> ArtifactMetadata:
        started = time.perf_counter_ns()
        try:
            path = self._metadata_path(artifact_id)
            if not path.exists():
                raise KeyError(f"unknown artifact {artifact_id}")
            return ArtifactMetadata(**json.loads(path.read_text(encoding="utf-8")))
        finally:
            self._record_latency("metadata_lookup", started)

    def parents(self, artifact_id: str) -> list[str]:
        return self.artifact_metadata(artifact_id).source_artifact_ids

    def children(self, artifact_id: str) -> list[str]:
        result = []
        for metadata in self._iter_artifact_metadata():
            if artifact_id in metadata.source_artifact_ids:
                result.append(metadata.artifact_id)
        return result

    def lineage(self, artifact_id: str) -> list[ArtifactMetadata]:
        ordered = []
        seen = set()

        def visit(current: str) -> None:
            if current in seen:
                return
            seen.add(current)
            metadata = self.artifact_metadata(current)
            for parent in metadata.source_artifact_ids:
                visit(parent)
            ordered.append(metadata)

        visit(artifact_id)
        return ordered

    def depends_on(self, artifact_id: str, ancestor_artifact_id: str) -> bool:
        return any(metadata.artifact_id == ancestor_artifact_id for metadata in self.lineage(artifact_id))

    def create_snapshot(
        self,
        name: str,
        artifact_ids: Iterable[str],
        configuration: dict[str, Any] | None = None,
    ) -> DatasetSnapshot:
        started = time.perf_counter_ns()
        try:
            with self._lock:
                existing = [int(path.stem) for path in self.snapshots_dir.glob("*.json") if path.stem.isdigit()]
                snapshot_id = max(existing, default=0) + 1
                snapshot = DatasetSnapshot(
                    snapshot_id=snapshot_id,
                    name=name,
                    artifact_ids=list(artifact_ids),
                    configuration=configuration or {},
                    created_at=time.time(),
                )
                self._atomic_write_json(self.snapshots_dir / f"{snapshot_id}.json", asdict(snapshot))
                return snapshot
        finally:
            self._record_latency("snapshot_create", started)

    def reset_operation_metrics(self) -> None:
        for values in self._operation_latencies.values():
            values.clear()

    def operation_metrics(self) -> dict[str, dict[str, Any]]:
        return {
            operation: _latency_distribution(values)
            for operation, values in sorted(self._operation_latencies.items())
        }

    def storage_metrics(
        self,
        *,
        raw_dataset_bytes: int = 0,
        baseline_preprocessed_bytes: int = 0,
    ) -> dict[str, Any]:
        files = [entry for entry in self.root.rglob("*") if entry.is_file()]
        bytes_by_top_level = {}
        for entry in files:
            top_level = entry.relative_to(self.root).parts[0]
            bytes_by_top_level[top_level] = bytes_by_top_level.get(top_level, 0) + entry.stat().st_size

        artifact_paths = set()
        logical_artifact_bytes = 0
        for metadata in self._iter_artifact_metadata():
            artifact_paths.add((self.root / metadata.storage_location).resolve())
            logical_artifact_bytes += metadata.size

        cached_artifact_bytes = sum(path.stat().st_size for path in artifact_paths if path.exists())
        metadata_bytes = _directory_size(self.root / "metadata")
        cache_index_bytes = _directory_size(self.cache_dir)
        total_storage_bytes = sum(entry.stat().st_size for entry in files)
        return {
            "files": len(files),
            "bytes": total_storage_bytes,
            "bytesByTopLevel": dict(sorted(bytes_by_top_level.items())),
            "rawDatasetBytes": raw_dataset_bytes,
            "baselinePreprocessedBytes": baseline_preprocessed_bytes,
            "cachedArtifactBytes": cached_artifact_bytes,
            "logicalArtifactBytes": logical_artifact_bytes,
            "metadataBytes": metadata_bytes,
            "cacheIndexBytes": cache_index_bytes,
            "walBytes": 0,
            "compactionWriteBytes": 0,
            "totalStorageBytes": total_storage_bytes,
            "writeAmplification": _ratio(total_storage_bytes, raw_dataset_bytes),
            "cacheAmplification": _ratio(cached_artifact_bytes, raw_dataset_bytes),
            "metadataOverhead": _ratio(metadata_bytes, cached_artifact_bytes),
        }

    def open_snapshot(self, snapshot_id: int) -> DatasetSnapshot:
        path = self.snapshots_dir / f"{snapshot_id}.json"
        if not path.exists():
            raise KeyError(f"unknown snapshot {snapshot_id}")
        return DatasetSnapshot(**json.loads(path.read_text(encoding="utf-8")))

    def restore_snapshot(self, snapshot_id: int) -> dict[str, Any]:
        snapshot = self.open_snapshot(snapshot_id)
        return {artifact_id: self.load_artifact(artifact_id) for artifact_id in snapshot.artifact_ids}

    def snapshot_manifest(self, snapshot_id: int, *, include_lineage: bool = True) -> dict[str, Any]:
        snapshot = self.open_snapshot(snapshot_id)
        artifacts = []
        seen = set()
        for artifact_id in snapshot.artifact_ids:
            metadata_items = self.lineage(artifact_id) if include_lineage else [self.artifact_metadata(artifact_id)]
            for metadata in metadata_items:
                if metadata.artifact_id not in seen:
                    seen.add(metadata.artifact_id)
                    artifacts.append(asdict(metadata))
        return {"snapshot": asdict(snapshot), "artifacts": artifacts}

    def verify_snapshot(self, snapshot_id: int) -> SnapshotVerificationReport:
        snapshot = self.open_snapshot(snapshot_id)
        verified = []
        missing_metadata = []
        missing_artifacts = []
        checksum_mismatches = []
        for artifact_id in snapshot.artifact_ids:
            metadata_path = self._metadata_path(artifact_id)
            if not metadata_path.exists():
                missing_metadata.append(artifact_id)
                continue
            metadata = self.artifact_metadata(artifact_id)
            artifact_path = self.root / metadata.storage_location
            if not artifact_path.exists():
                missing_artifacts.append(artifact_id)
                continue
            data = artifact_path.read_bytes()
            checksum = hashlib.sha256(data).hexdigest()
            if checksum != metadata.checksum or len(data) != metadata.size:
                checksum_mismatches.append(artifact_id)
                continue
            verified.append(artifact_id)
        return SnapshotVerificationReport(
            snapshot_id=snapshot_id,
            verified_artifact_ids=verified,
            missing_metadata=missing_metadata,
            missing_artifacts=missing_artifacts,
            checksum_mismatches=checksum_mismatches,
        )

    def compare_snapshots(self, left_id: int, right_id: int) -> dict[str, list[str]]:
        left = set(self.open_snapshot(left_id).artifact_ids)
        right = set(self.open_snapshot(right_id).artifact_ids)
        return {
            "added": sorted(right - left),
            "removed": sorted(left - right),
            "unchanged": sorted(left & right),
        }

    def record_experiment(self, record: ExperimentRecord) -> None:
        self._atomic_write_json(self.experiments_dir / f"{record.experiment_id}.json", asdict(record))

    def experiments_using(self, artifact_id: str) -> list[ExperimentRecord]:
        result = []
        for path in self.experiments_dir.glob("*.json"):
            record = ExperimentRecord(**json.loads(path.read_text(encoding="utf-8")))
            snapshot = self.open_snapshot(record.dataset_snapshot)
            candidate_artifact_ids = set(snapshot.artifact_ids) | set(record.output_artifacts)
            if any(self.depends_on(candidate_id, artifact_id) for candidate_id in candidate_artifact_ids):
                result.append(record)
        return result

    def validate(self) -> RecoveryReport:
        metadata_by_artifact = {}
        missing_artifacts = []
        checksum_mismatches = []
        missing_parent_artifacts = []
        referenced_storage_paths = set()

        for metadata in self._iter_artifact_metadata():
            metadata_by_artifact[metadata.artifact_id] = metadata
            artifact_path = self.root / metadata.storage_location
            referenced_storage_paths.add(artifact_path.resolve())
            if not artifact_path.exists():
                missing_artifacts.append(metadata.artifact_id)
                continue
            data = artifact_path.read_bytes()
            checksum = hashlib.sha256(data).hexdigest()
            if checksum != metadata.checksum or len(data) != metadata.size:
                checksum_mismatches.append(metadata.artifact_id)
            for parent in metadata.source_artifact_ids:
                if parent not in metadata_by_artifact and not self._metadata_path(parent).exists():
                    missing_parent_artifacts.append(f"{metadata.artifact_id}->{parent}")

        dangling_cache_entries = []
        corrupt_cache_entries = []
        for path in self.cache_dir.glob("*.json"):
            try:
                artifact_id = json.loads(path.read_text(encoding="utf-8"))["artifact_id"]
            except (KeyError, json.JSONDecodeError):
                corrupt_cache_entries.append(path.name)
                continue
            if artifact_id not in metadata_by_artifact or artifact_id in missing_artifacts or artifact_id in checksum_mismatches:
                dangling_cache_entries.append(path.name)

        missing_snapshot_artifacts = []
        for path in self.snapshots_dir.glob("*.json"):
            snapshot = DatasetSnapshot(**json.loads(path.read_text(encoding="utf-8")))
            for artifact_id in snapshot.artifact_ids:
                if artifact_id not in metadata_by_artifact:
                    missing_snapshot_artifacts.append(f"{snapshot.snapshot_id}->{artifact_id}")

        missing_experiment_snapshots = []
        for path in self.experiments_dir.glob("*.json"):
            record = ExperimentRecord(**json.loads(path.read_text(encoding="utf-8")))
            if not (self.snapshots_dir / f"{record.dataset_snapshot}.json").exists():
                missing_experiment_snapshots.append(f"{record.experiment_id}->{record.dataset_snapshot}")

        orphan_artifact_files = []
        if self.artifacts_dir.exists():
            for path in self.artifacts_dir.rglob("*"):
                if path.is_file() and path.resolve() not in referenced_storage_paths:
                    orphan_artifact_files.append(path.relative_to(self.root).as_posix())

        temporary_files = [
            path.relative_to(self.root).as_posix()
            for path in self.tmp_dir.glob("*")
            if path.is_file()
        ]
        return RecoveryReport(
            temporary_files=sorted(temporary_files),
            dangling_cache_entries=sorted(dangling_cache_entries),
            corrupt_cache_entries=sorted(corrupt_cache_entries),
            missing_artifacts=sorted(missing_artifacts),
            checksum_mismatches=sorted(checksum_mismatches),
            missing_parent_artifacts=sorted(missing_parent_artifacts),
            missing_snapshot_artifacts=sorted(missing_snapshot_artifacts),
            missing_experiment_snapshots=sorted(missing_experiment_snapshots),
            orphan_artifact_files=sorted(orphan_artifact_files),
        )

    def recover(self, *, remove_orphan_artifacts: bool = False) -> RecoveryReport:
        report = self.validate()
        with self._lock:
            for relative in report.temporary_files:
                (self.root / relative).unlink(missing_ok=True)
            for filename in [*report.dangling_cache_entries, *report.corrupt_cache_entries]:
                (self.cache_dir / filename).unlink(missing_ok=True)
            if remove_orphan_artifacts:
                for relative in report.orphan_artifact_files:
                    (self.root / relative).unlink(missing_ok=True)
        return report

    def _hash_inputs(self, args, kwargs) -> tuple[str, list[str]]:
        source_artifact_ids = []
        normalized_args = []
        for value in args:
            normalized, artifact_id = self._normalize_input(value)
            normalized_args.append(normalized)
            if artifact_id is not None:
                source_artifact_ids.append(artifact_id)
        normalized_kwargs = {}
        for key, value in kwargs.items():
            normalized, artifact_id = self._normalize_input(value)
            normalized_kwargs[key] = normalized
            if artifact_id is not None:
                source_artifact_ids.append(artifact_id)
        payload = self.serializer({"args": normalized_args, "kwargs": normalized_kwargs})
        return hashlib.sha256(payload).hexdigest(), source_artifact_ids

    def _normalize_input(self, value):
        if isinstance(value, ArtifactMetadata):
            return {"artifact_id": value.artifact_id}, value.artifact_id
        return value, None

    def _iter_artifact_metadata(self):
        for path in self.metadata_dir.glob("*.json"):
            yield ArtifactMetadata(**json.loads(path.read_text(encoding="utf-8")))

    def _metadata_path(self, artifact_id: str) -> Path:
        digest = artifact_id.removeprefix("aether:")
        return self.metadata_dir / f"{digest}.json"

    def _cache_path(self, cache_key: str) -> Path:
        return self.cache_dir / f"{cache_key}.json"

    def _record_latency(self, operation: str, started_ns: int) -> None:
        self._operation_latencies.setdefault(operation, []).append(time.perf_counter_ns() - started_ns)

    def _atomic_write_json(self, path: Path, value: dict[str, Any]) -> None:
        data = json.dumps(value, indent=2, sort_keys=True).encode("utf-8")
        self._atomic_write_bytes(path, data)

    def _atomic_write_bytes(self, path: Path, data: bytes) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        fd, tmp_name = tempfile.mkstemp(prefix=f".{path.name}.", suffix=".tmp", dir=self.tmp_dir)
        try:
            with os.fdopen(fd, "wb") as handle:
                handle.write(data)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(tmp_name, path)
        finally:
            if os.path.exists(tmp_name):
                os.unlink(tmp_name)


def _artifact_relative_path(content_hash: str) -> Path:
    return Path("artifacts") / content_hash[:2] / content_hash[2:4] / content_hash


def _canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":"), default=str).encode("utf-8")


def _gpu_report() -> dict[str, Any]:
    try:
        import torch
    except ImportError:
        return {"available": False, "backend": None, "devices": []}
    if not torch.cuda.is_available():
        return {"available": False, "backend": "torch", "devices": []}
    devices = []
    for index in range(torch.cuda.device_count()):
        properties = torch.cuda.get_device_properties(index)
        devices.append({
            "index": index,
            "name": properties.name,
            "totalMemoryBytes": properties.total_memory,
        })
    backend = "rocm" if getattr(torch.version, "hip", None) else "cuda"
    return {"available": True, "backend": backend, "devices": devices}


def _ram_report() -> dict[str, Any]:
    if sys.platform == "win32":
        try:
            import ctypes

            class MemoryStatus(ctypes.Structure):
                _fields_ = [
                    ("length", ctypes.c_ulong),
                    ("memory_load", ctypes.c_ulong),
                    ("total_physical", ctypes.c_ulonglong),
                    ("available_physical", ctypes.c_ulonglong),
                    ("total_page_file", ctypes.c_ulonglong),
                    ("available_page_file", ctypes.c_ulonglong),
                    ("total_virtual", ctypes.c_ulonglong),
                    ("available_virtual", ctypes.c_ulonglong),
                    ("available_extended_virtual", ctypes.c_ulonglong),
                ]

            status = MemoryStatus()
            status.length = ctypes.sizeof(status)
            if ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status)):
                return {
                    "totalBytes": int(status.total_physical),
                    "availableBytes": int(status.available_physical),
                }
        except Exception:
            pass
    if hasattr(os, "sysconf"):
        try:
            page_size = os.sysconf("SC_PAGE_SIZE")
            physical_pages = os.sysconf("SC_PHYS_PAGES")
            available_pages = os.sysconf("SC_AVPHYS_PAGES")
            return {
                "totalBytes": int(page_size * physical_pages),
                "availableBytes": int(page_size * available_pages),
            }
        except (OSError, ValueError):
            pass
    return {"totalBytes": None, "availableBytes": None}


def _disk_report(root: str | os.PathLike[str] | None) -> dict[str, Any]:
    usage = shutil.disk_usage(root or Path.cwd())
    return {
        "path": str(Path(root or Path.cwd()).resolve()),
        "totalBytes": usage.total,
        "usedBytes": usage.used,
        "freeBytes": usage.free,
    }


def _java_version() -> str | None:
    try:
        completed = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return None
    output = completed.stderr.splitlines() or completed.stdout.splitlines()
    return output[0] if output else None


def _pytorch_version() -> str | None:
    try:
        import torch
    except ImportError:
        return None
    return torch.__version__


def _git_commit(root: str | os.PathLike[str] | None) -> str:
    command = ["git", "rev-parse", "HEAD"]
    if root is not None:
        command = ["git", "-C", str(root), "rev-parse", "HEAD"]
    try:
        completed = subprocess.run(command, capture_output=True, text=True, timeout=5)
    except (OSError, subprocess.SubprocessError):
        return "unknown"
    return completed.stdout.strip() if completed.returncode == 0 and completed.stdout.strip() else "unknown"


def _directory_size(path: Path) -> int:
    if not path.exists():
        return 0
    return sum(entry.stat().st_size for entry in path.rglob("*") if entry.is_file())


def _ratio(numerator: int, denominator: int) -> float | None:
    if denominator == 0:
        return None
    return numerator / denominator


def _latency_distribution(values: Sequence[int]) -> dict[str, Any]:
    if not values:
        return {
            "count": 0,
            "min": 0,
            "mean": 0,
            "median": 0,
            "standardDeviation": 0,
            "confidence95": {"low": 0, "high": 0, "margin": 0},
            "p50": 0,
            "p95": 0,
            "p99": 0,
            "max": 0,
        }
    mean = statistics.mean(values)
    standard_deviation = statistics.stdev(values) if len(values) > 1 else 0
    margin = 1.96 * standard_deviation / (len(values) ** 0.5) if len(values) > 1 else 0
    return {
        "count": len(values),
        "min": min(values),
        "mean": mean,
        "median": statistics.median(values),
        "standardDeviation": standard_deviation,
        "confidence95": {"low": mean - margin, "high": mean + margin, "margin": margin},
        "p50": _percentile(values, 50),
        "p95": _percentile(values, 95),
        "p99": _percentile(values, 99),
        "max": max(values),
    }


def _percentile(values: Sequence[int], value: int) -> int:
    ordered = sorted(values)
    return ordered[min(len(ordered) - 1, max(0, int(value / 100 * len(ordered) + .999) - 1))]
