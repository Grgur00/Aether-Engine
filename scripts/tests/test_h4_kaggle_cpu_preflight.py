"""CPU deployment guards; these tests do not submit jobs or invent runtime results."""
import json
import zipfile

import pytest

from h4 import kaggle_cpu_preflight as deployment


def test_notebook_is_private_cpu_only_and_pins_just_its_source(tmp_path, monkeypatch):
    monkeypatch.setattr(deployment, "WORK", tmp_path)
    config = {"kernel": "example/h4-cpu", "transport": "source.bin", "sha256": "a" * 64}
    deployment.notebook(config, "example/h4-source/7")
    metadata = json.loads((tmp_path / "notebook/kernel-metadata.json").read_text())
    assert metadata["is_private"] is True
    assert metadata["enable_gpu"] is False
    assert metadata["dataset_sources"] == ["example/h4-source/7"]
    notebook = json.loads((tmp_path / "notebook/h4.ipynb").read_text())
    code = "".join(notebook["cells"][0]["source"])
    compile(code, "generated-preflight", "exec")
    assert "CUDA_VISIBLE_DEVICES=''" in code
    assert "alias.symlink_to" not in code
    assert "--gib', '1'" in code
    assert "finally:" in code
    assert "archive.testzip()" in code
    assert "primary_h4_eligible=False" in code
    assert "virtualenv==21.14.1" in code
    assert "'-m', 'venv'" not in code


def test_prepared_bundle_is_allowlisted_and_byte_verified(tmp_path, monkeypatch):
    root = tmp_path / "repo"
    root.mkdir()
    for name in ("build.gradle.kts", "gradlew", "gradlew.bat", "jreleaser.yml",
                 "scripts/paper_common.py", "scripts/h4/example.py",
                 "clients/python/client.py", ".kaggle/credentials.json",
                 "examples/aether-persistent-notes/build.gradle.kts",
                 "examples/aether-sample-app/build.gradle.kts",
                 "examples/aether-sample-app/aether-schemas/index.json",
                 "examples/aether-sample-app/src/main/java/Application.java"):
        path = root / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text("fixture", encoding="utf-8")
    monkeypatch.setattr(deployment, "ROOT", root)
    monkeypatch.setattr(deployment, "WORK", root / "build/deployment")
    import paper_common
    monkeypatch.setattr(paper_common, "java_build_sources", lambda _: {"build.gradle.kts": "unused"})
    monkeypatch.setattr(deployment.subprocess, "check_output", lambda *a, **k: "f" * 40)
    deployment.prepare()
    config = json.loads((deployment.WORK / "config.json").read_text())
    path = deployment.WORK / "source" / config["transport"]
    assert deployment.sha(path) == config["sha256"]
    with zipfile.ZipFile(path) as archive:
        assert not any(name.startswith(".kaggle/") for name in archive.namelist())
        assert "examples/aether-persistent-notes/build.gradle.kts" in archive.namelist()
        assert "examples/aether-sample-app/build.gradle.kts" in archive.namelist()
        assert "examples/aether-sample-app/aether-schemas/index.json" in archive.namelist()
        assert "examples/aether-sample-app/src/main/java/Application.java" in archive.namelist()
        inventory = json.loads(archive.read("h4-upload-inventory.json"))
        assert inventory["snapshot_is_worktree"] is True
        assert set(archive.namelist()) == set(inventory["files"]) | {"h4-upload-inventory.json"}
        for name, expected in inventory["files"].items():
            assert deployment.hashlib.sha256(archive.read(name)).hexdigest() == expected
    with pytest.raises(ValueError, match="Preserve the existing deployment"):
        deployment.prepare()
