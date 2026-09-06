import copy
import json
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from paper_common import PYTHON_CLIENT
from prepare_vision import prepare
from benchmark_gpu_segmentation import parse_args, load_sources, preprocess_sample, artifact_to_tensor_sample, BackendContext
import numpy as np
from PIL import Image


class VisionTests(unittest.TestCase):
    def test_real_rgb_manifest_labels_and_backend_parity(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            images = root / "images"
            for label in ("class-a", "class-b"):
                (images / label).mkdir(parents=True)
                Image.new("RGB", (9, 13), (42, 77, 160)).save(images / label / "sample.png")
            manifest = root / "imagenet.csv"
            prepare("imagenet", images, manifest)
            args = parse_args(["--dataset-kind", "imagenet", "--dataset-manifest", str(manifest),
                               "--samples", "2", "--num-classes", "2", "--resize", "8"])
            sources = load_sources(args)
            self.assertEqual(sorted(s["labels"] for s in sources), [[0], [1]])
            reference = [artifact_to_tensor_sample(preprocess_sample(s, args, np), np) for s in sources]
            self.assertEqual(reference[0]["image"].shape, (3, 8, 8))
            context = BackendContext(args, reference, sources, np)
            try:
                raw, _ = context.batch("RAW_RECOMPUTE", [0, 1])
                for backend in ("AETHER_CACHE", "STATIC_PREPROCESSED_MMAP", "RAM_READY"):
                    value, _ = context.batch(backend, [0, 1])
                    np.testing.assert_array_equal(raw["images"], value["images"])
                    np.testing.assert_array_equal(raw["masks"], value["masks"])
            finally:
                context.close()
            Image.new("RGB", (9, 13), (0, 0, 0)).save(images / "class-a/sample.png")
            with self.assertRaisesRegex(ValueError, "checksum"):
                load_sources(args)

    def test_coco_multilabel_categories_are_contiguous(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            Image.new("RGB", (8, 8)).save(root / "image.jpg")
            annotation = root / "instances.json"
            annotation.write_text(json.dumps({"images": [{"id": 4, "file_name": "image.jpg"}],
                "categories": [{"id": 90, "name": "b"}, {"id": 1, "name": "a"}],
                "annotations": [{"image_id": 4, "category_id": 90}, {"image_id": 4, "category_id": 1}]}))
            manifest = root / "coco.csv"
            prepare("coco", root, manifest, annotation)
            args = parse_args(["--dataset-kind", "coco", "--dataset-manifest", str(manifest),
                               "--samples", "1", "--num-classes", "2", "--resize", "8"])
            self.assertEqual(load_sources(args)[0]["labels"], [0, 1])


if __name__ == "__main__":
    unittest.main()
