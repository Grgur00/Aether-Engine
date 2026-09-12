"""Validate remote submission boundaries without contacting Kaggle."""
import hashlib
import json
import sys
import zipfile
from types import SimpleNamespace

import pytest

import kaggle_remote as remote
import package_artifact


@pytest.mark.parametrize("output", [
    "Kernel push error: Maximum batch GPU session count of 2 reached.\n",
    "The following are not valid dataset sources: ['owner/source']\nKernel version 1 successfully pushed.\n",
    "Unexpected response\n",
])
def test_push_rejects_errors_with_zero_exit_code(tmp_path, monkeypatch, output):
    command = tmp_path / "kaggle.exe"
    command.touch()
    monkeypatch.setattr(remote, "executable", lambda name: command)
    monkeypatch.setattr(remote.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(stdout=output))
    with pytest.raises(RuntimeError, match="did not confirm"):
        remote.cli("kernels", "push", "-p", tmp_path)


def test_push_accepts_confirmed_success(tmp_path, monkeypatch, capsys):
    command = tmp_path / "kaggle.exe"
    command.touch()
    monkeypatch.setattr(remote, "executable", lambda name: command)
    output = "Kernel version 2 successfully pushed.\n"
    monkeypatch.setattr(remote.subprocess, "run", lambda *args, **kwargs: SimpleNamespace(stdout=output))
    remote.cli("kernels", "push", "-p", tmp_path)
    assert capsys.readouterr().out == output


@pytest.fixture
def prepared(tmp_path, monkeypatch):
    monkeypatch.setattr(remote, "WORK", tmp_path)
    def package(path):
        path.parent.mkdir(parents=True, exist_ok=True)
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("artifact-provenance.json", '{"files": {"fixture": "test"}}')
        path.with_suffix(".zip.sha256").write_text(hashlib.sha256(path.read_bytes()).hexdigest())
    monkeypatch.setattr(package_artifact, "package", package)
    remote.prepare(SimpleNamespace(user="Grgur321", mode=None, training_epochs=None, dataset_config=None,
                                   scratch_root=None, dataset_source=None))
    return tmp_path


def test_prepared_notebook_private_and_executable(prepared):
    value = remote.config()
    remote.validate_prepared(value)
    metadata = json.loads((prepared / "notebook/kernel-metadata.json").read_text())
    assert metadata["is_private"] is True
    assert metadata["id"] == "grgur321/aether-engine-vs-code"
    assert metadata["dataset_sources"] == [value["sourceDataset"]]
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    assert all(cell["metadata"]["language"] == "python" for cell in notebook["cells"])
    namespace = {}
    exec("".join(notebook["cells"][0]["source"]), namespace)
    assert namespace["REMOTE_CONFIG"] == value
    compile("".join(notebook["cells"][1]["source"]), "notebook", "exec")
    runner = "".join(notebook["cells"][1]["source"])
    assert "aether-results-only" in runner
    assert "archive.testzip()" in runner
    assert "shutil.rmtree(results)" in runner


def test_five_epochs_persist_in_prepared_notebook(prepared, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "prepare", "--training-epochs", "5"])
    remote.main()
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "prepare"])
    remote.main()
    value = remote.config()
    remote.validate_prepared(value)
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    namespace = {}
    exec("".join(notebook["cells"][0]["source"]), namespace)
    assert namespace["REMOTE_CONFIG"]["trainingEpochs"] == 5


def test_smoke_orchestrator_forwards_training_epochs(tmp_path, monkeypatch):
    import reproduce
    calls = []
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "smoke", "--training-epochs", "5", "--output", str(tmp_path)])
    reproduce.main()
    training = next(command for command in calls if "scripts/artifact_smoke.py" in command)
    assert training[training.index("--training-epochs") + 1] == "5"


def test_profile_mode_needs_no_external_dataset(prepared, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "prepare", "--mode", "profile"])
    remote.main()
    value = remote.config()
    remote.validate_prepared(value)
    assert value["mode"] == "profile"
    assert value["datasetConfig"] is None


def test_pilot_mode_requires_kaggle_dataset_config(prepared, monkeypatch):
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "prepare", "--mode", "pilot"])
    with pytest.raises(ValueError, match="pilot/primary/all require --dataset-config"):
        remote.main()


def test_pilot_orchestrator_runs_ten_oct5k_blocks_and_pilot_analysis(tmp_path, monkeypatch):
    import reproduce
    calls = []
    output = str(tmp_path.resolve())
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "pilot", "--config", "remote-oct5k.json", "--output", output])
    reproduce.main()
    matrix, analysis, figures = calls[-3:]
    assert matrix == [sys.executable, "scripts/run_matrix.py", "--config", "remote-oct5k.json", "--datasets", "oct5k",
                      "--repeats", "10", "--resume", "--output", output]
    assert analysis == [sys.executable, "scripts/analyze.py", "--input", output, "--output", output + "/pilot-analysis", "--pilot"]
    assert figures == [sys.executable, "scripts/figures.py", "--input", output, "--output", output + "/pilot-figures"]


def test_profile_orchestrator_requests_correlated_traces(tmp_path, monkeypatch):
    import reproduce
    calls = []
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "profile", "--output", str(tmp_path)])
    reproduce.main()
    command = calls[-1]
    assert "scripts/profile_cache_requests.py" in command
    assert "--server-trace" in command
    assert command[command.index("--windows") + 1] == "30"


def test_changed_upload_inputs_rejected(prepared):
    (prepared / "notebook/kernel-metadata.json").write_text('{"is_private": false}')
    with pytest.raises(ValueError, match="Prepared files changed"):
        remote.validate_prepared(remote.config())


def test_extra_upload_files_rejected(prepared):
    (prepared / "source/unintended.txt").write_text("must not upload")
    with pytest.raises(ValueError, match="Unexpected files"):
        remote.validate_prepared(remote.config())


def test_upload_failure_does_not_record_success(prepared, monkeypatch):
    def fail(*args):
        assert args[:2] == ("datasets", "create")
        assert "--public" not in args
        raise RuntimeError("upload failed")
    monkeypatch.setattr(remote, "cli", fail)
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "upload-source"])
    with pytest.raises(RuntimeError, match="upload failed"):
        remote.main()
    assert not (prepared / "uploaded-source.json").exists()


def test_outputs_downloads_only_verified_results_archive(prepared, monkeypatch):
    calls = []
    monkeypatch.setattr(remote, "cli", lambda *args, **kwargs: calls.append(args))
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "outputs"])
    remote.main()
    assert calls[0][:2] == ("kernels", "output")
    assert calls[0][-2:] == ("--file-pattern", "aether-results-only\\.zip")


def test_run_requires_matching_source_receipt(prepared, monkeypatch):
    calls = []
    def cli(*args, **kwargs):
        calls.append(args)
        return "ready" if kwargs.get("capture") else None
    monkeypatch.setattr(remote, "cli", cli)
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "run"])
    remote.write(prepared / "uploaded-source.json", {"dataset": "wrong", "manifestSha256": "wrong"})
    with pytest.raises(ValueError, match="freshly prepared"):
        remote.main()
    assert not calls
    value = remote.config()
    remote.write(prepared / "uploaded-source.json", {"dataset": value["sourceDataset"], "manifestSha256": value["sourceManifestSha256"]})
    remote.main()
    assert calls == [("datasets", "status", value["sourceDataset"]),
                     ("kernels", "push", "-p", prepared / "notebook", "--accelerator", "NvidiaTeslaT4")]


@pytest.mark.parametrize("state", ["processing", "error", "", "403 Forbidden"])
def test_unready_dataset_blocks_submission(prepared, monkeypatch, state):
    calls = []
    def cli(*args, **kwargs):
        calls.append(args)
        return state
    monkeypatch.setattr(remote, "cli", cli)
    value = remote.config()
    remote.write(prepared / "uploaded-source.json", {"dataset": value["sourceDataset"], "manifestSha256": value["sourceManifestSha256"]})
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "run"])
    with pytest.raises(ValueError, match="Source dataset"):
        remote.main()
    assert calls == [("datasets", "status", value["sourceDataset"])]
