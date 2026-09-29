import os
from pathlib import Path

import pytest

from aether_training_cache.client import CacheKey, TransformationFingerprint
from bulk_population import BulkPipeWriter, BulkPublicationClient
from profile_population import base, cases, layout_cases, run_case, workload_args


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
@pytest.mark.parametrize("point", ["bulk.after_table", "bulk.after_table_force", "bulk.after_table_rename",
    "bulk.before_verification", "bulk.after_verification", "bulk.before_manifest",
    "bulk.manifest.after_append", "bulk.manifest.after_force", "bulk.after_manifest", "abort"])
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
            committed = point in {"bulk.after_manifest", "bulk.manifest.after_force", "bulk.manifest.after_append"}
            assert client.engine_info()["cacheEntries"] == (5 if committed else 0)
            actual = client.get_many(keys)
            assert actual == (dict(values) if committed else {})
    if point == "bulk.after_manifest":
        with pytest.raises(RuntimeError, match="before acknowledgement"):
            with BulkPipeWriter(store):
                pytest.fail("non-empty store must reject bulk writer")


def test_layout_sweep_changes_only_table_target():
    rows = layout_cases()
    assert [r["targetSstableBytes"] for r in rows] == [s * 1024 ** 2 for s in (32, 64, 128)]
    assert all(r["putBatch"] == 16 and not r["trace"] for r in rows)


def test_layout_reporting_handles_partial_and_complete_blocks(tmp_path):
    from profile_population import plot, summarize
    reports = []
    for i, case in enumerate(layout_cases()):
        report = dict(case=case, blockIndex=0, timingsMs=dict(population=100, startup=1, quiescence=0, close=1),
            totalMs=102, sourceLoadAndPreprocessMs=90, publicationRequests=[],
            bulkCommit=dict(storage=dict(timingsNs={}, tables=8 // (2 ** i))),
            populationMemory=dict(combinedPeakRssBytes=None),
            layoutRegression=dict(warm=dict(samplesPerSecond=100 + i), incremental=dict(admissionMs=10, drainMs=1)))
        reports.append(report)
        summary = summarize(reports)
        assert len(summary["cases"]) == i + 1
    assert summary["cases"]["aether-bulk-128mib"]["pairedWarmRatioVs32"] == [1.02]
    plot(reports, tmp_path)
    assert (tmp_path / "population.png").stat().st_size > 1000


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="requires real Java runtime")
@pytest.mark.parametrize("target", [32, 64, 128])
def test_layout_warm_reads_and_incremental_writes(tmp_path, target):
    from test_monai_comparison import fixture_data
    fixture_data(tmp_path / "inputs")
    manifest = tmp_path / "inputs/v2.csv"
    def reference(count):
        args = workload_args(manifest, count, 256)
        return base.tensor_digest(base.CanonicalTransform(args)(s) for s in base.workload.load_sources(args))
    case = next(c for c in layout_cases() if c["targetSstableBytes"] == target * 1024 ** 2)
    report = run_case(dict(case=case, store=str(tmp_path / "bulk"), manifest=str(manifest),
        samples=3, imageSize=256, referenceHash=reference(3), layoutRegression=True,
        updateManifest=str(manifest), updateSamples=5, updateReferenceHash=reference(5)))
    assert report["bulkCommit"]["storage"]["targetSstableBytes"] == target * 1024 ** 2
    assert report["layoutRegression"]["incremental"]["preprocessCalls"] == 2
    assert report["layoutRegression"]["warm"]["samples"] == 15
    assert report["preprocessCalls"] == 3
    assert report["bulkCommit"]["storage"]["manifest"]["forceNs"] > 0
