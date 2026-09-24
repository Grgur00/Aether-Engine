"""Download official COCO 2017 images and annotations for local manifest creation.

The default train split requires roughly 18 GB for images. Run this only on a
writable filesystem with sufficient quota, such as /kaggle/working on Kaggle.
"""
import argparse
import shutil
import urllib.request
import zipfile
from pathlib import Path


BASE_URL = "https://images.cocodataset.org"
IMAGE_ARCHIVES = {
    "train2017": f"{BASE_URL}/zips/train2017.zip",
    "val2017": f"{BASE_URL}/zips/val2017.zip",
    "test2017": f"{BASE_URL}/zips/test2017.zip",
}
ANNOTATIONS_ARCHIVE = f"{BASE_URL}/annotations/annotations_trainval2017.zip"


def download(url, destination):
    """Download a file, preserving a complete archive from an earlier run."""
    if destination.is_file() and destination.stat().st_size:
        print(f"Using existing archive: {destination}")
        return
    temporary = destination.with_suffix(destination.suffix + ".part")
    temporary.unlink(missing_ok=True)
    print(f"Downloading {url}")
    with urllib.request.urlopen(url) as response, temporary.open("wb") as output:
        shutil.copyfileobj(response, output)
    temporary.replace(destination)


def extract(archive, destination, required):
    if required.is_dir():
        print(f"Using existing extraction: {required}")
        return
    print(f"Extracting {archive.name}")
    root = destination.resolve()
    with zipfile.ZipFile(archive) as content:
        for member in content.infolist():
            target = (destination / member.filename).resolve()
            if not target.is_relative_to(root):
                raise ValueError(f"Archive member escapes output directory: {member.filename}")
        content.extractall(destination)
    if not required.is_dir():
        raise RuntimeError(f"Expected directory was not extracted: {required}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("/kaggle/working/coco"))
    parser.add_argument("--splits", nargs="+", choices=sorted(IMAGE_ARCHIVES), default=["train2017"])
    parser.add_argument("--no-annotations", action="store_true")
    args = parser.parse_args()

    output = args.output.resolve()
    archives = output / "archives"
    images = output / "images"
    archives.mkdir(parents=True, exist_ok=True)
    images.mkdir(parents=True, exist_ok=True)

    for split in args.splits:
        archive = archives / f"{split}.zip"
        download(IMAGE_ARCHIVES[split], archive)
        extract(archive, images, images / split)

    if not args.no_annotations:
        archive = archives / "annotations_trainval2017.zip"
        download(ANNOTATIONS_ARCHIVE, archive)
        extract(archive, output, output / "annotations")

    print("COCO is ready under", output)
    if "train2017" in args.splits and not args.no_annotations:
        print("Create the Aether manifest with:")
        print(
            "python scripts/prepare_vision.py --dataset coco "
            f"--image-root {images / 'train2017'} "
            f"--annotations {output / 'annotations/instances_train2017.json'} "
            "--output /kaggle/working/aether-data/coco-v2.csv"
        )


if __name__ == "__main__":
    main()