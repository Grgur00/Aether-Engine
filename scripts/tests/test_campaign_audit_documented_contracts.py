"""Artificial audit fixtures, not scientific campaign evidence."""
import hashlib
import json
import zipfile

import pytest

import confirmatory_v1
import preserve_campaign
import submission_gate


def test_fault_validator_accepts_empty_summary_without_proving_coverage(tmp_path, monkeypatch):
    monkeypatch.setattr(submission_gate, "load_campaign", lambda root: {"protocol": {"kind": "process-crash"}})
    (tmp_path / "summary.json").write_text(json.dumps({"trials": []}))
    assert submission_gate.validated_faults(tmp_path) == []


def test_archived_plan_rejects_current_style_epoch_count():
    with pytest.raises(ValueError, match="exactly 24 blocks, 10 epochs"):
        confirmatory_v1.validate_plan({"epochs": 20})


def test_preservation_source_mismatch_leaves_new_output_directory(tmp_path):
    source = tmp_path / "source.zip"
    provenance = {"files": {"fixture.txt": hashlib.sha256(b"expected").hexdigest()}}
    with zipfile.ZipFile(source, "w") as archive:
        archive.writestr("artifact-provenance.json", json.dumps(provenance))
        archive.writestr("fixture.txt", b"changed")
    output = tmp_path / "preserved"
    receipt = tmp_path / "receipt.json"
    with pytest.raises(ValueError, match="source checksum mismatch"):
        preserve_campaign.preserve(tmp_path / "missing-results.zip", source, output, receipt)
    assert output.is_dir()
    assert not receipt.exists()
