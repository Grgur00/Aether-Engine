import argparse
import csv
import hashlib
import json
import re
from collections import Counter, defaultdict
from pathlib import Path


IMAGE_EXTENSIONS = {".bmp", ".jpg", ".jpeg", ".png", ".tif", ".tiff"}
MASK_HINTS = {"mask", "masks", "label", "labels", "annotation", "annotations", "seg", "segmentation"}
VALID_MASK_MODES = {"1", "L", "P", "I", "I;16"}
RGB_MASK_MODES = {"RGB", "RGBA", "CMYK", "HSV"}


def main(argv=None):
    args = parse_args(argv)
    rows, summary = build_manifest(args)
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    write_manifest(output, rows)
    summary["manifestPath"] = str(output)
    print(json.dumps(summary, indent=2, sort_keys=True))
    if not summary["passed"]:
        raise SystemExit(1)


def parse_args(argv=None):
    parser = argparse.ArgumentParser(description="Build and validate an OCT5K image/mask manifest.")
    parser.add_argument("--input-root", required=True)
    parser.add_argument("--output", default="/kaggle/working/aether-oct5k/manifest/oct5k-manifest.csv")
    parser.add_argument("--split", default="train")
    parser.add_argument("--image-size", type=int, default=320)
    return parser.parse_args(argv)


def build_manifest(args):
    root = Path(args.input_root).resolve()
    if not root.is_dir():
        raise ValueError(f"input-root is not an existing directory: {root}")
    candidate_paths = sorted(path for path in root.rglob("*") if path.is_file() and path.suffix.lower() in IMAGE_EXTENSIONS)
    boundary_tables = [path for path in candidate_paths if is_boundary_table_path(path)]
    paths = [path for path in candidate_paths if path not in boundary_tables]
    images, masks = partition_images_and_masks(paths)
    image_by_key = group_by_pair_key(images)
    mask_by_key = group_by_pair_key(masks)
    rows = []
    duplicate_sample_ids = []
    mask_values = Counter()
    diseases = Counter()
    errors = []
    for key in sorted(set(image_by_key) & set(mask_by_key)):
        image_candidates = image_by_key[key]
        mask_candidates = mask_by_key[key]
        if len(image_candidates) != 1 or len(mask_candidates) != 1:
            errors.append({
                "pairKey": key,
                "imageCandidates": [str(path) for path in image_candidates],
                "maskCandidates": [str(path) for path in mask_candidates],
            })
            continue
        image_path = image_candidates[0]
        mask_path = mask_candidates[0]
        validation = validate_pair(image_path, mask_path, args.image_size)
        mask_values.update(validation["maskValues"])
        disease = disease_label(root, image_path)
        diseases[disease] += 1
        image_sha = file_sha256(image_path)
        mask_sha = file_sha256(mask_path)
        sample_id = sample_id_from_path(root, image_path)
        rows.append({
            "sample_id": sample_id,
            "image_path": str(image_path),
            "mask_path": str(mask_path),
            "disease": disease,
            "split": args.split,
            "image_sha256": image_sha,
            "mask_sha256": mask_sha,
            "source_identity": hashlib.sha256(f"{image_sha}:{mask_sha}".encode("utf-8")).hexdigest(),
        })
    sample_ids = Counter(row["sample_id"] for row in rows)
    duplicate_sample_ids = sorted(sample_id for sample_id, count in sample_ids.items() if count > 1)
    unpaired_images = sorted(set(image_by_key) - set(mask_by_key))
    unpaired_masks = sorted(set(mask_by_key) - set(image_by_key))
    passed = not errors and not duplicate_sample_ids and not unpaired_images and not unpaired_masks and bool(rows)
    summary = {
        "passed": passed,
        "inputRoot": str(root),
        "totalFiles": len(candidate_paths),
        "ignoredBoundaryTables": len(boundary_tables),
        "pairedSamples": len(rows),
        "unpairedImages": len(unpaired_images),
        "unpairedMasks": len(unpaired_masks),
        "duplicateSampleIds": duplicate_sample_ids,
        "ambiguousPairs": errors,
        "classValueDistribution": {str(key): value for key, value in sorted(mask_values.items())},
        "diseaseDistribution": dict(sorted(diseases.items())),
    }
    return rows, summary


def partition_images_and_masks(paths):
    images = []
    masks = []
    for path in paths:
        if is_mask_path(path):
            if not is_rgb_visualization(path):
                masks.append(path)
        else:
            images.append(path)
    return images, masks


def is_mask_path(path):
    tokens = {token.lower() for part in path.parts for token in re.split(r"[^A-Za-z0-9]+", part) if token}
    stem = path.stem.lower()
    return bool(tokens & MASK_HINTS) or any(stem.endswith(suffix) for suffix in ("_mask", "-mask", "_label", "-label", "_seg", "-seg"))


def is_rgb_visualization(path):
    return any(part.lower().endswith("_rgb") for part in path.parts)


def is_boundary_table_path(path):
    return any(part.lower().startswith("boundaries") for part in path.parts)


def group_by_pair_key(paths):
    grouped = defaultdict(list)
    for path in paths:
        grouped[pair_key(path)].append(path)
    return grouped


def pair_key(path):
    parts = path.parts
    for index, part in enumerate(parts):
        if part.lower() == "grading":
            relative = Path(*parts[index:]).with_suffix("")
            return "/".join(re.sub(r"[^a-z0-9]+", "", value.lower()) for value in relative.parts)
    stem = path.stem.lower()
    stem = re.sub(r"([_-]?(mask|label|annotation|segmentation|seg))+$", "", stem)
    return re.sub(r"[^a-z0-9]+", "", stem)


def validate_pair(image_path, mask_path, image_size):
    try:
        from PIL import Image
    except ImportError as error:
        raise RuntimeError("OCT5K manifest building requires Pillow") from error
    bilinear = getattr(getattr(Image, "Resampling", Image), "BILINEAR")
    nearest = getattr(getattr(Image, "Resampling", Image), "NEAREST")
    with Image.open(image_path) as image:
        image.convert("L").resize((image_size, image_size), bilinear)
    with Image.open(mask_path) as mask:
        if mask.mode in RGB_MASK_MODES:
            raise ValueError(f"mask appears to be an RGB visualization, not semantic labels: {mask_path}")
        if mask.mode not in VALID_MASK_MODES:
            raise ValueError(f"mask mode is not supported for semantic labels: {mask.mode} at {mask_path}")
        values = mask.resize((image_size, image_size), nearest).getdata()
        return {"maskValues": Counter(values)}


def disease_label(root, image_path):
    relative = image_path.relative_to(root)
    return relative.parts[0] if len(relative.parts) > 1 else "unknown"


def sample_id_from_path(root, image_path):
    return re.sub(r"[^A-Za-z0-9_.-]+", "_", str(image_path.relative_to(root).with_suffix("")))


def file_sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_manifest(path, rows):
    fieldnames = ["sample_id", "image_path", "mask_path", "disease", "split", "image_sha256", "mask_sha256", "source_identity"]
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)


if __name__ == "__main__":
    main()
