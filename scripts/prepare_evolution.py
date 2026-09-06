"""Create reproducible V1/V2 membership manifests without duplicating source images."""
import argparse
import csv
import random
from pathlib import Path

from paper_common import sha256, write_json
from run_matrix import manifest_rows


def prepare(source, output, *, v1_size=1170, v2_size=1505, reusable=1003, split="train", seed=20260904):
    source, output = Path(source).resolve(), Path(output).resolve()
    fields, rows = manifest_rows(source, split)
    if not 0 <= reusable <= min(v1_size, v2_size) or min(v1_size, v2_size) < 1:
        raise ValueError("invalid evolution cardinalities")
    if len(rows) < v1_size + v2_size - reusable:
        raise ValueError("not enough distinct source rows for the requested evolution")
    ids = [row.get("sample_id") for row in rows]
    if len(set(ids)) != len(ids) or not all(ids):
        raise ValueError("source manifest must contain unique nonempty sample IDs")
    rows.sort(key=lambda row: row["sample_id"])
    rng = random.Random(seed)
    rng.shuffle(rows)
    v1 = rows[:v1_size]
    surviving = rng.sample(v1, reusable)
    v2 = surviving + rows[v1_size:v1_size + v2_size - reusable]
    rng.shuffle(v2)
    output.mkdir(parents=True, exist_ok=True)
    for name in ("v1.csv", "v2.csv", "evolution.json"):
        if (output / name).exists():
            raise FileExistsError(output / name)
    for name, selected in (("v1", v1), ("v2", v2)):
        target = output / f"{name}.csv"
        if target.exists():
            raise FileExistsError(target)
        with target.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(stream, fieldnames=fields)
            writer.writeheader()
            for row in selected:
                row = dict(row)
                for field in ("image_path", "mask_path"):
                    if row.get(field):
                        path = Path(row[field])
                        row[field] = str(path if path.is_absolute() else (source.parent / path).resolve())
                writer.writerow(row)
    write_json(output / "evolution.json", {"samplesV1": v1_size, "samplesV2": v2_size, "expectedReusable": reusable,
        "initialMissing": v2_size - reusable, "removedFromV1": v1_size - reusable,
        "seed": seed, "sourceManifestSha256": sha256(source),
        "v1Sha256": sha256(output / "v1.csv"), "v2Sha256": sha256(output / "v2.csv")})


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--v1-size", type=int, default=1170)
    parser.add_argument("--v2-size", type=int, default=1505)
    parser.add_argument("--reusable", type=int, default=1003)
    parser.add_argument("--split", default="train")
    parser.add_argument("--seed", type=int, default=20260904)
    args = parser.parse_args()
    prepare(args.manifest, args.output, v1_size=args.v1_size, v2_size=args.v2_size, reusable=args.reusable, split=args.split, seed=args.seed)
