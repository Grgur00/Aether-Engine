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
            archive.writestr("artifact-provenance.json", '{"files": {"fixture": "test"}, "sourceClean": true}')
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


def test_monai_pilot_is_separate_and_fixed(prepared):
    remote.prepare(SimpleNamespace(user=None, mode="monai", training_epochs=None, dataset_config=None,
                                   scratch_root=None, dataset_source=None, epochs=20, prefetch_depth=0))
    value = remote.config()
    assert (value["mode"], value["epochs"], value["pilotRepeats"], value["prefetchDepth"], value["serverTrace"]) == (
        "monai", 20, 5, 0, False)
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    runner = "".join(notebook["cells"][1]["source"])
    assert '"scripts/monai_comparison.py"' in runner
    assert '"--no-deps", "-r", "env/requirements-monai.lock"' in runner
    remote.validate_prepared(value)


@pytest.mark.parametrize("mode", ["population", "population-bulk", "population-layout"])
def test_population_diagnostic_has_no_training_and_separate_smoke(prepared, mode):
    remote.prepare(SimpleNamespace(user=None, mode=mode, training_epochs=None, dataset_config=None,
                                   scratch_root=None, dataset_source=None))
    value = remote.config()
    assert value["epochs"] is None and value["datasetConfig"] is None
    assert value["pilotRepeats"] == 3 and value["prefetchDepth"] is None
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    runner = "".join(notebook["cells"][1]["source"])
    assert '"scripts/profile_population.py", "--smoke"' in runner
    assert 'prefix + "-diagnostic"' in runner
    assert '"--include-bulk"' in runner
    compile(runner, "notebook", "exec")
    remote.validate_prepared(value)


def test_longitudinal_preparation(prepared):
    remote.prepare(SimpleNamespace(user=None, mode="longitudinal", training_epochs=None, dataset_config=None,
                                   scratch_root=None, dataset_source=None))
    value = remote.config()
    assert value["mode"] == "longitudinal"
    assert value["epochs"] == 20 and value["pilotRepeats"] == 5 and value["prefetchDepth"] == 0
    assert len(value["longitudinalManifestSha256"]) == 5
    assert value["scratchRoot"] == "/kaggle/working/aether-longitudinal-stores"
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    runner = "".join(notebook["cells"][1]["source"])
    assert '"longitudinal-smoke"' in runner and '"longitudinal-pilot"' in runner
    assert runner.index("run_logged(smoke") < runner.index('command = [python, "scripts/longitudinal_comparison.py"')
    compile(runner, "notebook", "exec")
    remote.validate_prepared(value)


def test_bulk_jfr_preparation_is_one_fixed_sequence_without_layout_sweep(prepared):
    remote.prepare(SimpleNamespace(user=None, mode="population-jfr", training_epochs=None,
        dataset_config=None, scratch_root=None, dataset_source=None))
    value = remote.config()
    assert value["epochs"] is None and not value["serverTrace"]
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    runner = "".join(notebook["cells"][1]["source"])
    assert '"scripts/profile_bulk_jfr.py"' in runner
    compile(runner, "notebook", "exec")
    remote.validate_prepared(value)


def test_persistent_longitudinal_is_a_separate_protocol(prepared):
    remote.prepare(SimpleNamespace(user=None, mode="longitudinal-persistent", training_epochs=None,
                                   dataset_config=None, scratch_root=None, dataset_source=None))
    value = remote.config()
    assert value["serviceLifecycle"] == "persistent-per-block"
    assert value["pilotRepeats"] == 5 and value["epochs"] == 20
    assert value["datasetConfig"] == "configs/paper/oct5k-longitudinal-persistent-pilot.json"
    with pytest.raises(ValueError, match="lifecycle differ"):
        remote.prepare(SimpleNamespace(user=None, mode="longitudinal",
            training_epochs=None, dataset_config=value["datasetConfig"], scratch_root=None, dataset_source=None))


@pytest.mark.parametrize("test_fails", [False, True])
def test_restart_gate_creates_basetemp_parent_in_clean_checkout(prepared, monkeypatch, test_fails):
    import ast
    import os
    import subprocess
    from pathlib import Path

    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    tree = ast.parse("".join(notebook["cells"][1]["source"]))
    gate = next(node for node in tree.body if isinstance(node, ast.FunctionDef)
                and node.name == "run_restart_correctness")
    repo, results = prepared / "checkout", prepared / "results"
    repo.mkdir()
    results.mkdir()
    assert not (repo / "build").exists()
    calls = []

    def run_logged(command, *, cwd):
        calls.append(command)
        assert cwd == repo and os.environ["AETHER_JAVA_TEST"] == "1"
        basetemp = Path(command[command.index("--basetemp") + 1])
        assert basetemp == repo / "build/restart-correctness"
        basetemp.mkdir()  # pytest creates this directory without parents=True.
        assert command[command.index("--junitxml") + 1] == str(results / "restart-correctness.xml")
        if test_fails:
            raise subprocess.CalledProcessError(1, command)

    monkeypatch.setenv("AETHER_JAVA_TEST", "0")
    namespace = dict(os=os, repo=repo, results=results, run_logged=run_logged)
    exec(compile(ast.Module(body=[gate], type_ignores=[]), "notebook-gate", "exec"), namespace)
    if test_fails:
        with pytest.raises(subprocess.CalledProcessError):
            namespace["run_restart_correctness"]("python")
    else:
        namespace["run_restart_correctness"]("python")
    assert len(calls) == 1
    assert calls[0][1:4] == ["-m", "pytest",
        "scripts/tests/test_longitudinal_comparison.py::test_real_five_version_process_restart"]


@pytest.mark.parametrize("changed", [{"epochs": 10}, {"prefetch_depth": 1},
                                    {"pilot_repeats": 10}, {"server_trace": True}])
def test_monai_pilot_rejects_setting_drift(prepared, changed):
    values = dict(user=None, mode="monai", training_epochs=None, dataset_config=None,
                  scratch_root=None, dataset_source=None)
    with pytest.raises(ValueError, match="MONAI pilot"):
        remote.prepare(SimpleNamespace(**values, **changed))


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


def test_primary_resets_saved_pilot_settings_and_freezes_notebook(prepared, monkeypatch):
    value = remote.config()
    value.update(mode="pilot", epochs=5, prefetchDepth=2, serverTrace=True,
                 datasetConfig="/kaggle/input/fixture/config.json")
    remote.write(prepared / "config.json", value)
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "prepare", "--mode", "primary"])
    remote.main()
    value = remote.config()
    assert (value["epochs"], value["prefetchDepth"], value["serverTrace"]) == (20, 0, False)
    assert value["datasetConfig"] == "configs/paper/oct5k-confirmatory-v2.json"
    remote.validate_prepared(value)


def test_primary_rejects_dirty_snapshot(prepared, monkeypatch):
    def dirty_package(path):
        with zipfile.ZipFile(path, "w") as archive:
            archive.writestr("artifact-provenance.json", '{"sourceClean": false}')
    monkeypatch.setattr(package_artifact, "package", dirty_package)
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "prepare", "--mode", "primary",
                                      "--dataset-config", "fixture.json"])
    with pytest.raises(ValueError, match="clean committed"):
        remote.main()


def test_prepared_diagnostic_pilot_forwards_trace_and_single_block(prepared, monkeypatch):
    monkeypatch.setattr(sys, "argv", [
        "kaggle_remote.py", "prepare", "--mode", "pilot",
        "--dataset-config", "/kaggle/input/aether-oct5k-pilot/aether-datasets.json",
        "--dataset-source", "grgur321/aether-oct5k-pilot",
        "--server-trace", "--pilot-repeats", "1",
    ])
    remote.main()
    value = remote.config()
    assert value["serverTrace"] is True
    assert value["pilotRepeats"] == 1
    assert value["datasetSources"] == ["grgur321/aether-oct5k-pilot"]
    notebook = json.loads((prepared / "notebook/aether.ipynb").read_text())
    runner = "".join(notebook["cells"][1]["source"])
    assert 'command += ["--server-trace"]' in runner
    assert 'command += ["--pilot-repeats", str(REMOTE_CONFIG.get("pilotRepeats", 10))]' in runner


def test_run_rejects_ignored_configuration_overrides(prepared, monkeypatch, capsys):
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "run", "--server-trace", "--pilot-repeats", "1"])
    with pytest.raises(SystemExit) as error:
        remote.main()
    assert error.value.code == 2
    assert "configuration options apply to prepare" in capsys.readouterr().err


def test_pilot_orchestrator_runs_ten_oct5k_blocks_and_pilot_analysis(tmp_path, monkeypatch):
    import reproduce
    calls = []
    output = str(tmp_path.resolve())
    monkeypatch.setattr(reproduce.subprocess, "run", lambda command, **kwargs: calls.append(command))
    monkeypatch.setattr(sys, "argv", ["reproduce.py", "pilot", "--config", "remote-oct5k.json", "--output", output])
    reproduce.main()
    matrix, analysis, figures = calls[-3:]
    assert matrix == [sys.executable, "scripts/run_matrix.py", "--config", "remote-oct5k.json", "--datasets", "oct5k",
                      "--repeats", "10", "--prefetch-depth", "1", "--resume", "--output", output]
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
    monkeypatch.setattr(remote, "ROOT", prepared)
    def download(*args, **kwargs):
        calls.append(args)
        with zipfile.ZipFile(args[4] / "aether-results-only.zip", "w") as archive:
            archive.writestr("report.json", '{"fixture": true}')
        (args[4] / (args[2].split("/")[1] + ".log")).write_text("platform log")
    monkeypatch.setattr(remote, "cli", download)
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "outputs"])
    remote.main()
    assert calls[0][:2] == ("kernels", "output")
    assert calls[0][-2:] == ("--file-pattern", "aether-results-only\\.zip")
    destination = calls[0][4]
    assert [path.name for path in destination.iterdir()] == ["aether-results-only.zip"]
    with zipfile.ZipFile(destination / "aether-results-only.zip") as archive:
        assert archive.read("kaggle-notebook.log") == b"platform log"


def test_outputs_rejects_missing_archive(prepared, monkeypatch):
    monkeypatch.setattr(remote, "ROOT", prepared)
    monkeypatch.setattr(remote, "cli", lambda *args, **kwargs: None)
    monkeypatch.setattr(sys, "argv", ["kaggle_remote.py", "outputs"])
    with pytest.raises(RuntimeError, match="No results ZIP"):
        remote.main()


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
