import json
from types import SimpleNamespace

import pytest

import cache_workspace as workspace


def test_success_removes_only_owned_store_and_preserves_reports_and_logs(tmp_path):
    reports, scratch = tmp_path / "reports", tmp_path / "scratch"
    scratch.mkdir()
    unrelated = scratch / "user-data"
    unrelated.mkdir()
    (unrelated / "keep.txt").write_text("keep")
    with workspace.cache_workspace(reports, scratch_root=scratch) as owned:
        assert owned.parent == scratch
        (owned / "payload.bin").write_bytes(b"fixture")
        (owned / "java.stderr.log").write_text("fixture log")
        (owned / "java.compaction-1.json").write_text('{"backgroundCompaction":{"state":"IDLE"}}')
    assert not owned.exists()
    assert (unrelated / "keep.txt").read_text() == "keep"
    assert (reports / "cache-workspace-java.stderr.log").read_text() == "fixture log"
    assert json.loads((reports / "cache-workspace-java.compaction-1.json").read_text())["backgroundCompaction"]["state"] == "IDLE"
    assert json.loads((reports / "cache-workspace.json").read_text())["status"] == "complete"


def test_failed_attempt_is_retained_and_addressable(tmp_path):
    with pytest.raises(RuntimeError, match="injected"):
        with workspace.cache_workspace(tmp_path) as owned:
            (owned / "partial.bin").write_bytes(b"fixture")
            raise RuntimeError("injected failure")
    report = json.loads((tmp_path / "cache-workspace.json").read_text())
    assert report["status"] == "interrupted" and report["retained"] is True
    assert report["ownedDirectory"] == str(owned)
    assert (owned / "partial.bin").read_bytes() == b"fixture"


def test_low_capacity_fails_before_allocating_a_store(tmp_path, monkeypatch):
    monkeypatch.setattr(workspace.shutil, "disk_usage", lambda _: SimpleNamespace(free=1))
    with pytest.raises(RuntimeError, match="larger --scratch-root"):
        with workspace.cache_workspace(tmp_path, payload_bytes=100):
            pytest.fail("low-capacity workspace was used")
    assert not list(tmp_path.glob("aether-store-*"))
    assert json.loads((tmp_path / "cache-workspace.json").read_text())["status"] == "insufficient-capacity"


def test_explicit_retention(tmp_path):
    with workspace.cache_workspace(tmp_path, retain=True) as owned:
        pass
    assert owned.exists()
