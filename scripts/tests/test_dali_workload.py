"""DALI-independent identity, serialization and planning checks; no CUDA claims."""
import hashlib
import json
from types import SimpleNamespace

import numpy as np
import pytest

from aether_training_cache.dali_workload import EncodedSource, artifact_key, descriptor, payloads, targets
from benchmark_gpu_segmentation import unpack_payload
from dali_comparison import main, batches, validate_payloads


def test_dali_identity_binds_source_and_actual_pipeline_version():
    args = SimpleNamespace(dataset_kind="coco", resize=8, num_classes=2)
    params = descriptor(args, "test-version")
    source = {"sample_id": "sample", "source_hash": "source-a"}
    key = artifact_key(source, params)
    assert key != artifact_key({**source, "source_hash": "source-b"}, params)
    assert key != artifact_key(source, {**params, "daliVersion": "test-version-2"})
    assert key != artifact_key(source, {**params, "resize": 16})
    assert key == artifact_key(dict(reversed(list(source.items()))), dict(reversed(list(params.items()))))


def test_dali_artifact_packing_matches_exact_fp16_reference():
    sources = [{"sample_id": "a", "labels": [1]}, {"sample_id": "b", "labels": [0, 1]}]
    images = np.linspace(0, 1, 96, dtype=np.float16).astype(np.float32).reshape(2, 3, 4, 4)
    records = payloads(images, sources, [0, 1], 2, np)
    expected = {i: hashlib.sha256(value).hexdigest() for i, value in records.items()}
    validate_payloads(records, expected)
    for index, value in records.items():
        decoded = unpack_payload(value, np)
        np.testing.assert_array_equal(decoded["image"], images[index])
        np.testing.assert_array_equal(decoded["mask"], targets(sources, [index], 2, np)[0])
    with pytest.raises(ValueError, match="parity"):
        validate_payloads({0: b"incorrect"}, expected)


def test_external_callback_is_stateless_and_stops_prefetch(tmp_path):
    path = tmp_path / "encoded.bin"
    path.write_bytes(b"test bytes")
    callback = EncodedSource([{"image_path": path}], [[0], [0]])
    np.testing.assert_array_equal(callback(1)[0], callback(0)[0])
    for iteration in (2, 3, 10):
        with pytest.raises(StopIteration):
            callback(iteration)
    assert batches(5, 2, 2) == [[0, 1], [2, 3], [4], [0, 1], [2, 3], [4]]


def test_dali_plan_runs_without_cuda_or_dali(tmp_path):
    main(["--plan-only", "--samples", "8", "--repeats", "2", "--output", str(tmp_path)])
    plan = json.loads((tmp_path / "plan.json").read_text())
    assert plan["primaryEquivalent"] is False
    assert {point["dataset_kind"] for point in plan["conditions"]} == {"coco", "imagenet"}
    assert all(point["samples"] == 8 for point in plan["conditions"])
    assert not (tmp_path / "summary.json").exists()


def test_dali_orchestration_cpu_test_doubles_preserve_models_and_partial_batch_counts(tmp_path, monkeypatch):
    """Exercise orchestration only. CUDA/DALI and Java transport are explicit doubles."""
    import contextlib
    import torch
    from PIL import Image
    import dali_comparison as driver
    from prepare_vision import prepare
    from dali_analyze import load_dali_blocks
    from paper_common import write_json
    images = tmp_path / "images"
    for index in range(5):
        folder = images / str(index % 2)
        folder.mkdir(parents=True, exist_ok=True)
        Image.new("RGB", (8, 8), (index * 40, 70, 90)).save(folder / f"{index}.png")
    manifest = tmp_path / "manifest.csv"
    prepare("imagenet", images, manifest)
    point = dict(dataset_kind="imagenet", dataset_manifest=str(manifest), dataset_split="train", samples=5,
                 num_classes=2, resize=8, trust_manifest_hashes=False)
    args = SimpleNamespace(seed_base=1, epochs=2, batch_size=2, reuse_percent=40, model_tier="small",
                           dali_threads=2, dali_prefetch=2, samples=None)
    params = descriptor(SimpleNamespace(**point), "TEST DOUBLE")
    protocol = {"schema": "aether-dali-protocol-v1", "measurementRole": "generated-fixture-smoke",
                "pipelineParameters": {"imagenet": params}, "conditions": [point], "repeats": 1,
                "seedBase": 1, "epochs": 2, "reusePercent": 40}
    class FakePipeline:
        def __init__(self, args, sources, schedule=None):
            self.schedule, self.iteration = schedule, 0
        def batch(self, indices=None):
            if indices is None:
                indices = self.schedule[self.iteration]
                self.iteration += 1
            return torch.stack([torch.full((3, 8, 8), index / 8, dtype=torch.float16).float() for index in indices])
        def close(self):
            pass
    values = {}
    class FakeJava:
        measured_engine_info = {"engine": "TEST DOUBLE", "durability": "DURABLE"}
        def cached_artifact_ids(self, keys):
            return {key: key for key in keys if key in values}
        def commit_bytes_many(self, entries):
            values.update({entry["cache_key"]: entry["data"] for entry in entries})
        def load_cached_bytes_many(self, keys):
            return {key: values[key] for key in keys if key in values}
        def reset_operation_metrics(self):
            pass
        def operation_metrics(self):
            return {"scope": "TEST DOUBLE"}
        def close(self):
            pass
    original_store = driver.open_store
    @contextlib.contextmanager
    def fake_daemon(path):
        yield {"port": 123}
    monkeypatch.setattr(driver, "DaliBatches", FakePipeline)
    monkeypatch.setattr(driver, "java_daemon", fake_daemon)
    monkeypatch.setattr(driver, "open_store", lambda backend, root, port: FakeJava() if backend == "aether" else original_store(backend, root, port))
    monkeypatch.setattr(torch.Tensor, "cuda", lambda self: self)
    monkeypatch.setattr(torch.nn.Module, "cuda", lambda self: self)
    monkeypatch.setattr(torch.cuda, "synchronize", lambda: None)
    monkeypatch.setattr(torch.cuda, "reset_peak_memory_stats", lambda: None)
    monkeypatch.setattr(torch.cuda, "max_memory_allocated", lambda: 0)
    monkeypatch.setattr(torch.cuda, "current_stream", lambda: SimpleNamespace(synchronize=lambda: None))
    threads = torch.get_num_threads()
    torch.set_num_threads(1)
    try:
        output = tmp_path / "block-0000.json"
        report = driver.run_block(args, point, 0, output, protocol, "test-environment")
    finally:
        torch.set_num_threads(threads)
    assert report["modelParityPassed"] is True
    for backend in ("aether", "mmap"):
        measured = report["backends"][backend]
        assert (measured["lookups"], measured["hits"], measured["misses"], measured["publishedEntries"]) == (10, 7, 3, 3)
    driver.validated_report(output, protocol, "test-environment")
    write_json(tmp_path / "protocol.json", protocol)
    with pytest.raises(ValueError, match="fixture smoke"):
        load_dali_blocks(tmp_path)
