import json
import pytest
from h4 import prepare_storage_revision as revision


def test_existing_candidate_output_is_preserved(tmp_path):
    with pytest.raises(ValueError, match="new output"):
        revision.prepare(tmp_path / "missing.bin", tmp_path / "missing.patch", tmp_path)


def test_patch_cannot_declare_unrelated_h2_changes(tmp_path, monkeypatch):
    bundle, patch = tmp_path / "bundle.bin", tmp_path / "candidate.patch"
    bundle.write_bytes(b"fixture")
    patch.write_text("fixture")
    monkeypatch.setattr(revision, "digest", lambda path: revision.BASELINE_SHA256 if path == bundle else "patch")
    patch.with_suffix(".json").write_text(json.dumps({"patch_sha256": "patch",
        "baseline_transport_sha256": revision.BASELINE_SHA256,
        "changed_sources": {"scripts/h2.py": {}}}))
    with pytest.raises(ValueError, match="scope mismatch"):
        revision.prepare(bundle, patch, tmp_path / "output")
    assert not (tmp_path / "output").exists()
