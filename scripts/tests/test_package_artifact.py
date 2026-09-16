"""Frozen archives contain committed bytes, excluding ignored local source."""
import hashlib
import json
import subprocess
import zipfile

import package_artifact
import paper_common


def test_clean_archive_matches_commit(tmp_path, monkeypatch):
    monkeypatch.setattr(package_artifact, "ROOT", tmp_path)
    monkeypatch.setattr(paper_common, "ROOT", tmp_path)
    git = ["git", "-c", f"safe.directory={tmp_path.as_posix()}"]
    def run(*args):
        return subprocess.run([*git, *args], cwd=tmp_path, check=True, capture_output=True).stdout
    run("init")
    (tmp_path / ".gitattributes").write_text("* text eol=lf\n")
    (tmp_path / ".gitignore").write_text("ignored.py\n*.zip*\n")
    (tmp_path / "README.md").write_bytes(b"committed source\r\n")
    (tmp_path / "scripts").mkdir()
    (tmp_path / "scripts/ignored.py").write_text("excluded local helper")
    run("add", ".")
    run("-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "Test fixture")
    package_artifact.package(tmp_path / "source.zip")
    with zipfile.ZipFile(tmp_path / "source.zip") as archive:
        manifest = json.loads(archive.read("artifact-provenance.json"))
        assert manifest["sourceClean"] is True
        assert archive.read("README.md") == run("show", "HEAD:README.md") == b"committed source\n"
        assert "scripts/ignored.py" not in archive.namelist()
        for name, expected in manifest["files"].items():
            assert hashlib.sha256(archive.read(name)).hexdigest() == expected
