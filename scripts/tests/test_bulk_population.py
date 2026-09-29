import os
from pathlib import Path

import pytest

from aether_training_cache.client import CacheKey, TransformationFingerprint
from bulk_population import BulkPipeWriter, BulkPublicationClient
from profile_population import base, cases, run_case, workload_args


def test_bulk_arm_is_opt_in_without_changing_original_cases():
    assert cases(True)[:-1] == cases()
    assert len(cases()) == 11 and len(cases(True)) == 12
    assert cases(True)[-1]["putBatch"] == 16


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="requires real Java runtime")
def test_bulk_population_reopens_with_ordinary_reader(tmp_path, monkeypatch):
    from test_monai_comparison import fixture_data
    fixture_data(tmp_path / "inputs")
    manifest = tmp_path / "inputs/v2.csv"
    args = workload_args(manifest, 5, 256)
    sources = base.workload.load_sources(args)
    reference = base.tensor_digest(base.CanonicalTransform(args)(item) for item in sources)
    def no_model(*a, **kw):
        raise AssertionError("bulk population must not create a model")
    monkeypatch.setattr(base.workload, "create_model", no_model)
    request = dict(case=cases(True)[-1], store=str(tmp_path / "bulk"), manifest=str(manifest),
                   samples=5, imageSize=256, referenceHash=reference)
    report = run_case(request)
    assert report["restartValidation"]["passed"]
    assert report["bulkCommit"]["storage"]["walPayloadBytes"] == 0
    assert report["bulkCommit"]["storage"]["memtableInsertions"] == 0
    assert report["preprocessCalls"] == report["uniqueArtifacts"] == report["bulkCommit"]["sha256Calls"] == 5
    assert report["model"] is None and report["trainingSampleRequests"] == 0
    assert report["totalMs"] == sum(report["timingsMs"].values())


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="requires real Java crash child")
@pytest.mark.parametrize("point", ["bulk.after_table", "bulk.before_manifest", "bulk.after_manifest", "abort"])
def test_process_death_or_abort_never_publishes_a_partial_import(tmp_path, point):
    store = tmp_path / "store"
    keys = [CacheKey("bulk-crash", str(i), TransformationFingerprint.from_descriptor("v1")) for i in range(5)]
    values = [(key, bytes([i]) * 128) for i, key in enumerate(keys)]
    with BulkPipeWriter(store, crash_point=None if point == "abort" else point) as writer:
        BulkPublicationClient(writer).put_many(values)
        if point != "abort":
            with pytest.raises(RuntimeError, match="before acknowledgement"):
                writer.finish()
    if point != "abort":
        assert writer.process.returncode == 73
    with base.java_daemon(store) as daemon:
        with base.AetherTrainingCache(port=daemon["port"]) as client:
            assert client.engine_info()["cacheEntries"] == (5 if point == "bulk.after_manifest" else 0)
            actual = client.get_many(keys)
            assert actual == (dict(values) if point == "bulk.after_manifest" else {})
    if point == "bulk.after_manifest":
        with pytest.raises(RuntimeError, match="before acknowledgement"):
            with BulkPipeWriter(store):
                pytest.fail("non-empty store must reject bulk writer")
