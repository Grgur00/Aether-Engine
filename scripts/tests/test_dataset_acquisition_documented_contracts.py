"""Offline acquisition contracts; no real network or dataset downloads."""
import io
import zipfile

import pytest

import fetch_coco


def test_download_reuses_any_nonempty_file_without_opening_url(tmp_path, monkeypatch):
    destination = tmp_path / "images.zip"
    destination.write_bytes(b"not actually a zip")
    monkeypatch.setattr(fetch_coco.urllib.request, "urlopen", lambda url: pytest.fail("unexpected download"))
    fetch_coco.download("https://invalid.example/images.zip", destination)
    assert destination.read_bytes() == b"not actually a zip"


def test_download_replaces_partial_only_after_streaming(tmp_path, monkeypatch):
    destination = tmp_path / "images.zip"
    partial = tmp_path / "images.zip.part"
    partial.write_bytes(b"stale")
    monkeypatch.setattr(fetch_coco.urllib.request, "urlopen", lambda url: io.BytesIO(b"complete"))
    fetch_coco.download("https://invalid.example/images.zip", destination)
    assert destination.read_bytes() == b"complete"
    assert not partial.exists()


def test_extract_rejects_traversal_before_extracting_safe_member(tmp_path):
    archive = tmp_path / "images.zip"
    destination = tmp_path / "images"
    with zipfile.ZipFile(archive, "w") as content:
        content.writestr("train2017/safe.txt", "safe")
        content.writestr("../escape.txt", "escape")
    with pytest.raises(ValueError, match="escapes output directory"):
        fetch_coco.extract(archive, destination, destination / "train2017")
    assert not (destination / "train2017/safe.txt").exists()
    assert not (tmp_path / "escape.txt").exists()


def test_extract_skips_existing_directory_without_inventory(tmp_path):
    required = tmp_path / "train2017"
    required.mkdir()
    fetch_coco.extract(tmp_path / "missing.zip", tmp_path, required)
    assert list(required.iterdir()) == []
