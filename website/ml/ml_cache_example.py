"""Small CPU example of preprocessing reuse through the Java Aether daemon.

Run from the repository root after starting trainingCacheDaemon:
    python website/ml/ml_cache_example.py
    python website/ml/ml_cache_example.py --image-dir path/to/images

Source files must stay immutable while the sample index is in use. Rebuild the
index after edits. This demonstrates correctness and reuse, not GPU speedup.
"""
import argparse
import hashlib
import io
from pathlib import Path

import numpy as np
from PIL import Image, __version__ as pillow_version

from aether_training_cache import AetherTrainingCache, CacheKey, TransformationFingerprint


TRANSFORM = TransformationFingerprint.from_mapping({
    "implementation": "ml-guide-rgb-v1",
    "resize": "64x64-bilinear",
    "layout": "CHW-float32-div255-npy",
    "pillow": pillow_version,
    "numpy": np.__version__,
})


def index_images(directory):
    """Hash an immutable source snapshot once, outside the epoch loop."""
    paths = sorted(p for p in Path(directory).rglob("*")
                   if p.is_file() and p.suffix.lower() in {".png", ".jpg", ".jpeg"})
    if not paths:
        raise ValueError(f"No PNG or JPEG images found in {directory}")
    return [(path, hashlib.sha256(path.read_bytes()).hexdigest()) for path in paths]


def cached_image(sample, cache):
    path, source_sha256 = sample
    key = CacheKey("ml-guide-images", source_sha256, TRANSFORM)
    payload = cache.get(key)
    hit = payload is not None
    if payload is None:
        source = path.read_bytes()
        if hashlib.sha256(source).hexdigest() != source_sha256:
            raise ValueError(f"Source changed after indexing: {path}; rebuild the index")
        with Image.open(io.BytesIO(source)) as image:
            image = image.convert("RGB").resize((64, 64), Image.Resampling.BILINEAR)
            array = np.asarray(image, dtype=np.float32).transpose(2, 0, 1) / np.float32(255)
        stream = io.BytesIO()
        np.save(stream, np.ascontiguousarray(array), allow_pickle=False)
        payload = stream.getvalue()
        cache.put(key, payload)
    # Own writable memory before random augmentation or torch.from_numpy.
    array = np.load(io.BytesIO(payload), allow_pickle=False).copy()
    return array, hit


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--image-dir", type=Path)
    parser.add_argument("--port", type=int, default=9484)
    args = parser.parse_args()
    directory = args.image_dir
    if directory is None:
        directory = Path("build/ml-guide-images")
        directory.mkdir(parents=True, exist_ok=True)
        for i in range(4):
            path = directory / f"sample-{i}.png"
            if not path.exists():
                Image.new("RGB", (96, 96), (i * 60, 100, 200)).save(path)
    samples = index_images(directory)
    with AetherTrainingCache(port=args.port) as cache:
        for epoch in (1, 2):
            hits = 0
            for sample in samples:
                array, hit = cached_image(sample, cache)
                assert array.shape == (3, 64, 64) and array.dtype == np.float32
                hits += int(hit)
            print(f"epoch {epoch}: hits={hits}, misses={len(samples) - hits}, shape=(3, 64, 64)")


if __name__ == "__main__":
    main()
