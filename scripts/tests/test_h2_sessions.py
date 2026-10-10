import zipfile
import pytest

import h2_sessions as sessions


def test_partitions_cover_all_blocks_without_overlap():
    protocol = {"schedule": [{"block": i} for i in range(24)]}
    rows = [sessions.assignments(protocol, i) for i in range(1, 4)]
    assert list(map(len, rows)) == [9, 9, 6]
    assert [r["block"] for group in rows for r in group] == list(range(24))
    for invalid in (0, 4, True, "2"):
        with pytest.raises(ValueError):
            sessions.assignments(protocol, invalid)


@pytest.mark.parametrize("name", ["../escape", "/escape", "bad\\path"])
def test_evidence_zip_rejects_unsafe_names(tmp_path, name):
    archive = tmp_path / "bad.zip"
    with zipfile.ZipFile(archive, "w") as z:
        entry = zipfile.ZipInfo()
        entry.filename = name
        z.writestr(entry, "bad")
    with pytest.raises(ValueError):
        sessions.extract_evidence(archive, tmp_path / "output")


def test_session_rejects_wrong_protocol_before_reading_blocks(tmp_path):
    class Engine:
        @staticmethod
        def load_campaign(root):
            return {"protocol": {"changed": True}}
    with pytest.raises(ValueError, match="protocol changed"):
        sessions.validate_session(tmp_path, {}, 1, Engine)
