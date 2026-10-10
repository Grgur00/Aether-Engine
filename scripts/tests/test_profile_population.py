"""Population diagnostics must preserve data, wire encoding, durability, and timing scope."""
import os
import struct

import pytest

from profile_population import PublicationClient, cases, expected_puts, run_case, workload_args, base, plot, summarize
from aether_training_cache.client import CacheKey, TransformationFingerprint, AetherTrainingCache


def test_sweep_is_population_only_with_fixed_lookup_and_native_controls():
    arms = cases()
    assert [arm["putBatch"] for arm in arms[:6]] == [1, 4, 8, 16, 32, 64]
    assert {arm["lookupBatch"] for arm in arms[:6]} == {64}
    assert [expected_puts(1200, 64, arm["putBatch"]) for arm in arms[:6]] == [1200, 300, 150, 75, 38, 19]
    assert {arm["backend"] for arm in arms} == set(base.BACKENDS)
    assert len(arms) == 11 and len({arm["name"] for arm in arms}) == 11


@pytest.mark.parametrize("size", [1, 4, 8, 16, 32, 64])
def test_publication_splitting_retains_production_wire_encoding(monkeypatch, size):
    bodies = []
    monkeypatch.setattr(AetherTrainingCache, "_round_trip", lambda self, body, **kw: bodies.append(body))
    values = [(CacheKey("test", str(i), TransformationFingerprint.from_descriptor("same")), bytes([i])) for i in range(65)]
    client = PublicationClient(put_batch=size, traced=False)
    client.put_many(values)
    assert len(bodies) == expected_puts(65, 65, size)
    assert sum(struct.unpack_from(">I", b, 2)[0] for b in bodies) == 65
    expected = []
    for start in range(0, len(values), size):
        AetherTrainingCache().put_many(values[start:start + size])
        expected.append(bodies[-1])
    assert bodies[:len(expected)] == expected
    assert all(p["bodyConstructionNs"] <= p["totalNs"] for p in client.publications)


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="opt-in real Java population sweep")
def test_all_population_cases_preserve_tensors_without_model_or_training(tmp_path, monkeypatch):
    from test_monai_comparison import fixture_data
    fixture_data(tmp_path / "inputs")
    manifest = tmp_path / "inputs/v2.csv"
    args = workload_args(manifest, 5, 16)
    sources = base.workload.load_sources(args)
    reference = base.tensor_digest(base.CanonicalTransform(args)(item) for item in sources)
    def forbidden(*a, **kw):
        raise AssertionError("population diagnostic must never create a model")
    monkeypatch.setattr(base.workload, "create_model", forbidden)
    reports = []
    for case in cases():
        request = dict(case=case, store=str(tmp_path / case["name"]), manifest=str(manifest),
                       samples=5, imageSize=16, referenceHash=reference)
        report = run_case(request)
        reports.append(report)
        assert report["preprocessCalls"] == report["uniqueArtifacts"] == 5
        assert report["trainingSampleRequests"] == 0 and report["model"] is None
        assert report["tensorSha256"] == reference
        assert report["totalMs"] == sum(report["timingsMs"].values())
        if case["trace"]:
            counts = report["traceSummary"]["serverWriteCounters"]
            assert counts["operations"] == 5
            assert counts["walForceCalls"] == expected_puts(5, case["lookupBatch"], case["putBatch"])
            assert report["quiescence"]["backgroundCompaction"]["debtBytes"] == 0
        with pytest.raises(FileExistsError, match="fresh empty cache"):
            run_case(request)
    plot(reports, tmp_path)
    assert (tmp_path / "population.png").stat().st_size > 1000
    assert len(summarize(reports)["cases"]) == 11
