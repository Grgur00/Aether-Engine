"""Exercise real ZIP contents and notebook finalization without running Kaggle."""
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

import pytest

from kaggle_results import bundle_results, include_notebook_log


def verify(archive_path):
    with zipfile.ZipFile(archive_path) as archive:
        assert archive.testzip() is None
        for line in archive.read("SHA256SUMS").decode().splitlines():
            expected, relative = line.split("  ", 1)
            assert hashlib.sha256(archive.read(relative)).hexdigest() == expected
        return {name: archive.read(name) for name in archive.namelist()}


def test_bundle_contains_nested_results_runtime_and_checksums_only(tmp_path):
    results = tmp_path / "results"
    (results / "nested").mkdir(parents=True)
    (results / "nested/report.json").write_text('{"passed": true}')
    (results / "nested/SHA256SUMS").write_text("existing nested manifest")
    (results / "run.log").write_text("experiment log")
    runtime = tmp_path / "runtime.json"
    runtime.write_text('{"java": "21"}')
    (tmp_path / "source.py").write_text("not a result")
    archive = bundle_results(results, tmp_path / "results.zip", runtime)
    files = verify(archive)
    assert set(files) == {"nested/report.json", "nested/SHA256SUMS", "run.log", "aether-paper-runtime.json", "SHA256SUMS"}
    (results / "run.log").write_text("updated log")
    bundle_results(results, archive, runtime)
    assert verify(archive)["run.log"] == b"updated log"


def test_failed_packaging_keeps_previous_archive_and_results(tmp_path, monkeypatch):
    results = tmp_path / "results"
    results.mkdir()
    (results / "report.json").write_text("evidence")
    archive = bundle_results(results, tmp_path / "results.zip")
    previous = archive.read_bytes()
    def fail(*args, **kwargs):
        raise OSError("disk full")
    monkeypatch.setattr(zipfile.ZipFile, "write", fail)
    with pytest.raises(OSError, match="disk full"):
        bundle_results(results, archive)
    assert archive.read_bytes() == previous
    assert (results / "report.json").read_text() == "evidence"
    assert not archive.with_suffix(".zip.tmp").exists()


def test_platform_log_is_folded_into_single_archive_with_updated_checksums(tmp_path):
    results = tmp_path / "results"
    results.mkdir()
    (results / "report.json").write_text("evidence")
    archive = bundle_results(results, tmp_path / "results.zip")
    log = tmp_path / "kernel.log"
    log.write_text("Kaggle conversion log")
    include_notebook_log(archive, log)
    assert verify(archive)["kaggle-notebook.log"] == b"Kaggle conversion log"
    assert not log.exists()
    log.write_text("updated log")
    include_notebook_log(archive, log)
    assert verify(archive)["kaggle-notebook.log"] == b"updated log"
    with zipfile.ZipFile(archive) as bundled:
        assert len(bundled.namelist()) == len(set(bundled.namelist()))


@pytest.mark.parametrize("failure", [None, "setup", "experiment"])
def test_notebook_bundles_success_and_failures_with_logs(tmp_path, monkeypatch, failure):
    working = tmp_path / "working"
    working.mkdir()
    attached = tmp_path / "input/source"
    attached.mkdir(parents=True)
    provenance = json.dumps({"files": {"fixture": hashlib.sha256(b"source").hexdigest()}})
    with zipfile.ZipFile(attached / "aether-paper-artifact.zip", "w") as source:
        source.writestr("artifact-provenance.json", provenance)
        source.writestr("fixture", b"source")
    class Process:
        def __init__(self, command, **kwargs):
            self.stdout = iter(["stdout and stderr captured\n"])
            self.code = 0
            if command[0] == "bash":
                (working / "aether-paper-runtime.json").write_text(json.dumps({"javaHome": str(tmp_path)}))
                self.code = 7 if failure == "setup" else 0
            elif "scripts/reproduce.py" in command:
                (working / "aether-results/report.json").write_text('{"fixture": true}')
                self.code = 9 if failure == "experiment" else 0
        def __enter__(self): return self
        def __exit__(self, *args): pass
        def wait(self): return self.code
    monkeypatch.setattr(subprocess, "Popen", Process)
    # Restore environment mutations made by the actual runner.
    for key in ("JAVA_HOME", "PATH", "PYTHONPATH"):
        monkeypatch.setenv(key, "test-environment")
    runner = (Path(__file__).resolve().parents[2] / "kaggle/vscode_run.py").read_text()
    runner = runner.replace("/kaggle/working", working.as_posix()).replace("/kaggle/input", (tmp_path / "input").as_posix())
    namespace = {"bundle_results": bundle_results, "REMOTE_CONFIG": {"sourceDataset": "owner/source", "mode": "profile",
                 "sourceManifestSha256": hashlib.sha256(provenance.encode()).hexdigest()}}
    if failure:
        with pytest.raises(subprocess.CalledProcessError):
            exec(runner, namespace)
    else:
        exec(runner, namespace)
    archive = working / "aether-results-only.zip"
    files = verify(archive)
    assert json.loads(files["run-status.json"])["status"] == ("failed" if failure else "passed")
    assert b"stdout and stderr captured" in files["run.log"]
    if failure: assert b"CalledProcessError" in files["run.log"]
    assert "aether-paper-runtime.json" in files
    assert list(working.iterdir()) == [archive]
