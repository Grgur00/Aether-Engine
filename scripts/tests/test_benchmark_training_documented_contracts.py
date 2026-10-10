"""Small CPU/double checks for training documentation, not GPU measurements."""
from types import SimpleNamespace

import numpy as np
import pytest

import benchmark_gpu_segmentation as workload


@pytest.fixture
def cpu_torch():
    torch = pytest.importorskip("torch")
    previous = torch.get_num_threads()
    torch.set_num_threads(1)
    try:
        yield torch
    finally:
        torch.set_num_threads(previous)


def arguments(*extra):
    return workload.parse_args(["--samples", "3", "--batch-size", "2", "--resize", "4",
                                "--warmup-steps", "1", *extra])


def batch():
    return {"images": np.arange(32, dtype=np.float32).reshape(2, 1, 4, 4) / 32,
            "masks": np.zeros((2, 1, 4, 4), dtype=np.float32)}


@pytest.mark.parametrize("tier,channels", [("small", [16, 32]),
                                           ("medium", [32, 64, 128]),
                                           ("large", [64, 128, 256, 512])])
def test_model_is_spatial_convolution_stack_not_encoder_decoder(cpu_torch, tier, channels):
    torch = cpu_torch
    model = workload.create_model(tier, torch)
    convolutions = [item for item in model.modules() if isinstance(item, torch.nn.Conv2d)]
    assert [item.out_channels for item in convolutions] == [c for c in channels for _ in range(2)] + [1]
    assert all(item.bias is not None for item in convolutions)
    assert not any(isinstance(item, (torch.nn.MaxPool2d, torch.nn.ConvTranspose2d,
                                    torch.nn.BatchNorm2d, torch.nn.Dropout)) for item in model.modules())
    assert model(torch.zeros((2, 1, 4, 4))).shape == (2, 1, 4, 4)
    with pytest.raises(KeyError):
        workload.create_model("invalid", torch)


@pytest.mark.parametrize("kind", ["coco", "imagenet"])
def test_workload_factory_dispatches_vision(monkeypatch, kind):
    from aether_training_cache import vision_workload
    token = object()
    args, torch = SimpleNamespace(dataset_kind=kind), object()
    monkeypatch.setattr(vision_workload, "model", lambda a, t: token if a is args and t is torch else None)
    assert workload.create_workload_model(args, torch) is token


def test_warmup_changes_model_and_optimizer_state(cpu_torch):
    torch, args = cpu_torch, arguments()
    model = workload.create_workload_model(args, torch)
    before = {key: value.clone() for key, value in model.state_dict().items()}
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
    workload.run_model_warmup(torch, model, torch.nn.BCEWithLogitsLoss(), optimizer,
                              torch.device("cpu"), args)
    assert any(not torch.equal(before[key], value) for key, value in model.state_dict().items())
    assert optimizer.state and all(int(state["step"]) == 1 for state in optimizer.state.values())
    assert model.training


def test_seed_sets_torch_policy_without_seeding_global_numpy(cpu_torch):
    import random
    torch = cpu_torch
    python_state, numpy_state = random.getstate(), np.random.get_state()
    torch_state = torch.get_rng_state()
    deterministic, benchmark = torch.are_deterministic_algorithms_enabled(), torch.backends.cudnn.benchmark
    try:
        workload.set_seed(torch, 13)
        first = torch.rand(3)
        workload.set_seed(torch, 13)
        assert torch.equal(first, torch.rand(3))
        after = np.random.get_state()
        assert numpy_state[0] == after[0] and np.array_equal(numpy_state[1], after[1])
        assert numpy_state[2:] == after[2:]
        assert torch.are_deterministic_algorithms_enabled() and not torch.backends.cudnn.benchmark
    finally:
        random.setstate(python_state)
        np.random.set_state(numpy_state)
        torch.set_rng_state(torch_state)
        torch.use_deterministic_algorithms(deterministic)
        torch.backends.cudnn.benchmark = benchmark


def test_augmentation_is_repeatable_outside_cached_values():
    args, original = arguments("--augmentation-mode", "light"), batch()
    saved = {key: value.copy() for key, value in original.items()}
    context = SimpleNamespace(args=args, np=np, run_seed=91)
    first = workload.augment_batch(original, context, [0, 1], 0, 0)
    again = workload.augment_batch(original, context, [0, 1], 0, 0)
    next_step = workload.augment_batch(original, context, [0, 1], 1, 0)
    assert np.array_equal(first["images"], again["images"])
    assert not np.array_equal(first["images"], next_step["images"])
    assert all(np.array_equal(original[key], saved[key]) for key in saved)
    assert first["images"].dtype == first["masks"].dtype == np.float32
    args.augmentation_mode = "none"
    assert workload.augment_batch(original, context, [0, 1], 0, 0) is original


def test_tensor_normalization_can_share_memory_and_reject_negative_strides(cpu_torch):
    torch, original = cpu_torch, batch()
    tensors, metadata = workload.normalize_cpu_batch(torch, original)
    tensors["images"][0, 0, 0, 0] = 17
    assert original["images"][0, 0, 0, 0] == 17
    assert metadata["images"]["stride"] == metadata["images"]["expectedStride"] == [16, 16, 4, 1]
    assert not metadata["images"]["isPinned"]
    flipped = {key: value[:, :, :, ::-1] for key, value in original.items()}
    with pytest.raises(ValueError):
        workload.normalize_cpu_batch(torch, flipped)
    assert workload.expected_contiguous_stride(()) == ()
    assert workload.expected_contiguous_stride((2, 0, 3)) == (0, 3, 1)


def test_train_step_prepared_wait_does_not_lookup_batch(cpu_torch):
    torch, args = cpu_torch, arguments()
    model = workload.create_model("small", torch)
    optimizer = torch.optim.AdamW(model.parameters(), lr=1e-3)
    context = SimpleNamespace(args=args, np=np)
    def unexpected(*args):
        raise AssertionError("prepared step must not call context.batch")
    context.batch = unexpected
    result = workload.train_step(torch, model, optimizer, torch.nn.BCEWithLogitsLoss(),
        torch.device("cpu"), "RAM_READY", context, [0, 1], 0, 0,
        prepared=(batch(), {"artifactDecodeMs": 2., "aetherRequestTraces": [{"id": 1}]}, 10.),
        prefetch_wait_ms=7.)
    assert result["batchPrepareMs"] == 10 and result["inputWaitMs"] == result["prefetchWaitMs"] == 7
    assert result["artifactDecodeMs"] == 2 and result["aetherRequestTraces"] == [{"id": 1}]
    assert result["batchSize"] == 2 and np.isfinite(result["loss"])
    assert all(result[key] >= 0 for key in ["hostToDeviceMs", "forwardMs", "backwardMs", "optimizerMs"])
    assert all(int(state["step"]) == 1 for state in optimizer.state.values())


def test_inline_step_calls_context_batch(cpu_torch):
    torch, args, calls = cpu_torch, arguments(), []
    model = workload.create_model("small", torch)
    context = SimpleNamespace(args=args, np=np)
    def prepare(backend, indices):
        calls.append((backend, indices))
        return batch(), {"preprocessMs": 4.}
    context.batch = prepare
    result = workload.train_step(torch, model, torch.optim.AdamW(model.parameters()),
        torch.nn.BCEWithLogitsLoss(), torch.device("cpu"), "RAW_RECOMPUTE", context, [0, 1], 0, 0)
    assert calls == [("RAW_RECOMPUTE", [0, 1])]
    assert result["inputWaitMs"] == result["batchPrepareMs"]
    assert result["preprocessMs"] == 4 and result["prefetchWaitMs"] == 0


def test_schedule_truncates_epoch_without_shuffle():
    assert list(workload.scheduled_batches(arguments(), 3)) == [([0, 1], 0), ([2], 0), ([0, 1], 1)]
    assert list(workload.scheduled_batches(arguments(), 0)) == []


def test_sanity_metrics_pool_first_batch_empty_sets_score_zero(cpu_torch):
    torch, args, value = cpu_torch, arguments(), batch()
    reference = [{"image": value["images"][i], "mask": value["masks"][i]} for i in range(2)]
    context = SimpleNamespace(args=args, np=np, reference=reference)
    class NegativeModel(torch.nn.Module):
        def forward(self, images):
            return torch.full_like(images, -10)
    model = NegativeModel()
    result = workload.segmentation_sanity_metrics(torch, model, torch.nn.BCEWithLogitsLoss(),
                                                  torch.device("cpu"), context)
    assert result["samplesChecked"] == 2 and result["dice"] == result["meanIoU"] == 0
    assert model.training
    context.reference = []
    assert workload.segmentation_sanity_metrics(torch, model, None, torch.device("cpu"), context) is None


@pytest.mark.parametrize("requested,cuda,hip,available,expected", [
    ("auto", "12", None, True, True), ("auto", None, "7", True, True),
    ("cuda", "12", "7", True, False), ("rocm", None, "7", True, True),
    ("cuda", "12", None, False, False), ("unknown", "12", None, True, False),
])
def test_backend_match_uses_availability_and_build_versions(requested, cuda, hip, available, expected):
    report = dict(deviceAvailable=available, cudaVersion=cuda, hipVersion=hip, backend="incorrect-label")
    assert workload.accelerator_backend_matches(report, requested) is expected


def test_unsupported_reason_precedence_and_family_name_match():
    report = dict(deviceAvailable=True, cudaVersion="12", hipVersion=None, deviceName="Tesla T4")
    assert workload.unsupported_reason(dict(available=False, error="broken"), {}, "cuda", "T4").startswith("NumPy")
    assert workload.unsupported_reason(dict(available=True), report, "cuda", "t4") is None
    assert workload.unsupported_reason(dict(available=True), report, "rocm", "wrong").startswith("PyTorch")
    assert workload.gpu_name_matches("AMD Radeon RX 7900 XT", "AMD Radeon RX 7900 XTX")
    assert workload.gpu_name_matches("anything", "")


def test_sync_and_event_probe_errors():
    calls = []
    def event(**kwargs):
        raise RuntimeError("no CUDA")
    fake = SimpleNamespace(cuda=SimpleNamespace(synchronize=lambda *args: calls.append(args), Event=event))
    workload.synchronize_device(fake, SimpleNamespace(type="cpu"))
    assert calls == []
    device = SimpleNamespace(type="cuda")
    workload.synchronize_device(fake, device)
    assert calls == [(device,)]
    assert workload.validate_device_events(fake) is False


def test_metadata_precision_is_label_not_dtype_enforcement(cpu_torch):
    torch, args = cpu_torch, arguments()
    model = workload.create_model("small", torch).double()
    metadata = workload.model_metadata(model, args, torch)
    assert metadata["precision"] == "fp32" and next(model.parameters()).dtype == torch.float64
    assert metadata["parameters"] == sum(parameter.numel() for parameter in model.parameters())
    assert metadata["device"] == "cpu" and metadata["timingMode"] == "wall+synchronize"
    with pytest.raises(StopIteration):
        workload.model_metadata(torch.nn.Identity(), args, torch)


@pytest.mark.parametrize("fail", [False, True])
def test_backend_closes_iterator_and_sampler_before_sanity(cpu_torch, monkeypatch, fail):
    from aether_training_cache import resources
    torch, args, events = cpu_torch, arguments(), []
    context = SimpleNamespace(args=args, np=np)
    model = workload.create_model("small", torch)
    class Sampler:
        def __init__(self, interval):
            pass
        def start(self):
            events.append("start")
        def stop(self):
            events.append("stop")
            return []
    def prepared(*args, **kwargs):
        try:
            yield [0, 1], 0, (batch(), {}, 0.), 0.
        finally:
            events.append("close")
    def sanity(*args):
        events.append("sanity")
        return {}
    monkeypatch.setattr(workload, "GpuUtilizationSampler", Sampler)
    monkeypatch.setattr(workload, "prepared_batches", prepared)
    monkeypatch.setattr(workload, "segmentation_sanity_metrics", sanity)
    monkeypatch.setattr(workload, "summarize_backend", lambda *args, **kwargs: {})
    monkeypatch.setattr(workload, "process_metrics_snapshot", lambda: {})
    monkeypatch.setattr(workload, "process_metrics_delta", lambda *args: {})
    monkeypatch.setattr(resources, "snapshot", lambda *args: {})
    monkeypatch.setattr(resources, "delta", lambda *args: {})
    if fail:
        def failure(*args, **kwargs):
            raise RuntimeError("injected training failure")
        monkeypatch.setattr(workload, "train_step", failure)
    call = lambda: workload.run_backend(torch, model, torch.optim.AdamW(model.parameters()),
        torch.nn.BCEWithLogitsLoss(), torch.device("cpu"), "RAM_READY", context, 1)
    if fail:
        with pytest.raises(RuntimeError, match="injected training failure"):
            call()
        assert events == ["start", "close", "stop"]
    else:
        report = call()
        assert events == ["start", "close", "stop", "sanity"]
        assert len(report["modelStateSha256"]) == 64 and len(report["lossTrajectory"]) == 1
        assert report["resources"]["gpuHoursDuringTraining"] == 0
