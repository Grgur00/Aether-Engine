"""Disposable local stores and injected Java clients for documentation contracts."""
from types import SimpleNamespace

import numpy as np
import pytest

import benchmark_gpu_segmentation as workload


def args_for(root, *extra):
    return workload.parse_args(["--samples", "3", "--batch-size", "2", "--height", "4",
        "--width", "4", "--resize", "4", "--epochs", "2", "--aether-cache-dir",
        str(root / "aether"), "--mmap-cache-dir", str(root / "mmap"), *extra])


@pytest.fixture
def contexts(tmp_path):
    opened = []
    def create(*extra):
        args = args_for(tmp_path, *extra)
        sources = workload.load_sources(args)
        reference = [workload.artifact_to_tensor_sample(workload.preprocess_sample(source, args, np), np)
                     for source in sources]
        context = workload.BackendContext(args, reference, sources, np)
        opened.append(context)
        return context
    yield create
    for context in opened:
        context.close()


def assert_batch_equal(left, right):
    for key in ("images", "masks"):
        np.testing.assert_array_equal(left[key], right[key])


def test_real_stores_cold_then_warm_batches_match_raw_and_ram(contexts):
    context = contexts()
    assert context.initial_present_indices == context.initial_mmap_indices == set()
    assert workload.aether_cold_start_gate(context)["passed"]
    for indices, _ in workload.scheduled_batches(context.args, 4):
        raw, _ = context.batch("RAW_RECOMPUTE", indices)
        for backend in ("AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"):
            cached, _ = context.batch(backend, indices)
            assert_batch_equal(raw, cached)
    counters = context.protocol_counters()
    assert counters["cacheHits"] == counters["cacheMisses"] == 3
    assert counters["getManyRequests"] == 4 and counters["putManyRequests"] == 2
    assert counters["entriesPerWriteBatch"] == [2, 1]
    assert context.mmap_dynamics()["invariants"]["passed"]
    with pytest.raises(ValueError, match="unknown backend"):
        context.batch("invalid", [0])


def test_simulated_prefix_seed_is_separate_from_measured_counts(contexts):
    context = contexts("--initial-cache-hit-ratio", "33.3")
    assert context.initial_present_indices == context.initial_mmap_indices == {0}
    assert context._existing_cache_entries() == 1
    assert context.protocol_counters()["cacheHits"] == 0
    assert context.mmap_store.metrics["entriesAppended"] == 0
    assert workload.aether_cold_start_gate(context)["passed"]
    for indices, _ in workload.scheduled_batches(context.args, 4):
        context.batch("AETHER_CACHE", indices)
        context.batch("STATIC_PREPROCESSED_MMAP", indices)
    assert context.protocol_counters()["cacheMisses"] == 2
    assert context.mmap_dynamics()["invariants"]["passed"]


def test_external_reuse_discovers_actual_keys_and_preserves_directories(contexts):
    first = contexts()
    first.populate_aether_dataset()
    first.populate_mmap_dataset()
    first.close()
    second = contexts("--aether-cache-mode", "reuse", "--mmap-cache-mode", "reuse")
    assert second.initial_present_indices == second.initial_mmap_indices == {0, 1, 2}
    assert second._target_initial_hit_ratio() == 100
    assert second.populate_ms("AETHER_CACHE") == second.populate_ms("STATIC_PREPROCESSED_MMAP") == 0
    second.close()
    assert second.directory.exists() and second.mmap_root.exists()


def test_changed_deterministic_identity_keeps_old_records_but_misses_new_keys(contexts):
    first = contexts()
    first.populate_aether_dataset()
    first.populate_mmap_dataset()
    old_key = first.cache_key(0)
    first.close()
    second = contexts("--aether-cache-mode", "reuse", "--mmap-cache-mode", "reuse",
                      "--normalization-offset", "0.25")
    assert second.cache_key(0) != old_key
    assert second.initial_present_indices == second.initial_mmap_indices == set()
    report = second.populate_mmap_dataset()
    assert report["misses"] == report["entriesAppended"] == 3
    assert report["entriesStored"] == 6


def test_population_reports_deltas_but_aether_lookup_time_is_cumulative(contexts):
    context = contexts()
    context.protocol["lookupNanos"] = 10_000_000
    first = context.populate_aether_dataset()
    assert first["misses"] == first["entriesPublished"] == 3 and first["lookupMs"] >= 10
    second = context.populate_aether_dataset()
    assert second["misses"] == second["entriesPublished"] == second["bytesWritten"] == 0
    assert second["cacheHits"] == 3 and second["lookupMs"] >= first["lookupMs"]
    mmap = context.populate_mmap_dataset()
    assert mmap["entriesAppended"] == 3 and mmap["bytesAppended"] > 12
    assert context.mmap_protocol["lookups"] == 0
    assert not context.mmap_dynamics()["invariants"]["passed"]


def test_reset_external_python_reopens_without_deleting_cached_payloads(contexts):
    context = contexts()
    context.populate_aether_dataset()
    context.reset_aether_cache()
    assert context._existing_cache_indices() == {0, 1, 2}
    assert context.protocol["cacheHits"] == context.protocol["cacheMisses"] == 0
    assert context.populate_ms("missing-backend") == 0
    assert not workload.aether_cold_start_gate(context)["passed"]


def test_temporary_python_reset_and_close_remove_only_its_root(tmp_path):
    args = args_for(tmp_path)
    args.aether_cache_dir = args.mmap_cache_dir = None
    sources = workload.load_sources(args)
    reference = [workload.artifact_to_tensor_sample(workload.preprocess_sample(source, args, np), np)
                 for source in sources]
    context = workload.BackendContext(args, reference, sources, np)
    directory = context.directory
    try:
        context.populate_aether_dataset()
        context.reset_aether_cache()
        assert context._existing_cache_indices() == set()
        assert directory.exists()
    finally:
        context.close()
    assert not directory.exists()


def test_warm_backend_touches_only_ram_prefix():
    calls = []
    context = SimpleNamespace(args=SimpleNamespace(batch_size=2, samples=3),
                              batch=lambda backend, indices: calls.append((backend, indices)))
    for backend in workload.BACKENDS:
        workload.warm_backend(context, backend)
    assert calls == [("RAM_READY", [0, 1])]


@pytest.mark.parametrize("engine,durability,valid", [
    ("java-training-cache", "RECOVERABLE", True),
    ("different", "RECOVERABLE", False), ("java-training-cache", "DURABLE", False),
])
def test_java_open_checks_report_not_local_root(monkeypatch, engine, durability, valid):
    calls, closed = [], []
    store = SimpleNamespace(engine_info=lambda: dict(engine=engine, durability=durability),
                            close=lambda: closed.append(True))
    def construct(**kwargs):
        calls.append(kwargs)
        return store
    monkeypatch.setattr(workload, "JavaArtifactStore", construct)
    context = workload.BackendContext.__new__(workload.BackendContext)
    context.args = SimpleNamespace(aether_engine="java", aether_port=1234,
        aether_namespace="fixture", cache_durability="recoverable", server_trace=True)
    if valid:
        assert context._open_store("unused-local-root") is store and not closed
        assert context.engine_info["engine"] == engine
    else:
        with pytest.raises(ValueError, match="daemon does not match"):
            context._open_store("unused-local-root")
        assert closed == [True]
    assert calls == [dict(port=1234, namespace="fixture", server_trace=True)]


def test_java_reset_only_resets_metrics_and_protocol_lists():
    context = workload.BackendContext.__new__(workload.BackendContext)
    calls = []
    context.args = SimpleNamespace(aether_engine="java")
    context.store = SimpleNamespace(reset_operation_metrics=lambda: calls.append("metrics"))
    context.protocol = dict(cacheHits=4, entriesPerWriteBatch=[2], retained="custom")
    context.initial_present_indices = {0}
    context.reset_aether_cache()
    assert calls == ["metrics"] and context.initial_present_indices == {0}
    assert context.protocol["cacheHits"] == 0 and context.protocol["entriesPerWriteBatch"] == []
    assert context.protocol["retained"] == "custom" and context.protocol["connectionsOpened"] == 1


def test_cold_gate_checks_counts_not_exact_initial_index_set():
    context = SimpleNamespace(args=SimpleNamespace(samples=3), cache_key=str,
        store=SimpleNamespace(cached_artifact_ids=lambda keys: {"2"}),
        target_initial_cache_hit_ratio=33.3, prepopulated_aether_entries=1,
        initial_present_indices={0})
    assert workload.aether_cold_start_gate(context)["passed"]
    context.target_initial_cache_hit_ratio = 0
    assert not workload.aether_cold_start_gate(context)["passed"]


def test_protocol_report_shallow_lists_and_worker_transport_merge():
    context = workload.BackendContext.__new__(workload.BackendContext)
    context.args = SimpleNamespace(aether_engine="java")
    context.protocol = dict(entriesPerWriteBatch=[2], cacheHits=3, cacheMisses=1,
        bytesReturned=20, bytesPublished=10)
    context.store = SimpleNamespace(client=SimpleNamespace(protocol_metrics=lambda: dict(
        connectionsOpened=1, requestsSent=2, operationCounts={"GET_MANY": 2})))
    context.worker_aether_transport = dict(connectionsOpened=2, requestsSent=4,
                                        operationCounts={"GET_MANY": 3, "PUT_MANY": 1})
    result = context.protocol_counters()
    assert result["transport"] == dict(connectionsOpened=3, requestsSent=6,
                                       operationCounts={"GET_MANY": 5, "PUT_MANY": 1})
    assert result["walForceCount"] is None
    result["entriesPerWriteBatch"].append(9)
    assert context.protocol["entriesPerWriteBatch"] == [2, 9]


def test_java_operation_observation_merge_is_not_background_drain():
    context = workload.BackendContext.__new__(workload.BackendContext)
    context.args = SimpleNamespace(aether_engine="java")
    observations = {"lookup": [1]}
    context.store = SimpleNamespace(operation_observations=lambda: observations,
                                   operation_metrics=lambda merged: merged)
    context.worker_aether_observations = {"lookup": [2], "publish": [3]}
    assert context.aether_operation_metrics() == {"lookup": [1, 2], "publish": [3]}
    assert observations["lookup"] == [1, 2]


def test_close_failure_prevents_later_cleanup():
    context = workload.BackendContext.__new__(workload.BackendContext)
    calls = []
    def fail():
        calls.append("mmap")
        raise RuntimeError("close failed")
    context.mmap_store = SimpleNamespace(close=fail)
    context.args = SimpleNamespace(aether_engine="java")
    context.store = SimpleNamespace(close=lambda: calls.append("java"))
    with pytest.raises(RuntimeError, match="close failed"):
        context.close()
    assert calls == ["mmap"]
