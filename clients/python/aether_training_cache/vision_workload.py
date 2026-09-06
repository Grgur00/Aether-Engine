"""Real RGB classification workloads; no synthetic expansion of medical images."""
import csv
import hashlib
import json
import time
from pathlib import Path


def file_hash(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def load_sources(args):
    manifest = Path(args.dataset_manifest).resolve()
    with manifest.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        required = {"sample_id", "image_path", "image_sha256", "labels"}
        if not required <= set(reader.fieldnames or []):
            raise ValueError(f"vision manifest requires {sorted(required)}")
        rows = [row for row in reader if not row.get("split") or row["split"] == args.dataset_split]
    if len(rows) < args.samples:
        raise ValueError("requested dataset cardinality exceeds real manifest rows")
    sources, seen = [], set()
    for row in rows[:args.samples]:
        if not row["sample_id"] or row["sample_id"] in seen:
            raise ValueError("vision sample IDs must be unique and nonempty")
        seen.add(row["sample_id"])
        path = Path(row["image_path"])
        path = path if path.is_absolute() else manifest.parent / path
        labels = sorted(set(json.loads(row["labels"])))
        if any(type(label) is not int or not 0 <= label < args.num_classes for label in labels):
            raise ValueError("invalid class index in manifest")
        if args.dataset_kind == "imagenet" and len(labels) != 1:
            raise ValueError("ImageNet requires exactly one class per image")
        actual_hash = file_hash(path) if not args.trust_manifest_hashes else row["image_sha256"]
        if actual_hash != row["image_sha256"]:
            raise ValueError(f"source checksum mismatch: {path}")
        identity = hashlib.sha256(json.dumps([actual_hash, labels], separators=(",", ":")).encode()).hexdigest()
        sources.append({"kind": args.dataset_kind, "sample_id": row["sample_id"],
                        "source_identity": identity, "source_hash": identity,
                        "image_path": path.resolve(), "labels": labels})
    return sources


def parameters(args):
    import PIL
    return {"dataset": args.dataset_kind, "task": "multilabel-classification" if args.dataset_kind == "coco" else "classification",
            "decode": "Pillow RGB", "pillowVersion": PIL.__version__, "resize": args.resize,
            "resampling": "bilinear", "denoiseRadius": 1.0, "preprocessPasses": args.preprocess_passes,
            "normalization": "rgb-divide-255-v1", "numClasses": args.num_classes,
            "artifactEncodingVersion": "nchw-float16-uint8-v1", "implementationVersion": "vision-v1"}


def preprocess(sample, args, np):
    from PIL import Image, ImageFilter
    started = time.perf_counter()
    with Image.open(sample["image_path"]) as source:
        image = source.convert("RGB")
        image.load()
    source_ms = (time.perf_counter() - started) * 1000
    started = time.perf_counter()
    image = image.resize((args.resize, args.resize), Image.Resampling.BILINEAR)
    for _ in range(args.preprocess_passes - 1):
        image = image.filter(ImageFilter.GaussianBlur(radius=1))
    array = np.asarray(image, dtype=np.float32).transpose(2, 0, 1) / 255.0
    target = np.zeros(args.num_classes, dtype=np.float32)
    target[sample["labels"]] = 1
    return {"sample_id": sample["sample_id"], "image": array, "mask": target}, {
        "sourceLoadMs": source_ms, "preprocessMs": (time.perf_counter() - started) * 1000}


def model(args, torch):
    width = {"small": 16, "medium": 32, "large": 64}[args.model_tier]
    nn = torch.nn
    return nn.Sequential(nn.Conv2d(3, width, 3, padding=1), nn.ReLU(), nn.MaxPool2d(2),
                         nn.Conv2d(width, width * 2, 3, padding=1), nn.ReLU(),
                         nn.AdaptiveAvgPool2d(1), nn.Flatten(), nn.Linear(width * 2, args.num_classes))
