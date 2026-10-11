import gzip
import hashlib
import io
import json
from pathlib import Path

import numpy as np
import pytest

from h4 import prepare_pcam_manifest as pcam
from h4.common import digest


def test_transform_exact_payload_and_byte_order():
    image = np.arange(96 * 96 * 3, dtype=np.uint8).reshape(96, 96, 3)
    result = pcam.preprocess(image)
    assert result.shape == (3, 96, 96)
    assert result.flags.c_contiguous
    assert result.dtype.str == "<f4"
    assert result.nbytes == 110592
    np.testing.assert_array_equal(result, (image.astype(np.float32) / np.float32(255)).transpose(2, 0, 1))
    assert result.tobytes() == pcam.preprocess(image).tobytes()
    with pytest.raises(ValueError):
        pcam.preprocess(image.astype(np.float32))


def test_subsets_are_nested_reproducible_and_within_budget():
    selected, counts = pcam.nested_subsets(262144, 2 * 1024 ** 3, [.25, .5, 1, 2, 4], 20261011)
    again, other = pcam.nested_subsets(262144, 2 * 1024 ** 3, [.25, .5, 1, 2, 4], 20261011)
    assert counts == [4854, 9709, 19418, 38836, 77672]
    assert counts == other
    np.testing.assert_array_equal(selected, again)
    assert len(set(selected)) == counts[-1]
    assert all(count * 110592 <= 2 * 1024 ** 3 * ratio for count, ratio in zip(counts, [.25, .5, 1, 2, 4]))
    with pytest.raises(ValueError):
        pcam.nested_subsets(10, 2 * 1024 ** 3, [4], 1)


@pytest.mark.parametrize("content,size,checksum", [
    (b"wrong", 4, hashlib.md5(b"good").hexdigest()),
    (b"bad!", 4, hashlib.md5(b"good").hexdigest()),
])
def test_invalid_download_never_publishes(tmp_path, monkeypatch, content, size, checksum):
    monkeypatch.setattr(pcam.urllib.request, "urlopen", lambda *args, **kwargs: io.BytesIO(content))
    with pytest.raises(ValueError):
        pcam.download_file(tmp_path / "source.gz", "https://example.test/source", size, checksum)
    assert not list(tmp_path.iterdir())


def test_verified_download_and_bounded_extraction(tmp_path, monkeypatch):
    content = gzip.compress(b"test data", mtime=0)
    monkeypatch.setattr(pcam.urllib.request, "urlopen", lambda *args, **kwargs: io.BytesIO(content))
    path = tmp_path / "source.h5.gz"
    pcam.download_file(path, "https://example.test/source", len(content), hashlib.md5(content).hexdigest())
    with pytest.raises(ValueError, match="bound"):
        pcam.extract_archive(path, maximum=1)
    assert list(tmp_path.iterdir()) == [path]
    report = pcam.extract_archive(path)
    assert report["sha256"] == hashlib.sha256(b"test data").hexdigest()
    assert pcam.extract_archive(path) == report
    path.with_suffix("").write_bytes(b"changed")
    with pytest.raises(ValueError, match="differs"):
        pcam.extract_archive(path)


def test_capacity_failure_before_network(tmp_path, monkeypatch):
    monkeypatch.setattr(pcam.shutil, "disk_usage", lambda _: type("Usage", (), {"free": 1})())
    monkeypatch.setattr(pcam, "download_file", lambda *args: pytest.fail("network must not start"))
    with pytest.raises(RuntimeError, match="free bytes"):
        pcam.acquire(tmp_path)


def test_partial_download_resumes_only_with_matching_range(tmp_path, monkeypatch):
    content = b"abcdef"
    path = tmp_path / "source.gz"
    partial = path.with_name(path.name + ".partial")
    partial.write_bytes(content[:2])

    class Response(io.BytesIO):
        status = 206
        headers = {"Content-Range": "bytes 2-5/6"}

    def open_range(request, **kwargs):
        assert request.get_header("Range") == "bytes=2-5"
        return Response(content[2:])

    monkeypatch.setattr(pcam.urllib.request, "urlopen", open_range)
    pcam.download_file(path, "https://example.test/source", 6, hashlib.md5(content).hexdigest())
    assert path.read_bytes() == content
    assert not partial.exists()


def test_ignored_range_cannot_duplicate_download_bytes(tmp_path, monkeypatch):
    path = tmp_path / "source.gz"
    path.with_name(path.name + ".partial").write_bytes(b"ab")

    class Response(io.BytesIO):
        status = 200
        headers = {}

    monkeypatch.setattr(pcam.urllib.request, "urlopen", lambda *args, **kwargs: Response(b"abcdef"))
    with pytest.raises(ValueError, match="range request"):
        pcam.download_file(path, "https://example.test/source", 6, hashlib.md5(b"abcdef").hexdigest())
    assert not list(tmp_path.iterdir())


def test_deadline_preserves_partial_without_publishing(tmp_path):
    path = tmp_path / "source.gz"
    partial = path.with_name(path.name + ".partial")
    partial.write_bytes(b"ab")
    ticks = iter([0, 2])
    with pytest.MonkeyPatch.context() as patch:
        patch.setattr(pcam.time, "monotonic", lambda: next(ticks))
        with pytest.raises(TimeoutError, match="deadline"):
            pcam.download_file(path, "https://example.test/source", 6,
                               hashlib.md5(b"abcdef").hexdigest(), timeout_seconds=1)
    assert partial.read_bytes() == b"ab"
    assert not path.exists()


def fixture_source(tmp_path, monkeypatch):
    h5py = pytest.importorskip("h5py")
    official = {}
    for kind in ("x", "y"):
        path = tmp_path / f"camelyonpatch_level_2_split_train_{kind}.h5"
        with h5py.File(path, "w") as file:
            data = np.zeros((16, 96, 96, 3), dtype=np.uint8) if kind == "x" else np.arange(16, dtype=np.uint8).reshape(16, 1, 1, 1) % 2
            file.create_dataset(kind, data=data)
        compressed = gzip.compress(path.read_bytes(), mtime=0)
        archive = path.with_suffix(".h5.gz")
        archive.write_bytes(compressed)
        official[archive.name] = (len(compressed), hashlib.md5(compressed).hexdigest())
    monkeypatch.setattr(pcam, "OFFICIAL", official)
    monkeypatch.setattr(pcam, "SAMPLES", 16)
    protocol = json.loads(Path(pcam.__file__).with_name("protocol.json").read_text())
    protocol["memory_limit_bytes"] = 4 * 110592
    path = tmp_path / "protocol.json"
    path.write_text(json.dumps(protocol))
    return path


def test_manifest_preserves_ids_labels_hashes_and_requests(tmp_path, monkeypatch):
    protocol = fixture_source(tmp_path, monkeypatch)
    output = tmp_path / "manifest"
    result = pcam.prepare(tmp_path, output, protocol)
    rows = json.loads((output / "sample_hashes.json").read_text())
    assert len(rows) == 16
    assert len({row["artifact_id"] for row in rows}) == 16
    assert all(row["label"] == row["sample_id"] % 2 for row in rows)
    assert all(row["artifact_sha256"] == hashlib.sha256(bytes(110592)).hexdigest() for row in rows)
    for before, after in zip(result["conditions"], result["conditions"][1:]):
        assert after["sequential_ids"][:before["samples"]] == before["sequential_ids"]
    for condition in result["conditions"]:
        assert sorted(condition["sequential_ids"]) == sorted(condition["shuffled_ids"])
    checksums = json.loads((output / "sha256.json").read_text())
    assert all(digest(output / name) == expected for name, expected in checksums.items())
    with pytest.raises(ValueError, match="preserve"):
        pcam.prepare(tmp_path, output, protocol)


def test_manifest_rejects_extracted_source_drift(tmp_path, monkeypatch):
    protocol = fixture_source(tmp_path, monkeypatch)
    source = tmp_path / "camelyonpatch_level_2_split_train_x.h5"
    source.write_bytes(source.read_bytes()[:-1] + b"x")
    with pytest.raises(ValueError, match="differs"):
        pcam.prepare(tmp_path, tmp_path / "manifest", protocol)


def test_status_never_accepts_partial_archives(tmp_path, monkeypatch):
    fixture_source(tmp_path, monkeypatch)
    name = "camelyonpatch_level_2_split_train_x.h5.gz"
    archive = tmp_path / name
    archive.rename(tmp_path / (name + ".partial"))
    report = pcam.source_status(tmp_path)
    assert report["all_official_archives_verified"] is False
    assert report["files"][name]["official_archive_verified"] is False
    assert report["files"][name]["partial_bytes"] > 0
    assert report["files"]["camelyonpatch_level_2_split_train_y.h5.gz"]["official_archive_verified"] is True
    assert report["primary_h4_eligible"] is False


def test_runner_binding_verifies_source_transform_and_live_preprocessing(tmp_path, monkeypatch):
    from h4.inputs import bind_inputs, sample_stream
    protocol = fixture_source(tmp_path, monkeypatch)
    output = tmp_path / "manifest"
    pcam.prepare(tmp_path, output, protocol)
    binding = bind_inputs(tmp_path, output, protocol)
    assert binding["source_verified"]
    records = list(sample_stream(tmp_path, binding["rows"]))
    assert len(records) == 16
    assert all(hashlib.sha256(payload).hexdigest() == row["artifact_sha256"] for row, payload in records)
    (tmp_path / "camelyonpatch_level_2_split_train_x.h5").write_bytes(b"changed")
    with pytest.raises(ValueError, match="actual PCam source"):
        bind_inputs(tmp_path, output, protocol)


def test_runner_binding_rejects_rechecksummed_order_or_identity_drift(tmp_path, monkeypatch):
    from h4.inputs import bind_inputs
    protocol = fixture_source(tmp_path, monkeypatch)
    output = tmp_path / "manifest"
    pcam.prepare(tmp_path, output, protocol)
    manifest_path = output / "manifest.json"
    manifest = json.loads(manifest_path.read_text())
    manifest["conditions"][-1]["shuffled_ids"] = list(reversed(manifest["conditions"][-1]["shuffled_ids"]))
    manifest_path.write_text(json.dumps(manifest))
    inventory_path = output / "sha256.json"
    inventory = json.loads(inventory_path.read_text())
    inventory["manifest.json"] = digest(manifest_path)
    inventory_path.write_text(json.dumps(inventory))
    with pytest.raises(ValueError, match="workload order"):
        bind_inputs(tmp_path, output, protocol)
