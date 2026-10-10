import csv
import hashlib
import json
import os
from pathlib import Path
import random
import subprocess
import sys

import pytest

import longitudinal_manifests as manifests


def rows(count=5):
    result = []
    for i in range(count):
        image, mask = f"declared-image-{i}", f"declared-mask-{i}"
        result.append(dict(sample_id=str(i), image_sha256=image, mask_sha256=mask,
                           source_identity=hashlib.sha256(f"{image}:{mask}".encode()).hexdigest(),
                           image_path=f"missing/{i}.png", mask_path=f"missing/{i}-mask.png"))
    return result


def write_csv(path, values):
    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=list(values[0]), lineterminator="\n")
        writer.writeheader()
        writer.writerows(values)


def test_order_is_source_order_independent_but_source_receipt_is_not(tmp_path):
    source, reversed_source = tmp_path / "pool.csv", tmp_path / "reversed.csv"
    values = rows()
    write_csv(source, values)
    write_csv(reversed_source, values[::-1])
    before = random.getstate()
    a = manifests.prepare(source, tmp_path / "a", [2, 3], seed=42)
    b = manifests.prepare(reversed_source, tmp_path / "b", [2, 3], seed=42)
    assert random.getstate() == before
    assert a["manifestSha256"] == b["manifestSha256"]
    assert a["sourceManifestSha256"] != b["sourceManifestSha256"]
    assert a["newCounts"] == [2, 1] and a["unused"] == 2
    assert b"\r\n" not in (tmp_path / "a/v0.csv").read_bytes()


def test_split_filter_and_declared_digests_do_not_read_images(tmp_path):
    source = tmp_path / "pool.csv"
    values = rows(4)
    for row in values:
        row["split"] = "train"
    values[-1].update(split="validation", source_identity="bad", sample_id="")
    write_csv(source, values)
    receipt = manifests.prepare(source, tmp_path / "output", [2, 3])
    assert receipt["sourceSamples"] == 3
    assert manifests.verify(tmp_path / "output")[0] == receipt
    assert not (tmp_path / "missing").exists()


@pytest.mark.parametrize("counts", [[], [0], [3, 2], [2, 2], [-1, 2], [6]])
def test_invalid_counts_rejected_before_output_creation(tmp_path, counts):
    source = tmp_path / "pool.csv"
    write_csv(source, rows())
    with pytest.raises(ValueError, match="sizes"):
        manifests.prepare(source, tmp_path / "output", counts)
    assert not (tmp_path / "output").exists()


def test_all_training_rows_checked_even_when_unused(tmp_path):
    source = tmp_path / "pool.csv"
    values = rows()
    values[-1]["source_identity"] = "bad"
    write_csv(source, values)
    with pytest.raises(ValueError, match="image and mask hashes"):
        manifests.prepare(source, tmp_path / "output", [1])
    assert not (tmp_path / "output").exists()


def test_existing_empty_output_is_not_overwritten(tmp_path):
    source, output = tmp_path / "pool.csv", tmp_path / "output"
    write_csv(source, rows())
    output.mkdir()
    with pytest.raises(FileExistsError):
        manifests.prepare(source, output, [2, 3])
    assert list(output.iterdir()) == []


def test_receipt_failure_leaves_csvs_not_transactional_directory(tmp_path, monkeypatch):
    source, output = tmp_path / "pool.csv", tmp_path / "output"
    write_csv(source, rows())

    def fail(*args):
        raise OSError("receipt write failed")

    monkeypatch.setattr(manifests, "write_json", fail)
    with pytest.raises(OSError, match="receipt write"):
        manifests.prepare(source, output, [2, 3])
    assert (output / "v0.csv").is_file() and (output / "v1.csv").is_file()
    assert not (output / "manifests.json").exists()
    with pytest.raises(FileNotFoundError):
        manifests.verify(output)


def test_verify_trusts_receipt_does_not_rederive_source_identity(tmp_path):
    source, output = tmp_path / "pool.csv", tmp_path / "output"
    write_csv(source, rows())
    receipt = manifests.prepare(source, output, [2, 3])
    for version in range(2):
        path = output / f"v{version}.csv"
        _, selected = manifests.read_rows(path)
        selected[0]["source_identity"] = "not-a-content-hash"
        write_csv(path, selected)
        receipt["manifestSha256"][version] = manifests.sha256(path)
    receipt.update(schema="unvalidated", seed=-1, newCounts=[99], unused=-100)
    manifests.write_json(output / "manifests.json", receipt)
    source.unlink()
    (output / "extra.txt").write_text("not inventoried")
    verified, paths = manifests.verify(output)
    assert verified == receipt
    assert len(paths) == 2


def test_verify_rejects_byte_drift_without_updated_receipt(tmp_path):
    source, output = tmp_path / "pool.csv", tmp_path / "output"
    write_csv(source, rows())
    manifests.prepare(source, output, [2, 3])
    with (output / "v0.csv").open("a") as stream:
        stream.write("\n")
    with pytest.raises(ValueError, match="hashes changed"):
        manifests.verify(output)


def test_direct_validator_limits_and_complete_prefix_equality():
    values = rows(3)
    manifests.validate_versions([], [])
    manifests.validate_versions([[], values[:1]], [0, 1])
    with pytest.raises(ValueError):
        manifests.validate_versions([values[:1]], [1, 2])
    with pytest.raises(ValueError, match="add samples"):
        manifests.validate_versions([values[:1], values[:1]], [1, 1])
    changed = [dict(values[0], image_path="different"), values[1]]
    with pytest.raises(ValueError, match="prefixes"):
        manifests.validate_versions([values[:1], changed], [1, 2])
    duplicate_sample_ids = [dict(values[0], sample_id="same"), dict(values[1], sample_id="same")]
    manifests.validate_versions([duplicate_sample_ids], [2])


def test_cli_generation_timing_is_separate_from_membership_receipt(tmp_path):
    source, output = tmp_path / "pool.csv", tmp_path / "output"
    write_csv(source, rows(1458))
    env = dict(os.environ, PYTHONPATH=str(Path(manifests.__file__).parent))
    process = subprocess.run([sys.executable, str(Path(manifests.__file__)),
                              "--source", str(source), "--output", str(output)],
                             capture_output=True, text=True, env=env, timeout=30)
    assert process.returncode == 0, process.stderr
    receipt, _ = manifests.verify(output)
    assert json.loads(process.stdout) == receipt
    assert receipt["counts"] == manifests.COUNTS
    timing = json.loads((output / "generation-timing.json").read_text())
    assert timing["manifestGenerationMs"] >= 0
    assert "preflight measured separately" in timing["scope"]
