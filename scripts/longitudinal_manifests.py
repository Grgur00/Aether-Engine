"""Freeze ordered, nested membership without changing retained artifact identities."""
import argparse
import csv
import hashlib
import json
import random
import time
from pathlib import Path

from paper_common import sha256, write_json

COUNTS = [1200, 1260, 1323, 1389, 1458]


def read_rows(path):
    with Path(path).open(encoding="utf-8", newline="") as stream:
        reader = csv.DictReader(stream)
        return reader.fieldnames, list(reader)


def validate_versions(versions, counts):
    previous = []
    for rows, count in zip(versions, counts, strict=True):
        ids = [row["source_identity"] for row in rows]
        if len(rows) != count or len(set(ids)) != count or rows[:len(previous)] != previous:
            raise ValueError("versions must have exact counts, unique identities and unchanged ordered prefixes")
        if previous and len(rows) <= len(previous):
            raise ValueError("each version must add samples")
        previous = rows


def prepare(source, output, counts=COUNTS, seed=20260926):
    fields, rows = read_rows(source)
    rows = [row for row in rows if row.get("split", "train") == "train"]
    if not counts or counts != sorted(set(counts)) or counts[0] < 1 or counts[-1] > len(rows):
        raise ValueError("invalid nested version sizes")
    for column in ("source_identity", "sample_id"):
        values = [row[column] for row in rows]
        if not all(values) or len(set(values)) != len(values):
            raise ValueError("source contains duplicate or missing identities")
    for row in rows:
        expected = hashlib.sha256(f"{row['image_sha256']}:{row['mask_sha256']}".encode()).hexdigest()
        if row["source_identity"] != expected:
            raise ValueError("source identity does not match image and mask hashes")
    rows.sort(key=lambda row: row["source_identity"])
    random.Random(seed).shuffle(rows)
    versions = [rows[:count] for count in counts]
    validate_versions(versions, counts)
    output = Path(output)
    output.mkdir(parents=True, exist_ok=False)
    for version, selected in enumerate(versions):
        with (output / f"v{version}.csv").open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields, lineterminator="\n")
            writer.writeheader()
            writer.writerows(selected)
    receipt = {"schema": "aether-nested-manifests-v1", "seed": seed,
        "sourceManifestSha256": sha256(source), "sourceSamples": len(rows), "counts": counts,
        "newCounts": [counts[0]] + [b - a for a, b in zip(counts, counts[1:])],
        "removed": 0, "modified": 0, "unused": len(rows) - counts[-1],
        "manifestSha256": [sha256(output / f"v{i}.csv") for i in range(len(counts))]}
    write_json(output / "manifests.json", receipt)
    return receipt


def verify(directory):
    directory = Path(directory)
    receipt = json.loads((directory / "manifests.json").read_text())
    paths = [directory / f"v{i}.csv" for i in range(len(receipt["counts"]))]
    if [sha256(path) for path in paths] != receipt["manifestSha256"]:
        raise ValueError("frozen manifest hashes changed")
    validate_versions([read_rows(path)[1] for path in paths], receipt["counts"])
    return receipt, paths


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    started = time.perf_counter()
    receipt = prepare(args.source, args.output)
    write_json(args.output / "generation-timing.json", {"manifestGenerationMs": (time.perf_counter() - started) * 1000,
        "scope": "one offline campaign manifest generation; source hashing/preprocessing preflight measured separately"})
    print(json.dumps(receipt, indent=2))
