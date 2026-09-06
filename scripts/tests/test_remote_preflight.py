import csv
import json

import pytest

from scripts.validate_manifests import main, rows_for


def write_manifest(path, image_path):
    with path.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=["sample_id", "image_path", "image_sha256", "split"])
        writer.writeheader()
        writer.writerow({"sample_id": "sample-1", "image_path": str(image_path), "image_sha256": "abc", "split": "train"})


def test_manifest_preflight_accepts_configured_v1_v2_sources(tmp_path, capsys):
    image = tmp_path / "image.bin"
    image.write_bytes(b"fixture")
    v1, v2 = tmp_path / "v1.csv", tmp_path / "v2.csv"
    write_manifest(v1, image)
    write_manifest(v2, image)
    config = tmp_path / "datasets.json"
    config.write_text(json.dumps({"fixture": {"manifestV1": str(v1), "manifestV2": str(v2),
                                               "samplesV1": 1, "samplesV2": 1, "split": "train"}}))

    main(["--config", str(config)])

    report = json.loads(capsys.readouterr().out)
    assert report["fixture"]["V1"]["availableRows"] == 1
    assert report["fixture"]["V2"]["requiredRows"] == 1


def test_manifest_preflight_rejects_missing_image(tmp_path):
    manifest = tmp_path / "missing-image.csv"
    write_manifest(manifest, tmp_path / "not-present.bin")

    with pytest.raises(ValueError, match="image paths do not exist"):
        rows_for(manifest, "train")
