"""Execute source selection and confirmatory guards without contacting Kaggle or GitHub."""
import hashlib
import json
import zipfile
from pathlib import Path
from types import SimpleNamespace

import pytest


def cells():
    root = Path(__file__).resolve().parents[2]
    return json.loads((root / "kaggle/aether_paper.ipynb").read_text(encoding="utf-8"))["cells"]


def test_archive_selection_verifies_source_and_rejects_different_archive(tmp_path):
    data = b"explicit source fixture"
    manifest = {"files": {"fixture.txt": hashlib.sha256(data).hexdigest()}, "sourceClean": False}
    archive = tmp_path / "source.zip"
    with zipfile.ZipFile(archive, "w") as bundle:
        bundle.writestr("artifact-provenance.json", json.dumps(manifest))
        bundle.writestr("fixture.txt", data)
    setup = ''.join(cells()[2]["source"]).split("subprocess.run(['bash'")[0]
    namespace = {"SOURCE_ARCHIVE": archive, "REPO": tmp_path / "repo", "Path": Path, "json": json}
    exec(setup, namespace)
    assert namespace["provenance"]["sourceClean"] is False
    (tmp_path / "repo/fixture.txt").write_bytes(b"changed")
    with pytest.raises(ValueError, match="differs"):
        exec(setup, namespace)


def test_git_selection_defines_provenance_used_by_confirmatory_guard(tmp_path):
    calls = []
    def run(command, **kwargs):
        calls.append(command)
        return SimpleNamespace(stdout=" M fixture.py\n")
    setup = ''.join(cells()[2]["source"]).split("subprocess.run(['bash'")[0]
    namespace = {"SOURCE_ARCHIVE": None, "REPO": tmp_path / "repo", "Path": Path, "json": json,
                 "subprocess": SimpleNamespace(run=run), "REPOSITORY": "TEST FIXTURE", "GIT_REF": "frozen-fixture"}
    exec(setup, namespace)
    assert namespace["provenance"]["sourceClean"] is False
    namespace["RUN_CONFIRMATORY"] = True
    with pytest.raises(RuntimeError, match="clean, committed"):
        exec(''.join(cells()[10]["source"]), namespace)
