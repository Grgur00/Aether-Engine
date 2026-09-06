"""Build hashed manifests from locally acquired licensed COCO/ImageNet datasets."""
import argparse
import csv
import json
import random
import os
import tempfile
from pathlib import Path

from paper_common import write_json
from aether_training_cache.vision_workload import file_hash


def prepare(kind, root, output, annotations=None, split="train", seed=20260904):
    root, output = Path(root).resolve(), Path(output).resolve()
    if not root.is_dir():
        raise ValueError("dataset image directory does not exist")
    rows = []
    if kind == "imagenet":
        classes = sorted(path.name for path in root.iterdir() if path.is_dir())
        if not classes:
            raise ValueError("ImageNet root must contain class subdirectories")
        for label, class_name in enumerate(classes):
            for path in sorted((root / class_name).rglob("*")):
                if path.suffix.lower() in {".jpeg", ".jpg", ".png"}:
                    rows.append((path.relative_to(root).as_posix(), path, [label]))
    else:
        if annotations is None:
            raise ValueError("COCO requires the instances annotation JSON")
        data = json.loads(Path(annotations).read_text(encoding="utf-8"))
        categories = sorted(data["categories"], key=lambda category: category["id"])
        classes = [category["name"] for category in categories]
        category_map = {category["id"]: index for index, category in enumerate(categories)}
        labels = {}
        for annotation in data["annotations"]:
            labels.setdefault(annotation["image_id"], set()).add(category_map[annotation["category_id"]])
        for image in sorted(data["images"], key=lambda image: image["id"]):
            rows.append((str(image["id"]), root / image["file_name"], sorted(labels.get(image["id"], []))))
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists() or output.with_suffix(".metadata.json").exists():
        raise FileExistsError("manifest already exists; use a new output path")
    if not rows:
        raise ValueError("no real images found")
    # Prefix subsets now sample the whole dataset, instead of taking only the
    # first ImageNet class directories. The manifest order stays fixed across runs.
    random.Random(seed).shuffle(rows)
    temporary = None
    try:
        with tempfile.NamedTemporaryFile(mode="w", newline="", encoding="utf-8", dir=output.parent,
                                         prefix=output.name + ".", suffix=".tmp", delete=False) as stream:
            temporary = Path(stream.name)
            writer = csv.DictWriter(stream, fieldnames=["sample_id", "image_path", "image_sha256", "labels", "split"])
            writer.writeheader()
            for sample, path, labels in rows:
                writer.writerow(dict(sample_id=sample, image_path=path.as_posix(), image_sha256=file_hash(path),
                                     labels=json.dumps(labels), split=split))
        os.replace(temporary, output)
    finally:
        if temporary is not None:
            temporary.unlink(missing_ok=True)
    write_json(output.with_suffix(".metadata.json"), {"dataset": kind, "samples": len(rows),
        "task": "multilabel object-category presence" if kind == "coco" else "single-label classification",
        "classes": classes, "numClasses": len(classes), "manifestSha256": file_hash(output),
        "sampleOrder": "seeded permutation of the complete real-image listing", "seed": seed,
        "annotationSha256": file_hash(annotations) if annotations else None,
        "redistributesImages": False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--dataset", choices=["coco", "imagenet"], required=True)
    parser.add_argument("--image-root", type=Path, required=True)
    parser.add_argument("--annotations", type=Path)
    parser.add_argument("--split", default="train")
    parser.add_argument("--seed", type=int, default=20260904)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    prepare(args.dataset, args.image_root, args.output, args.annotations, args.split, args.seed)


if __name__ == "__main__":
    main()
