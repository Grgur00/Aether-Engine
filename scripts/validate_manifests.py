"""Validate paper dataset manifests before starting a remote campaign."""
import argparse
import csv
import json
from pathlib import Path


REQUIRED = {"sample_id", "image_path", "image_sha256"}


def rows_for(path, split):
    with path.open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        fields = set(reader.fieldnames or [])
        missing = REQUIRED - fields
        if missing:
            raise ValueError(f"{path}: missing columns {sorted(missing)}")
        rows = [row for row in reader if not row.get("split") or row["split"] == split]
    if not rows:
        raise ValueError(f"{path}: no rows for split {split!r}")
    identifiers = [row["sample_id"] for row in rows]
    if any(not value for value in identifiers) or len(identifiers) != len(set(identifiers)):
        raise ValueError(f"{path}: sample_id values must be non-empty and unique")
    missing_images = [row["image_path"] for row in rows if not Path(row["image_path"]).is_file()]
    if missing_images:
        raise ValueError(f"{path}: {len(missing_images)} image paths do not exist (first: {missing_images[0]})")
    return len(rows)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--datasets", help="Comma-separated subset; default validates every configured dataset")
    args = parser.parse_args(argv)
    config = json.loads(args.config.read_text(encoding="utf-8"))
    names = args.datasets.split(",") if args.datasets else list(config)
    report = {}
    for name in names:
        spec = config[name]
        split = spec.get("split", "train")
        report[name] = {}
        for version in ("V1", "V2"):
            path = Path(spec[f"manifest{version}"])
            if not path.is_file():
                raise ValueError(f"{name} {version}: manifest does not exist: {path}")
            actual = rows_for(path, split)
            required = spec[f"samples{version}"]
            if actual < required:
                raise ValueError(f"{name} {version}: {actual} rows available, but {required} required")
            report[name][version] = {"manifest": str(path), "availableRows": actual, "requiredRows": required}
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
