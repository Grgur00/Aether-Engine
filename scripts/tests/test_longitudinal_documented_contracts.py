"""Local workload/reference contracts, not GPU or confirmatory measurements."""
import math
from pathlib import Path
from types import SimpleNamespace

import pytest

import longitudinal_analyze as analysis
import longitudinal_comparison as runner
from longitudinal_state import BACKENDS, cumulative, save_receipt


def config():
    return dict(versions=[4, 5, 6, 7, 8], prefetchDepth=0, serverTrace=False,
                confirmatory=False, batchSize=16, epochs=1, pairedBlocks=1,
                seed=20260926, sourceSamples=8, imageSize=16)


def stage_report(stage, count=5, previous=4, epochs=1):
    phases = dict(startup=1., modelSetup=1., scanAdmission=1., training=1., drain=1., close=1.)
    return dict(preprocessCalls=count - previous, trainingCacheMisses=0,
                uniqueArtifacts=count, reusedSamples=previous,
                trainingSampleRequests=count * epochs if stage else 0,
                epochs=[dict(sampleRequests=count, losses=[0.5])] * epochs if stage else [],
                timingsMs=phases, fullLifecycleMs=sum(phases.values()))


def test_fixture_config_relaxes_dataset_but_not_common_invariants():
    value = config()
    runner.validate_config(value, fixture=True)
    with pytest.raises(ValueError, match="dataset configuration drift"):
        runner.validate_config(value)
    value["batchSize"] = 8
    with pytest.raises(ValueError, match="protocol"):
        runner.validate_config(value, fixture=True)


@pytest.mark.parametrize("lifecycle", ["restart-per-version", "persistent-per-block"])
def test_smoke_still_requires_one_block_and_epoch(lifecycle):
    value = dict(config(), serviceLifecycle=lifecycle)
    runner.validate_config(value, fixture=True, smoke=True)
    value["epochs"] = 2
    with pytest.raises(ValueError, match="smoke"):
        runner.validate_config(value, fixture=True, smoke=True)


def test_stage_validation_has_no_v0_training_or_loss_count_gate():
    value = config()
    report = stage_report(0, count=4, previous=0)
    runner.validate_stage(report, value, 0)
    report["trainingSampleRequests"] = 4
    with pytest.raises(ValueError, match="accounting"):
        runner.validate_stage(report, value, 0)
    update = stage_report(1)
    update["epochs"][0]["losses"] = []
    runner.validate_stage(update, value, 1)
    update["epochs"][0]["losses"] = [math.nan]
    with pytest.raises(ValueError, match="nonfinite"):
        runner.validate_stage(update, value, 1)


def block_fixture(times):
    results = {}
    for backend, costs in times.items():
        stages = [dict(timingsMs=dict(startup=cost, modelSetup=0., scanAdmission=0.,
                                    training=0., drain=0., close=0.), fullLifecycleMs=cost)
                  for cost in costs]
        results[backend] = dict(stages=stages, cumulativeMs=cumulative(stages))
    return dict(backendResults=results)


def test_first_break_even_counts_tie_even_when_later_advantage_is_lost():
    block = block_fixture({"aether": [10.] * 5, **{name: [10., 5., 20., 5., 5.] for name in BACKENDS[1:]}})
    summary = analysis.analyze([block], common_ms=100.)
    comparison = summary["comparisons"]["mmap"]
    assert comparison["firstObservedBreakEvenVersionByBlock"] == [0]
    assert comparison["firstObservedGeometricMeanBreakEvenVersion"] == 0
    assert comparison["cumulative"]["V4"]["geometricMeanRatio"] == pytest.approx(.9)
    assert comparison["includingCommonIdentification"]["V4"]["geometricMeanRatio"] == pytest.approx(145 / 150)
    assert comparison["perUpdate"]["V1"]["geometricMeanRatio"] == pytest.approx(.5)
    assert comparison["cumulative"]["V0"]["descriptiveCI95"] is None
    assert summary["measurementRole"] == "exploratory; no confirmatory claim"


@pytest.mark.parametrize("ratios", [[], [0], [-1], [math.nan], [math.inf]])
def test_ratio_summary_rejects_invalid_ratios(ratios):
    with pytest.raises(ValueError, match="invalid"):
        analysis.ratio_summary(ratios)


def test_ratio_summary_uses_geometric_not_arithmetic_mean():
    result = analysis.ratio_summary([.5, 2.])
    assert result["geometricMeanRatio"] == pytest.approx(1.)
    assert result["descriptiveCI95"][0] < 1 < result["descriptiveCI95"][1]
    assert result["pairedRatios"] == [.5, 2.]


def test_analysis_checks_cumulative_map_not_identity():
    block = block_fixture({name: [1.] * 5 for name in BACKENDS})
    block["backendResults"]["aether"]["cumulativeMs"]["V4"] = 99
    with pytest.raises(ValueError, match="saved cumulative"):
        analysis.analyze([block])


def test_plot_produces_local_pngs_and_closes_figures(tmp_path):
    import matplotlib.pyplot as plt
    from PIL import Image
    block = block_fixture({name: [1., 2., 3., 4., 5.] for name in BACKENDS})
    before = set(plt.get_fignums())
    analysis.plot([block, block], tmp_path, role="Disposable fixture")
    assert set(plt.get_fignums()) == before
    for name in ["cumulative.png", "phases.png"]:
        with Image.open(tmp_path / name) as image:
            assert image.width > 100 and image.height > 100
            assert any(low != high for low, high in image.convert("RGB").getextrema())


def test_fresh_and_completed_block_paths_have_different_reference_checks(tmp_path):
    cfg = config()
    meta = dict(protocolHash="protocol", sourceSha256={"file": "hash"}, environmentId="fixture",
                protocol=dict(sourceManifestSha256="archive", cpuFixture=True))
    reference = dict(referenceHashes=[f"tensor-{i}" for i in range(5)], transformIdentity={"transform": 1})
    calls = []

    def worker(backend, stage, live):
        calls.append((backend, stage))
        (live / "items").write_text(str(cfg["versions"][stage]))
        report = stage_report(stage, cfg["versions"][stage], cfg["versions"][stage - 1] if stage else 0)
        report.update(tensorSha256=f"tensor-{stage}", modelSha256=f"model-{stage}" if stage else None,
                      initialModelSha256=f"initial-{stage}" if stage else None,
                      sampleOrderSha256=f"order-{stage}", modelSeed=cfg["seed"] + stage,
                      transformIdentity={"transform": 1})
        return report

    output, scratch = tmp_path / "reports", tmp_path / "scratch"
    paths = [tmp_path / f"v{i}.csv" for i in range(5)]
    block = runner.run_block(0, output, scratch, meta, cfg, paths, reference, worker)
    assert len(calls) == 20
    assert len(block["backendOrderByStage"]) == 5
    changed_reference = dict(referenceHashes=["different"] * 5, transformIdentity={"different": 2})
    reused = runner.run_block(0, output, scratch, meta, cfg, paths, changed_reference, worker)
    assert reused == block and len(calls) == 20
    # A self-consistent changed cumulative map is not recomputed on this fast path.
    path = output / "blocks/00/paired.json"
    identity = {key: block[key] for key in ("protocolHash", "sourceHash", "sourceManifestSha256", "environmentId", "blockIndex")}
    block["backendResults"]["mmap"]["cumulativeMs"]["V4"] = 99
    save_receipt(path, block, identity)
    reused = runner.run_block(0, output, scratch, meta, cfg, paths, reference, worker)
    assert reused["backendResults"]["mmap"]["cumulativeMs"]["V4"] == 99
    with pytest.raises(ValueError, match="saved cumulative"):
        analysis.analyze([reused])


@pytest.fixture
def workload_modules():
    pytest.importorskip("monai")
    pytest.importorskip("lmdb")
    import monai_comparison as adapters
    import longitudinal_worker as worker
    return adapters, worker


def test_batch_dispatch_and_cleanup_are_attribute_based(workload_modules):
    adapters, _ = workload_modules
    assert adapters.batches(5, 2) == [[0, 1], [2, 3], [4]]
    assert adapters.batches(0, 16) == []
    with pytest.raises(ValueError):
        adapters.batches(5, 0)
    assert adapters.fetch(["a", "b"], [1, 0, 1]) == ["b", "a", "b"]
    with pytest.raises(TypeError):
        adapters.fetch(SimpleNamespace(get_batch=None), [0])
    calls = []
    adapters.close(SimpleNamespace(close=lambda: calls.append("closed")))
    adapters.close(object())
    assert calls == ["closed"]


def test_transform_failure_counts_and_captured_identity(workload_modules, monkeypatch):
    adapters, _ = workload_modules
    args = adapters.workload.parse_args(["--resize", "16"])
    transform = adapters.CanonicalTransform(args)
    first = transform.key(dict(source_identity="declared"))
    args.resize = 32
    assert transform.key(dict(source_identity="declared")) == first
    assert transform.identity["resize"] == 16
    assert adapters.CanonicalTransform(args).key(dict(source_identity="declared")) != first
    def fail(*args):
        raise RuntimeError("preprocessing failed")
    monkeypatch.setattr(adapters.workload, "preprocess_sample", fail)
    with pytest.raises(RuntimeError, match="preprocessing failed"):
        transform(dict(source_identity="declared"))
    assert transform.calls == 1


def test_disk_sampler_error_handling_and_repeated_stop(workload_modules, monkeypatch):
    adapters, worker = workload_modules
    outcomes = iter([FileNotFoundError(), OSError("fixture stat"),
                     dict(logicalBytes=20, allocatedBytes=None), dict(logicalBytes=30, allocatedBytes=64),
                     dict(logicalBytes=10, allocatedBytes=32)])
    def disk_usage(path):
        value = next(outcomes)
        if isinstance(value, BaseException):
            raise value
        return value
    monkeypatch.setattr(adapters, "disk_usage", disk_usage)
    sampler = worker.DiskSampler(Path("fixture"))
    sampler.sample()
    sampler.sample()
    assert sampler.errors == ["fixture stat"]
    sampler.start()
    first = sampler.stop()
    second = sampler.stop()
    assert first["sampleCount"] == 2 and second["sampleCount"] == 3
    assert first["peakAllocatedBytes"] == 64 and first["peakLogicalBytes"] == 30
    assert not sampler.thread.is_alive()


@pytest.mark.parametrize("report, fails", [
    (None, False), ({}, False),
    ({"backgroundCompaction": dict(state="IDLE", debtBytes=0, failed=0)}, False),
    ({"backgroundCompaction": dict(state="BUSY", debtBytes=0, failed=0)}, True),
    ({"backgroundCompaction": dict(state="IDLE", debtBytes=1, failed=0)}, True),
    ({"backgroundCompaction": dict(state="IDLE", debtBytes=0, failed=1)}, True),
])
def test_strict_drain_adds_state_debt_guards(workload_modules, monkeypatch, report, fails):
    adapters, worker = workload_modules
    monkeypatch.setattr(adapters, "drain", lambda port: report)
    if fails:
        with pytest.raises(RuntimeError, match="version boundary"):
            worker.strict_drain(123)
    else:
        assert worker.strict_drain(123) is report


def test_model_hash_sorts_names_but_omits_shape_metadata(workload_modules):
    adapters, worker = workload_modules
    torch = adapters.torch
    state = dict(z=torch.ones(2), a=torch.arange(4.))
    a = SimpleNamespace(state_dict=lambda: state)
    b = SimpleNamespace(state_dict=lambda: dict(reversed(list(state.items()))))
    assert worker.model_hash(a) == worker.model_hash(b)
    reshaped = SimpleNamespace(state_dict=lambda: dict(z=state["z"], a=state["a"].reshape(2, 2)))
    assert worker.model_hash(a) == worker.model_hash(reshaped)
    tensor_a = dict(image=state["a"], mask=torch.zeros(4, dtype=torch.uint8))
    tensor_b = dict(image=state["a"].reshape(2, 2), mask=tensor_a["mask"])
    assert adapters.tensor_digest([tensor_a]) != adapters.tensor_digest([tensor_b])


def test_real_cpu_mmap_worker_v0_prepares_then_v1_trains(tmp_path, workload_modules):
    adapters, worker = workload_modules
    from test_monai_comparison import fixture_data
    args, data = fixture_data(tmp_path / "inputs")
    cfg = config()
    references = [adapters.tensor_digest(adapters.CanonicalTransform(args)(item) for item in data[:count])
                  for count in [4, 5]]
    store = tmp_path / "cache"
    old_threads = adapters.torch.get_num_threads()
    try:
        reports = [worker.execute(dict(config=cfg, backend="mmap", version=stage, store=str(store),
                    manifest=str(tmp_path / f"inputs/v{stage + 1}.csv"), modelSeed=20260926 + stage,
                    referenceHash=references[stage], cpuFixture=True)) for stage in range(2)]
    finally:
        adapters.torch.set_num_threads(old_threads)
    initial, update = reports
    assert initial["epochs"] == [] and initial["modelSha256"] is None
    assert initial["preprocessCalls"] == 4 and initial["trainingSampleRequests"] == 0
    assert len(update["epochs"]) == 1 and update["trainingSampleRequests"] == 5
    assert update["preprocessCalls"] == 1 and update["trainingPreprocessCalls"] == 0
    assert update["initialModelSha256"] and update["modelSha256"]
    assert initial["tensorSha256"] == references[0] and update["tensorSha256"] == references[1]
    for report in reports:
        assert report["fullLifecycleMs"] == pytest.approx(sum(report["timingsMs"].values()))
        assert report["validationMs"] >= 0 and report["gpuUtilization"] is not None
