"""Local evidence for the Python loading and store-adapter contributor references."""
import importlib
import json
from pathlib import Path
import struct
import sys
from types import SimpleNamespace

import pytest


ROOT = Path(__file__).resolve().parents[2]


@pytest.fixture
def low(monkeypatch):
    monkeypatch.syspath_prepend(str(ROOT / "clients/python"))
    return importlib.import_module("aether_training_cache")


def test_reference_loader_omits_misses_but_counts_requested_keys_and_accumulates(low, monkeypatch):
    dataset = importlib.import_module("aether_training_cache.dataset")
    cache = SimpleNamespace(get_many_refs=lambda keys: {key: key for key in keys if key != "missing"})
    registry = SimpleNamespace(view=lambda ref: "view:" + ref)
    loader = dataset.AetherDataLoader(cache, ["b", "missing", "b"], registry, batch_size=2)
    ticks = iter([0, 1, 11, 12, 22, 30, 100, 101, 111, 112, 122, 140])
    monkeypatch.setattr(dataset.time, "perf_counter_ns", lambda: next(ticks))
    assert list(loader) == [["view:b"], ["view:b"]]
    assert loader.metrics()["samples"] == 3
    assert loader.total_nanos == 30
    assert list(loader) == [["view:b"], ["view:b"]]
    assert loader.total_nanos == 40
    assert loader.metrics()["samples"] == 6
    assert loader.metrics()["batches"] == 4
    assert loader.metrics()["inputWaitPercent"] == 100
    assert dataset.AetherBatchLoader is dataset.AetherDataLoader
    assert (loader.cache, loader.mapped_segments) == (cache, registry)


def test_reference_loader_validation_and_empty_metrics_are_not_framework_guards(low):
    dataset = importlib.import_module("aether_training_cache.dataset")
    loader = dataset.AetherDataLoader(None, [], None, batch_size=0)
    with pytest.raises(ValueError):
        list(iter(loader))
    with pytest.raises(ZeroDivisionError):
        len(loader)
    assert loader.metrics()["batchLatencyP95Nanos"] == 0
    for settings in ({"workers": -1}, {"prefetch_batches": -1}):
        with pytest.raises(ValueError, match="non-negative"):
            dataset.AetherDataLoader(None, [], None, **settings)


def test_reference_loader_pinning_is_an_explicit_conversion_path(low, monkeypatch):
    dataset = importlib.import_module("aether_training_cache.dataset")
    tensor = importlib.import_module("aether_training_cache.tensor")
    calls = []
    monkeypatch.setattr(tensor, "torch_tensor", lambda view, dtype, shape: calls.append((view, dtype, shape)) or "cpu")
    monkeypatch.setattr(tensor, "pinned_tensor", lambda value: ("pinned-copy", value))
    cache = SimpleNamespace(get_many_refs=lambda keys: {key: "ref" for key in keys})
    registry = SimpleNamespace(view=lambda ref: "borrowed-view")
    loader = dataset.AetherDataLoader(cache, ["one"], registry, pin_memory=True, dtype="float32", shape=(2,))
    assert list(loader) == [[("pinned-copy", "cpu")]]
    assert calls == [("borrowed-view", "float32", (2,))]


@pytest.fixture
def adapter(low, monkeypatch):
    module = importlib.import_module("aether_training_cache.java_store")
    packed_type = importlib.import_module("aether_training_cache.batch_values").InlineValueBatch

    class Client:
        def __init__(self, **settings):
            self.settings = settings
            self.read_batches = []
            self.presence_batches = []
            self.writes = []
            self.packed = []
            self.closed = False
            self.connection = object()

        def get_many_values(self, keys):
            self.read_batches.append(keys)
            payload = b"".join(b"value" for key in keys)
            batch = packed_type(memoryview(payload), [index * 5 for index in range(len(keys))],
                                [5] * len(keys), ["MISS" if key.sample_id == "missing" else "HIT_INLINE" for key in keys])
            self.packed.append(batch)
            return batch

        def contains_many(self, keys):
            self.presence_batches.append(keys)
            return set(keys)

        def put_many(self, values):
            self.writes.append(list(values))

        def close(self):
            self.closed = True

    monkeypatch.setattr(module, "AetherTrainingCache", Client)
    return module.JavaArtifactStore(port=9999, server_trace=True)


def test_java_adapter_retains_child_views_after_parent_close_and_copies_bytes(adapter):
    views = adapter.load_cached_views_many(["one", "missing", "one"])
    assert list(views) == ["one"]
    assert views["one"].readonly
    assert views["one"].tobytes() == b"value"
    with pytest.raises(ValueError, match="released"):
        adapter.client.packed[0].buffer.tobytes()
    copied = adapter.load_cached_bytes_many(["one"])
    assert type(copied["one"]) is bytes
    assert copied["one"] == b"value"
    assert adapter.operation_metrics()["lookup"]["count"] == 2
    views["one"].release()


def test_java_adapter_has_separate_payload_and_presence_chunk_limits(adapter):
    assert len(adapter.load_cached_bytes_many([str(index) for index in range(65)])) == 65
    assert list(map(len, adapter.client.read_batches)) == [64, 1]
    assert len(adapter.cached_artifact_ids([str(index) for index in range(4100)])) == 4100
    assert list(map(len, adapter.client.presence_batches)) == [4096, 4]
    assert set(adapter.operation_observations()) == {"lookup"}


def test_java_adapter_metadata_is_ignored_and_receipts_are_size_only(adapter):
    entries = [{"cache_key": str(index), "data": b"payload"} for index in range(65)]
    receipts = adapter.commit_bytes_many(entries, source_digest="ignored", experiment="ignored")
    assert list(map(len, adapter.client.writes)) == [64, 1]
    assert [vars(receipt) for receipt in receipts] == [{"size": 7}] * 65
    assert all(key.namespace == "tpds" for batch in adapter.client.writes for key, _ in batch)
    assert adapter.operation_metrics()["publish"]["count"] == 1


def test_java_adapter_failure_does_not_roll_back_earlier_chunks(adapter):
    class Oversized:
        def __len__(self):
            return 60 * 1024 * 1024 + 1

    entries = [{"cache_key": str(index), "data": b"ok"} for index in range(65)]
    entries.append({"cache_key": "too-large", "data": Oversized()})
    with pytest.raises(ValueError, match="frame budget"):
        adapter.commit_bytes_many(entries)
    assert list(map(len, adapter.client.writes)) == [64]
    assert adapter.operation_observations() == {}


def test_java_adapter_reset_preserves_connection_and_traces_and_copies_observations(adapter):
    adapter._metrics = {"lookup": [1, 2]}
    observations = adapter.operation_observations()
    observations["lookup"].append(3)
    assert adapter.operation_observations() == {"lookup": [1, 2]}
    assert adapter.operation_metrics({"test": list(range(20)), "empty": []}) == {
        "test": {"count": 20, "mean": 9.5, "p95": 19, "max": 19, "unit": "ms"}}
    connection = adapter.client.connection
    adapter.request_traces.append({"trace": 1})
    adapter.client.requests_sent = 9
    adapter.reset_operation_metrics()
    assert adapter.client.connection is connection
    assert not adapter.client.closed
    assert adapter.request_traces == [{"trace": 1}]
    assert (adapter.client.requests_sent, adapter.client.connections_opened, adapter.client.operation_counts) == (0, 0, {})
    assert adapter.operation_observations() == {}
    adapter.close()
    assert adapter.client.closed


def test_mmap_get_returns_framed_copied_bytes_and_close_is_reversible(low, tmp_path):
    store_type = importlib.import_module("aether_training_cache.persistent_mmap").PersistentMmapStore
    store = store_type(tmp_path)
    try:
        assert store.put("one", b"abc") == (0, 7)
        journal = store.journal_path.read_bytes()
        assert store.put("one", b"abc") == (0, 7)
        assert store.journal_path.read_bytes() == journal
        assert store.metrics["bytesAppended"] == 7
        value = store.get("one")
        assert type(value) is bytes
        assert value == struct.pack("<I", 3) + b"abc"
        store.close()
        assert value == store.get("one")
        assert store.metrics["remaps"] == 2
    finally:
        store.close()


@pytest.mark.parametrize("publish_before_failure", [False, True])
def test_mmap_failed_publish_rolls_back_memory_but_reopen_decides_outcome(low, tmp_path, monkeypatch, publish_before_failure):
    store_type = importlib.import_module("aether_training_cache.persistent_mmap").PersistentMmapStore
    store = store_type(tmp_path)
    save = store._save_index

    def fail(published):
        if publish_before_failure:
            save(published)
        raise OSError("injected publication failure")

    monkeypatch.setattr(store, "_save_index", fail)
    try:
        with pytest.raises(OSError, match="injected"):
            store.put("one", b"abc")
        assert store.lookup("one") is None
        assert store.data_path.stat().st_size == 7
        assert store.metrics["entriesAppended"] == 0
    finally:
        store.close()
    reopened = store_type(tmp_path)
    try:
        assert reopened.contains("one") is publish_before_failure
        if publish_before_failure:
            assert reopened.get("one") == struct.pack("<I", 3) + b"abc"
    finally:
        reopened.close()


def test_mmap_legacy_entry_reads_without_hash_but_requires_fresh_publication(low, tmp_path):
    store_type = importlib.import_module("aether_training_cache.persistent_mmap").PersistentMmapStore
    (tmp_path / "data.bin").write_bytes(struct.pack("<I", 3) + b"abc")
    (tmp_path / "index.json").write_text(json.dumps({"one": {"offset": 0, "size": 7}}))
    store = store_type(tmp_path)
    try:
        assert store.get("one").endswith(b"abc")
        with pytest.raises(ValueError, match="legacy"):
            store.put("one", b"abc")
    finally:
        store.close()


@pytest.mark.parametrize("io_readable", [False, True])
def test_resource_snapshot_parses_complex_comm_and_optional_io(low, monkeypatch, io_readable):
    resources = importlib.import_module("aether_training_cache.resources")
    fields = ["0"] * 13
    for index, value in {7: "11", 9: "2", 11: "150", 12: "50"}.items():
        fields[index] = value
    files = {
        "/proc/123/stat": "123 (comm with ) parentheses) " + " ".join(fields),
        "/proc/123/status": "VmRSS: 4 kB\nVmHWM: 8 kB\n",
        "/proc/123/io": "read_bytes: 30\nwrite_bytes: 40\n",
    }

    class ProcPath:
        def __init__(self, path):
            self.path = path

        def __truediv__(self, name):
            return ProcPath(self.path + "/" + name)

        def read_text(self):
            if self.path.endswith("/io") and not io_readable:
                raise PermissionError("restricted process io")
            return files[self.path]

    monkeypatch.setattr(resources, "Path", ProcPath)
    monkeypatch.setattr(resources, "os", SimpleNamespace(getpid=lambda: 123, sysconf=lambda name: 100))
    result = resources.snapshot()
    assert result["available"]
    assert (result["cpuSeconds"], result["minorFaults"], result["majorFaults"]) == (2, 11, 2)
    assert (result["rssBytes"], result["lifetimePeakRssBytes"]) == (4096, 8192)
    assert ("diskReadBytes" in result) is io_readable
    if io_readable:
        assert (result["diskReadBytes"], result["diskWriteBytes"]) == (30, 40)


def test_resource_delta_missing_counters_changed_pid_and_negative_clamping(low):
    resources = importlib.import_module("aether_training_cache.resources")
    before = {"pid": 1, "available": True, "cpuSeconds": 3, "minorFaults": 2}
    after = {"pid": 1, "available": True, "cpuSeconds": 2, "minorFaults": 5, "rssBytes": 99, "lifetimePeakRssBytes": 100}
    result = resources.delta(before, after)
    assert (result["cpuSeconds"], result["minorFaults"], result["diskReadBytes"]) == (0, 3, None)
    after["pid"] = 2
    result = resources.delta(before, after)
    assert not result["available"]
    assert all(result[field] is None for field in resources.COUNTERS)
    assert (result["rssBytesAtEnd"], result["lifetimePeakRssBytes"]) == (99, 100)


def test_worker_dataset_defers_and_owns_both_adapters_without_parent_constructor(low, monkeypatch, tmp_path):
    workers = importlib.import_module("aether_training_cache.loader_workers")
    java = importlib.import_module("aether_training_cache.java_store")
    mmap = importlib.import_module("aether_training_cache.persistent_mmap")
    owners, registrations = [], []

    class Context:
        def __init__(self):
            pytest.fail("full benchmark constructor must not run")

    class Owner:
        def __init__(self, *args, **settings):
            self.args, self.settings = args, settings
            owners.append(self)

        def close(self):
            pass

    monkeypatch.setitem(sys.modules, "benchmark_gpu_segmentation", SimpleNamespace(BackendContext=Context))
    monkeypatch.setattr(java, "JavaArtifactStore", Owner)
    monkeypatch.setattr(mmap, "PersistentMmapStore", Owner)
    monkeypatch.setattr(workers.atexit, "register", registrations.append)
    parent = SimpleNamespace(args=SimpleNamespace(aether_port=9999, aether_namespace="test", cache_durability="durable"),
                             sources=["source"], mmap_root=tmp_path, ram_ready=["ram"], store=object())
    dataset = workers.PreparedBatchDataset(parent, "RAM_READY", [([0], 0)])
    assert dataset.context is None and not owners
    assert dataset.args is not parent.args
    assert dataset.sources is parent.sources
    assert dataset.ram_ready is parent.ram_ready
    dataset._open()
    assert len(owners) == 2
    assert owners[1].settings == {"shared": True, "durable": True}
    assert dataset.context.store is owners[0] and dataset.context.store is not parent.store
    assert registrations == [owners[0].close, owners[1].close]
    assert workers.identity_collate(dataset) is dataset
    assert len(dataset) == 1


def test_worker_routing_groups_epochs_and_aggregates_transport_not_synthetic_connections(low, monkeypatch):
    workers = importlib.import_module("aether_training_cache.loader_workers")
    loaders = []

    class Loader:
        def __init__(self, dataset, **settings):
            self.dataset = dataset
            loaders.append((dataset, settings))

        def __iter__(self):
            for indices, epoch in self.dataset.schedule:
                usage = {"pid": 123, "available": True, "rssBytesAtEnd": 10 + epoch,
                         "lifetimePeakRssBytes": 20 + epoch, **{key: 1 for key in workers.COUNTERS}}
                yield (indices, epoch, ("prepared", {}, 1), {"connectionsOpened": 999, "hits": 2},
                       {}, {}, usage, {"connectionsOpened": 1, "requestsSent": 2, "operationCounts": {"GET": 2}},
                       {"lookup": [1]})

    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace(utils=SimpleNamespace(data=SimpleNamespace(DataLoader=Loader))))
    context = SimpleNamespace(args=SimpleNamespace(workers=2, prefetch_batches=0), sources=[],
                              mmap_root="unused", ram_ready=[], protocol={"connectionsOpened": 0, "hits": 0})
    schedule = [([0], 0), ([1], 0), ([2], 1)]
    results = list(workers.worker_batches(context, "AETHER_CACHE", schedule))
    assert [(indices, epoch) for indices, epoch, _, _ in results] == schedule
    assert [len(dataset) for dataset, _ in loaders] == [2, 1]
    for _, settings in loaders:
        assert settings["multiprocessing_context"] == "spawn"
        assert settings["batch_size"] is None
        assert settings["prefetch_factor"] == 1
        assert settings["persistent_workers"] is False
    assert context.protocol == {"connectionsOpened": 0, "hits": 6}
    assert context.worker_aether_transport == {"connectionsOpened": 3, "requestsSent": 6, "operationCounts": {"GET": 6}}
    assert context.worker_aether_observations == {"lookup": [1, 1, 1]}
    assert context.worker_resources["123"]["cpuSeconds"] == 3
    assert context.worker_resources["123"]["rssBytesAtEnd"] == 11
    assert context.worker_resources["123"]["lifetimePeakRssBytes"] == 21
