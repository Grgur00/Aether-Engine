"""Runner boundaries with disposable CPU stores and controlled report doubles."""
import random
import sys
from types import SimpleNamespace

import numpy as np
import pytest

import benchmark_gpu_segmentation as workload


def arguments(*extra):
    return workload.parse_args(["--samples", "3", "--height", "4", "--width", "4",
        "--resize", "4", "--batch-size", "2", "--epochs", "1", "--warmup-steps", "1",
        "--gpu-sample-interval-ms", "0", *extra])


def test_cli_defaults_normalized_and_backend_tokens_not_trimmed(tmp_path):
    manifest = tmp_path / "manifest.csv"
    manifest.write_text("not parsed here", encoding="utf-8")
    args = arguments("--dataset-kind", "oct5k", "--dataset-manifest", str(manifest),
                     "--oct5k-image-size", "8")
    assert args.resize == 8 and args.num_classes == 1000 and args.augmentation_mode == "light"
    assert args.invocation_args[-2:] == ["--oct5k-image-size", "8"]
    assert arguments().augmentation_mode == "none"
    with pytest.raises(SystemExit):
        arguments("--backends", "raw, aether,mmap")


@pytest.mark.parametrize("option,value,match", [
    ("--samples", "0", "samples"), ("--height", "3", "height"),
    ("--batch-size", "0", "batch-size"), ("--warmup-steps", "-1", "warmup"),
    ("--normalization-scale", "nan", "finite"), ("--preprocess-passes", "0", "preprocess"),
    ("--prefetch-batches", "-1", "prefetch"), ("--workers", "1", "multiprocess"),
    ("--changed-percent", "101", "changed-percent"),
    ("--initial-cache-hit-ratio", "101", "initial-cache-hit"),
    ("--max-reference-bytes", "1", "reference plus RAM"),
])
def test_argument_guards(option, value, match):
    with pytest.raises(ValueError, match=match):
        arguments(option, value)


def test_java_requires_external_isolation_for_repeated_runs():
    with pytest.raises(ValueError, match="isolated stores"):
        arguments("--aether-engine", "java", "--runs", "2")
    assert np.isnan(arguments("--gpu-sample-interval-ms", "nan").gpu_sample_interval_ms)


def test_lazy_reference_recomputes_scalar_slice_and_mutable_sources(monkeypatch):
    calls = []
    args = arguments("--backends", "raw,aether,mmap")
    sources = [{"sample_id": "a"}, {"sample_id": "b"}]
    monkeypatch.setattr(workload, "preprocess_sample", lambda source, args, np: calls.append(source["sample_id"]) or source)
    monkeypatch.setattr(workload, "artifact_to_tensor_sample", lambda value, np: dict(value))
    reference = workload.make_reference(args, sources, np)
    assert isinstance(reference, workload.LazyReferenceSequence) and calls == []
    assert reference[-1] == reference[-1] == {"sample_id": "b"}
    assert calls == ["b", "b"]
    assert reference[::-1] == [{"sample_id": "b"}, {"sample_id": "a"}]
    sources.append({"sample_id": "c"})
    assert len(reference) == 3
    args.backend_names = (*args.backend_names, "RAM_READY")
    assert isinstance(workload.make_reference(args, sources, np), list)


def test_descriptor_invalidation_self_test_does_not_access_storage():
    args = arguments()
    result = workload.cache_invalidation_self_test(args)
    assert result["passed"] and all(result.values())
    params = {"x": 1, "y": 2}
    key = workload.test_cache_key(params, source_identity="a", source_hash="h")
    assert key == workload.test_cache_key({"y": 2, "x": 1}, source_identity="a", source_hash="h")
    assert key != workload.test_cache_key(params, source_identity="a", source_hash="changed")


@pytest.fixture
def report_probes(monkeypatch):
    monkeypatch.setattr(workload, "environment_report", lambda **kwargs: {"fixture": True})
    monkeypatch.setattr(workload, "storage_locations", lambda args: {})
    monkeypatch.setattr(workload, "import_numpy_status", lambda: dict(available=True, version=np.__version__))
    monkeypatch.setattr(workload, "accelerator_report", lambda: dict(deviceAvailable=False, backend="unavailable"))


def test_unsupported_report_skips_without_loading_sources(report_probes, monkeypatch):
    monkeypatch.setattr(workload, "load_sources", lambda args: pytest.fail("skip loaded inputs"))
    report = workload.run_benchmark(arguments())
    assert report["status"] == "SKIPPED_UNSUPPORTED_ACCELERATOR" and not report["allPassed"]
    assert report["validity"]["noCpuFallback"]["passed"]
    assert not report["validity"]["requiresAcceleratorBackend"]["passed"]
    assert report["runs"] == [] and report["endedAt"] is not None


def test_population_only_success_is_not_accelerator_training_gate(report_probes, monkeypatch):
    calls = []
    monkeypatch.setattr(workload, "load_sources", lambda args: [])
    monkeypatch.setattr(workload, "make_reference", lambda *args: [])
    class Context:
        def __init__(self, *args):
            calls.append("construct")
        def populate_aether_dataset(self):
            calls.append("aether")
            return {"entriesPublished": 0}
        def populate_mmap_dataset(self):
            calls.append("mmap")
            return {"entriesAppended": 0}
        def close(self):
            calls.append("close")
    monkeypatch.setattr(workload, "BackendContext", Context)
    report = workload.run_benchmark(arguments("--aether-populate-only", "--mmap-populate-only"))
    assert calls == ["construct", "aether", "mmap", "close"]
    assert report["status"] == "PASSED" and report["allPassed"]
    assert not report["validity"]["requiresAcceleratorBackend"]["passed"]
    assert not report["correctness"]["allChecksumsEqual"] and report["runs"] == []


def test_requested_gpu_count_checked_before_smoke(report_probes, monkeypatch):
    monkeypatch.setattr(workload, "accelerator_report", lambda: dict(deviceAvailable=True,
        backend="cuda", cudaVersion="12", hipVersion=None, deviceName="fixture"))
    monkeypatch.setitem(sys.modules, "torch", SimpleNamespace(cuda=SimpleNamespace(device_count=lambda: 1)))
    monkeypatch.setattr(workload, "gpu_smoke_test", lambda *args: pytest.fail("smoke ran"))
    with pytest.raises(RuntimeError, match="GPU count"):
        workload.run_benchmark(arguments("--gpu-count", "2"))


def test_equivalence_checks_only_first_eight_and_raises_on_prefix_change():
    args = arguments()
    args.backend_names = ("RAW_RECOMPUTE",)
    reference = [{"image": np.zeros((1, 4, 4), dtype=np.float32),
                  "mask": np.zeros((1, 4, 4), dtype=np.float32)} for _ in range(10)]
    seen = []
    def batch(backend, indices):
        seen.append(indices)
        return workload.stack_values([reference[i] for i in indices], np), {}
    context = SimpleNamespace(args=args, np=np, reference=reference, batch=batch)
    assert workload.validate_backend_equivalence(context, reference)["RAW_RECOMPUTE"]["samplesChecked"] == 8
    assert seen == [list(range(8))]
    def bad_batch(backend, indices):
        value, _ = batch(backend, indices)
        value["images"][0, 0, 0, 0] = 1
        return value, {}
    context.batch = bad_batch
    with pytest.raises(RuntimeError, match="equivalent model input"):
        workload.validate_backend_equivalence(context, reference)


def test_main_writes_skipped_report_without_exit_policy(tmp_path, monkeypatch, capsys):
    output = tmp_path / "nested" / "report.json"
    monkeypatch.setattr(workload, "parse_args", lambda: SimpleNamespace(output=str(output)))
    report = dict(benchmark="fixture", profile="gpu-training", status="SKIPPED_UNSUPPORTED_ACCELERATOR", allPassed=False)
    monkeypatch.setattr(workload, "run_benchmark", lambda args: report)
    workload.main()
    assert output.is_file() and output.read_text(encoding="utf-8").endswith("\n")
    assert "SKIPPED_UNSUPPORTED_ACCELERATOR" in capsys.readouterr().out


def test_tiny_real_cpu_per_run_controller_has_parity_and_matching_reuse(tmp_path):
    torch = pytest.importorskip("torch")
    threads, rng, python_rng = torch.get_num_threads(), torch.get_rng_state(), random.getstate()
    deterministic, benchmark = torch.are_deterministic_algorithms_enabled(), torch.backends.cudnn.benchmark
    args = arguments("--aether-cache-dir", str(tmp_path / "aether"),
                     "--mmap-cache-dir", str(tmp_path / "mmap"))
    try:
        torch.set_num_threads(1)
        report = workload.run_training_once(args, torch, np, torch.device("cpu"), 1)
        assert report["seed"] == args.seed + 1 and report["modelParityPassed"]
        assert set(report["backends"]) == set(args.backend_names)
        assert report["samplesCheckedElementwise"] == 3
        assert report["mmapDynamics"]["invariants"]["passed"]
        assert report["cacheDynamics"]["invariants"]["passed"]
        assert len({item["modelStateSha256"] for item in report["backends"].values()}) == 1
    finally:
        torch.set_num_threads(threads)
        torch.set_rng_state(rng)
        random.setstate(python_rng)
        torch.use_deterministic_algorithms(deterministic)
        torch.backends.cudnn.benchmark = benchmark
