"""Real MONAI caches; optional real-Java end-to-end CPU fixture."""
import csv
import hashlib
import json
import os

import pytest

pytest.importorskip("monai")
pytest.importorskip("lmdb")
import numpy as np
import torch
from PIL import Image
import monai_comparison as bench


def fixture_data(root):
    root.mkdir(parents=True, exist_ok=True)
    rows = []
    for i in range(5):
        pixels = np.random.default_rng(i).integers(0, 256, (20, 20), dtype=np.uint8)
        image, mask = root / f"image-{i}.png", root / f"mask-{i}.png"
        Image.fromarray(pixels).save(image)
        Image.fromarray((pixels > 127).astype(np.uint8) * 255).save(mask)
        a, b = bench.sha256(image), bench.sha256(mask)
        rows.append(dict(sample_id=str(i), image_path=str(image), mask_path=str(mask), disease="fixture",
                         image_sha256=a, mask_sha256=b, source_identity=hashlib.sha256(f"{a}:{b}".encode()).hexdigest()))
    for name, count in (("v1", 4), ("v2", 5)):
        with (root / f"{name}.csv").open("w", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
            writer.writeheader()
            writer.writerows(rows[:count])
    args = bench.workload.parse_args(["--dataset-kind", "oct5k", "--dataset-manifest", str(root / "v2.csv"),
                                      "--samples", "5", "--resize", "16", "--preprocess-passes", "4"])
    return args, bench.workload.load_sources(args)


@pytest.mark.parametrize("backend", ["mmap", "monai_persistent", "monai_lmdb"])
def test_reuse_content_and_transform_invalidation(tmp_path, backend):
    args, data = fixture_data(tmp_path / "inputs")
    directory = tmp_path / "cache"
    for items, expected in ((data[:4], 4), (data, 1), (data, 0)):
        transform = bench.CanonicalTransform(args)
        ds = bench.dataset(backend, items, transform, directory)
        try:
            values = bench.fetch(ds, list(range(len(items))))
            assert transform.calls == expected
            reference = [bench.CanonicalTransform(args)(item) for item in items]
            assert bench.tensor_digest(values) == bench.tensor_digest(reference)
        finally:
            bench.close(ds)
    args.pipeline_version = "changed"
    transform = bench.CanonicalTransform(args)
    ds = bench.dataset(backend, data, transform, directory)
    try:
        bench.fetch(ds, list(range(len(data))))
        assert transform.calls == 5
    finally:
        bench.close(ds)
    changed = [{**item, "source_identity": "corrected-" + item["source_identity"]} for item in data]
    transform = bench.CanonicalTransform(args)
    ds = bench.dataset(backend, changed, transform, directory)
    try:
        bench.fetch(ds, list(range(len(data))))
        assert transform.calls == 5
    finally:
        bench.close(ds)


def test_tensor_digest_checks_dtype_and_values():
    a = {"image": torch.ones(1, dtype=torch.float16), "mask": torch.zeros(1, dtype=torch.uint8)}
    b = {**a, "image": a["image"].float()}
    assert bench.tensor_digest([a]) != bench.tensor_digest([b])


@pytest.mark.skipif(os.environ.get("AETHER_JAVA_TEST") != "1", reason="opt-in real Java daemon")
def test_four_backend_cpu_campaign(tmp_path):
    fixture_data(tmp_path / "inputs")
    config = tmp_path / "datasets.json"
    config.write_text(json.dumps({"oct5k": {"manifestV1": str(tmp_path / "inputs/v1.csv"),
        "manifestV2": str(tmp_path / "inputs/v2.csv"), "samplesV1": 4, "samplesV2": 5,
        "expectedReusable": 4, "imageSize": 16}}))
    old_threads = torch.get_num_threads()
    torch.set_num_threads(1)
    try:
        bench.main(["--config", str(config), "--output", str(tmp_path / "campaign"),
                    "--pilot-repeats", "1", "--epochs", "2", "--cpu-fixture"])
    finally:
        torch.set_num_threads(old_threads)
    report = json.loads((tmp_path / "campaign/blocks/00/paired.json").read_text())
    assert set(report["backends"]) == set(bench.BACKENDS)
    for result in report["backends"].values():
        assert result["initialPopulation"]["preprocessCalls"] == 4
        update = result["updateTraining"]
        assert update["preprocessCalls"] == 1
        assert update["trainingPreprocessCalls"] == 0
        assert update["totalMs"] >= update["preparationMs"]
        assert len(update["epochs"]) == 2
    assert len({r["updateTraining"]["modelSha256"] for r in report["backends"].values()}) == 1
