"""Arithmetic fixtures for documentation, not confirmatory research evidence."""
import math
from types import SimpleNamespace

import numpy as np
import pytest

import benchmark_gpu_segmentation as workload


def backend(epoch=100, populate=0, throughput=10):
    return dict(lifecycle=dict(populateMs=populate, trainingMs=epoch * 2,
        totalTrainingWallMs=epoch * 2, totalMs=populate + epoch * 2),
        steadyState=dict(meanEpochMs=epoch, samplesPerSecond=throughput,
            effectiveSamplesPerSecond=throughput, stepsPerSecond=1, pixelsPerSecond=160,
            effectiveMegapixelsPerSecond=.00016, inputWaitPercent=10), timing=dict(preprocessMs=2),
        steps=[{"representative": True}], gpu={}, process={})


def test_distribution_empty_singleton_halfwidth_and_discrete_percentiles():
    empty = workload.distribution([])
    assert empty["count"] == 0 and empty["mean"] == empty["confidence95"] == 0
    assert workload.distribution([3])["confidence95"] == 0
    values = workload.distribution([1, 3])
    assert values["median"] == 2 and values["p50"] == 1 and values["p95"] == 3
    assert values["standardDeviation"] == pytest.approx(math.sqrt(2))
    assert values["confidence95"] == pytest.approx(1.96)
    assert workload.percentile([1, 3], -10) == 1 and workload.percentile([1, 3], 200) == 3
    with pytest.raises(IndexError):
        workload.percentile([], 50)
    assert workload.mean([]) == 0


def test_projected_crossover_grid_strict_margin_and_continuous_root():
    results = {"AETHER_CACHE": backend(epoch=90, populate=45), "RAW_RECOMPUTE": backend()}
    # At N=5 both sides equal 495; strict inequality waits for next grid value.
    assert workload.training_break_even_epoch(results)["epoch"] == 10
    model = workload.admission_model(results)
    assert model["onePercentMarginPredictedBreakEvenEpoch"] == 5
    assert model["observedSampledCrossoverEpoch"] == 10 and model["predictionErrorEpochs"] == 5
    assert workload.predicted_break_even_epoch(100, 100, 0, 0) is None
    assert workload.predicted_break_even_epoch(100, 90, 0, 0) == 0
    assert workload.training_break_even_epoch({}) is None and workload.admission_model({}) is None


def test_ratio_direction_and_outcome_ties():
    raw, aether = backend(), backend()
    results = {"RAW_RECOMPUTE": raw, "AETHER_CACHE": aether}
    assert workload.training_outcome(results)["status"] == "NO_AETHER_TRAINING_SIGNAL"
    aether["steadyState"]["samplesPerSecond"] = 20
    assert workload.compare_pair(aether, raw)["aetherSamplesPerSecondRatio"] == 2
    assert workload.training_outcome(results)["status"] == "TRAINING_THROUGHPUT_SIGNAL"
    aether["lifecycle"]["totalMs"] = 100
    assert workload.training_outcome(results)["status"] == "TRAINING_THROUGHPUT_AND_TOTAL_WALL_IMPROVED"
    assert workload.compare_pair(None, raw) == {}
    assert workload.ratio(1, 0) is None and workload.ratio(2, 4) == .5
    assert workload.outcome_interpretation("unknown") == "Unknown outcome."


def test_expected_counts_track_truncated_schedule_and_initial_membership():
    args = workload.parse_args(["--samples", "5", "--batch-size", "2", "--measured-steps", "2"])
    expected = workload.expected_cache_counts(args, {0})
    assert expected == dict(lookups=4, misses=3, hits=1)
    protocol = dict(cacheHits=1, cacheMisses=3, prepopulatedEntries=1,
        initialPresentIndices=[0], entriesPerWriteBatch=[1, 2], bytesPublished=30, publishNanos=2_000_000)
    report = workload.cache_dynamics(protocol, args)
    assert report["hitRatio"] == .25 and report["publishCostMs"] == 2
    assert report["invariants"]["passed"]
    report["publishedSamples"] = 2
    assert not workload.cache_invariants(report, args)["passed"]


def test_checksums_omit_shapes_and_preserve_batch_order_within_epoch():
    array = np.arange(4, dtype=np.float32)
    first = workload.batch_checksums(dict(images=array, masks=array))
    shaped = workload.batch_checksums(dict(images=array.reshape(2, 2), masks=array.reshape(2, 2)))
    assert first == shaped
    second = workload.batch_checksums(dict(images=array + 1, masks=array))
    steps = [dict(epoch=0, checksums=first), dict(epoch=0, checksums=second)]
    assert workload.epoch_checksums(steps) != workload.epoch_checksums(list(reversed(steps)))
    assert workload.cumulative_lifecycle_by_epoch([10, 20], 5) == [dict(epoch=1, totalMs=15), dict(epoch=2, totalMs=35)]
    assert workload.cumulative_lifecycle_by_epoch([], 5) == []


def test_summary_uses_step_and_backend_denominators_separately():
    fields = ["inputWaitMs", "batchPrepareMs", "sourceLoadMs", "preprocessMs", "aetherLookupMs",
        "aetherPublishMs", "mmapReadMs", "tensorBuildMs", "artifactDecodeMs", "randomAugmentationMs",
        "prefetchWaitMs", "hostToDeviceMs", "forwardMs", "lossMs", "backwardMs", "optimizerMs"]
    step = dict.fromkeys(fields, 0.)
    step.update(batchSize=2, stepWallMs=10., inputWaitMs=20., epoch=0,
                checksums={"combinedChecksum": "a"}, tensorLayout={})
    context = SimpleNamespace(args=SimpleNamespace(batch_size=2, resize=4), peak_memory_bytes=lambda: 0,
        populate_ms=lambda backend: 5., checksums={"combinedChecksum": "reference"})
    gpu = dict(samples=[], status="UNAVAILABLE", source=None, error="disabled", intervalMs=0)
    report = workload.summarize_backend("fixture", [step], [30.], 40., context, gpu, {},
                                       device=SimpleNamespace(type="cpu"))
    assert report["steadyState"]["samplesPerSecond"] == 200
    assert report["steadyState"]["effectiveSamplesPerSecond"] == 50
    assert report["steadyState"]["inputWaitPercent"] == 200
    assert report["lifecycle"]["totalMs"] == 45
    assert report["lifecycle"]["cumulativeByEpochMs"][-1]["totalMs"] == 35
    assert report["gpu"]["utilizationMean"] is None
    assert workload.summarize_tensor_layout([]) == {}


def test_backend_aggregation_keeps_representative_fields_and_zeros_missing_timings():
    first, second = backend(), backend(epoch=200)
    first["lifecycle"].update(cumulativeByEpochMs=[dict(epoch=1, totalMs=100)], backgroundDrainMs=5)
    first["timing"]["onlyFirst"] = 8
    second["steps"] = [{"representative": False}]
    report = workload.aggregate_backend_runs("not-validated", [first, second])
    assert report["steps"] == first["steps"]
    assert report["lifecycle"]["trainingMs"]["mean"] == 300
    assert "cumulativeByEpochMs" not in report["lifecycle"] and "backgroundDrainMs" not in report["lifecycle"]
    assert report["timing"]["onlyFirst"]["mean"] == 4
    report["steps"][0]["representative"] = "changed"
    assert first["steps"][0]["representative"] is True


def test_operation_summary_means_are_not_count_weighted():
    report = workload.aggregate_operation_metrics([
        {"get": dict(count=1, mean=100, p95=100, max=100)},
        {"get": dict(count=99, mean=0, p95=0, max=1)},
        {"get": dict(count=0, mean=900, p95=900, max=900)}])
    assert report["get"]["count"] == 100 and report["get"]["mean"]["mean"] == 50
    assert report["get"]["max"] == 100


def test_protocol_aggregation_drops_transport_and_propagates_unknown_wal():
    protocols = [dict(cacheHits=2, entriesPerWriteBatch=[1], walForceCount=1, transport={"requestsSent": 9}),
                 dict(cacheHits=3, entriesPerWriteBatch=[2])]
    report = workload.aggregate_protocol(protocols)
    assert report["cacheHits"] == 5 and report["entriesPerWriteBatch"] == [1, 2]
    assert report["walForceCount"] is None and "transport" not in report
    assert workload.aggregate_protocol([])["walForceCount"] == 0


def test_conditional_crossover_and_missing_flags_have_different_denominators():
    values = [None, dict(epoch=5)]
    report = workload.aggregate_break_even(values)
    assert report["epoch"]["count"] == 1 and report["rawRuns"] is values
    assert workload.aggregate_break_even([None]) is None
    outcomes = [dict(status="SIGNAL", flags={"improved": True}), {}]
    assert workload.aggregate_outcomes(outcomes)["flagPassRates"]["improved"] == .5
    comparisons = workload.aggregate_comparisons([{ "primary": {"ratio": 2}}, {}])
    assert comparisons["primary"]["ratio"]["count"] == 1


def test_other_aggregates_omit_missing_fields_and_keep_raw_models():
    model = dict(onePercentMarginPredictedBreakEvenEpoch=3, equation="fixture")
    report = workload.aggregate_admission_models([None, model])
    assert report["runs"] == 1 and report["margin"] == .01
    assert report["zeroMarginPredictedBreakEvenEpoch"]["count"] == 0
    assert report["rawRuns"][0] is model
    assert workload.aggregate_admission_models([]) is None
    cache = workload.aggregate_cache_dynamics([dict(hits=1), {}])
    assert cache["hits"]["count"] == 1 and cache["misses"]["count"] == 0
    assert "invariants" not in cache
    assert workload.aggregate_runs([])["runs"] == 0


def test_elapsed_time_uses_supplied_clock_without_clamping(monkeypatch):
    monkeypatch.setattr(workload, "time", SimpleNamespace(perf_counter=lambda: 2.))
    assert workload.elapsed_ms(1.5) == 500 and workload.elapsed_ms(3.) == -1000
