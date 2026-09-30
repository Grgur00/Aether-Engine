"""Local Kaggle CLI workflow for VS Code. Credentials stay with Kaggle's auth tools."""
import argparse
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / "build/kaggle"
VENV = ROOT / "build/kaggle-venv"


def executable(name):
    return VENV / ("Scripts" if os.name == "nt" else "bin") / (name + ".exe" if os.name == "nt" else name)


def cli(*arguments, capture=False):
    command = executable("kaggle")
    if not command.is_file():
        raise RuntimeError("Run python scripts/kaggle_remote.py setup first")
    env = os.environ.copy()
    env.pop("PYTHONPATH", None)
    pushing = arguments[:2] == ("kernels", "push")
    result = subprocess.run([str(command), *map(str, arguments)], cwd=ROOT, env=env, check=True,
                            stdout=subprocess.PIPE if capture or pushing else None,
                            stderr=subprocess.STDOUT if pushing else None, text=True)
    if pushing:
        print(result.stdout, end="")
        if ("Kernel push error:" in result.stdout
                or "not valid dataset sources" in result.stdout
                or "successfully pushed" not in result.stdout):
            raise RuntimeError("Kaggle did not confirm a successful submission with all datasets attached. See the CLI message above.")
    return result.stdout if capture else None


def require_source_ready(value):
    status = cli("datasets", "status", value["sourceDataset"], capture=True).strip().lower()
    if status != "ready":
        raise ValueError(f"Source dataset is {status!r}; wait for Source status to report ready, then retry Run")
    print("Source dataset is ready")


def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")


def verified_submission(value):
    """Bind this submission to downloaded, byte-verified source archives."""
    state = json.loads(cli("datasets", "status", value["sourceDataset"],
                           "--format", "json(status,current_version_number)", capture=True))
    version = state.get("current_version_number")
    if state.get("status") != "ready" or type(version) is not int or version < 1:
        raise ValueError("Source dataset has no ready version to pin")
    pinned = f"{value['sourceDataset']}/{version}"
    destination = Path(tempfile.mkdtemp(prefix="submission-", dir=WORK))
    for name in ("candidate-source.bin", "baseline-source.bin"):
        cli("datasets", "download", pinned, "-f", name, "-p", destination)
        downloaded = destination / name
        if not downloaded.is_file() or hashlib.sha256(downloaded.read_bytes()).digest() != hashlib.sha256((WORK / "source" / name).read_bytes()).digest():
            raise ValueError(f"Remote source mismatch: {pinned}/{name}; no notebook submitted")
    notebook = destination / "notebook"
    shutil.copytree(WORK / "notebook", notebook)
    metadata = json.loads((notebook / "kernel-metadata.json").read_text())
    metadata["dataset_sources"] = [pinned, *value["datasetSources"]]
    write(notebook / "kernel-metadata.json", metadata)
    write(destination / "receipt.json", {"sourceDataset": pinned,
          "sourceManifestSha256": value["sourceManifestSha256"],
          "files": {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in notebook.iterdir()}})
    return notebook


def config():
    path = WORK / "config.json"
    if not path.is_file():
        raise RuntimeError("Run prepare --user YOUR_KAGGLE_USERNAME first")
    value = json.loads(path.read_text())
    # Repair the legacy title/slug mismatch for status, logs and downloads too.
    if value["notebook"] == value.get("user", "") + "/aether-engine-vscode":
        value["notebook"] = value["user"] + "/aether-engine-vs-code"
    for key in ("notebook", "sourceDataset"):
        if not re.fullmatch(r"[a-z0-9_-]+/[a-z0-9-]+", value[key]):
            raise ValueError(f"invalid Kaggle identifier: {key}")
    return value


def validate_prepared(value):
    """Reject changed upload inputs; prepare is the explicit snapshot boundary."""
    receipt = json.loads((WORK / "prepared.json").read_text())
    for relative, expected in receipt.items():
        path = (WORK / relative).resolve()
        if not path.is_relative_to(WORK.resolve()) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError("Prepared files changed; run Prepare again before uploading or running")
    allowed = {"dataset-metadata.json", "aether-paper-artifact.zip", "aether-paper-artifact.zip.sha256"}
    if value["mode"] == "population-verification":
        allowed.add("baseline-source.zip")
        allowed.update({"candidate-source.bin", "baseline-source.bin"})
    if {p.name for p in (WORK / "source").iterdir()} != allowed:
        raise ValueError("Unexpected files in source upload folder; inspect before preparing again")
    metadata = json.loads((WORK / "notebook/kernel-metadata.json").read_text())
    if metadata.get("is_private") is not True or metadata.get("id") != value["notebook"] or "id_no" in metadata:
        raise ValueError("Notebook must match the prepared private notebook")


def prepare(args):
    from package_artifact import package
    previous = json.loads((WORK / "config.json").read_text()) if (WORK / "config.json").is_file() else {}
    user = (args.user or previous.get("user", "")).lower()
    mode = args.mode or previous.get("mode", "smoke")
    # Hit-path options belong to profile mode. Do not carry a saved diagnostic
    # flag or sweep into a newly selected pilot/primary/smoke campaign.
    previous_profile = previous if mode == "profile" and previous.get("mode") == "profile" else {}
    hit_path_profile = getattr(args, "hit_path_profile", False)
    request_sizes = getattr(args, "request_sizes", None)
    prefetch_depths = getattr(args, "prefetch_depths", None)
    prefetch_depth = getattr(args, "prefetch_depth", None)
    epochs = getattr(args, "epochs", None)
    previous_epochs = previous.get("epochs") if mode in {"pilot", "primary"} and previous.get("mode") in {"pilot", "primary"} else None
    hit_warmup_epochs = getattr(args, "hit_warmup_epochs", None)
    if not re.fullmatch(r"[a-z0-9_-]+", user):
        raise ValueError("provide a valid Kaggle username with --user")
    value = {"user": user, "notebook": user + "/aether-engine-vs-code",
             "sourceDataset": user + "/aether-engine-source", "accelerator": "NvidiaTeslaT4",
             "mode": mode,
             "serverTrace": getattr(args, "server_trace", None) if getattr(args, "server_trace", None) is not None else previous.get("serverTrace", False),
             "pilotRepeats": getattr(args, "pilot_repeats", None) if getattr(args, "pilot_repeats", None) is not None else previous.get("pilotRepeats", 10),
             "prefetchDepth": prefetch_depth if prefetch_depth is not None else previous.get("prefetchDepth"),
             "epochs": epochs if epochs is not None else previous_epochs,
             "trainingEpochs": args.training_epochs if args.training_epochs is not None else previous.get("trainingEpochs", 1),
             "hitPathProfile": hit_path_profile or previous_profile.get("hitPathProfile", False),
             "requestSizes": request_sizes if request_sizes is not None else previous_profile.get("requestSizes"),
             "prefetchDepths": prefetch_depths if prefetch_depths is not None else previous_profile.get("prefetchDepths"),
             "hitWarmupEpochs": hit_warmup_epochs if hit_warmup_epochs is not None else previous_profile.get("hitWarmupEpochs"),
             "datasetConfig": args.dataset_config or previous.get("datasetConfig"),
             "scratchRoot": args.scratch_root or previous.get("scratchRoot"),
             "datasetSources": args.dataset_source if args.dataset_source is not None else previous.get("datasetSources", [])}
    if mode == "primary":
        if epochs not in (None, 20) or prefetch_depth not in (None, 0) or getattr(args, "server_trace", None) is True:
            raise ValueError("primary is frozen at 20 epochs, prefetch depth 0, server tracing off")
        from confirmatory import PRIMARY_CONFIG
        value.update(epochs=20, prefetchDepth=0, serverTrace=False)
        value["datasetConfig"] = args.dataset_config or PRIMARY_CONFIG
    if mode == "monai":
        if epochs not in (None, 20) or prefetch_depth not in (None, 0) or getattr(args, "server_trace", None) is True:
            raise ValueError("MONAI pilot is fixed at 20 epochs, prefetch depth 0, server tracing off")
        if getattr(args, "pilot_repeats", None) not in (None, 5):
            raise ValueError("MONAI pilot requires 5 fresh paired blocks")
        value.update(epochs=20, prefetchDepth=0, serverTrace=False, pilotRepeats=5)
        value["datasetConfig"] = args.dataset_config or "configs/paper/oct5k-pilot-20ep.json"
    if mode in {"population", "population-bulk", "population-layout", "population-jfr", "population-verification"}:
        if epochs is not None or getattr(args, "pilot_repeats", None) not in (None, 3):
            raise ValueError("population diagnostic has no training epochs and requires three repetitions")
        if args.dataset_config is not None or prefetch_depth not in (None, 0):
            raise ValueError("population uses the frozen V0 manifest and no prefetch")
        value.update(epochs=None, prefetchDepth=None, serverTrace=False, pilotRepeats=3, datasetConfig=None)
        value["scratchRoot"] = args.scratch_root or "/kaggle/working/aether-population-stores"
        from longitudinal_manifests import verify
        receipt, _ = verify(ROOT / "configs/paper/oct5k-longitudinal")
        value["populationManifestSha256"] = receipt["manifestSha256"][0]
    baseline = getattr(args, "bulk_baseline", None)
    if mode == "population-verification":
        if baseline is None:
            raise ValueError("population-verification requires --bulk-baseline frozen source ZIP")
        baseline = Path(baseline).resolve()
        with zipfile.ZipFile(baseline) as archive:
            names = archive.namelist()
            if len(names) != len(set(names)) or any(
                    "\\" in name or Path(name).is_absolute() or ".." in Path(name).parts for name in names):
                raise ValueError("unsafe baseline archive inventory")
            raw = archive.read("artifact-provenance.json")
            files = json.loads(raw)["files"]
            if not files or set(names) != {*files, "artifact-provenance.json"}:
                raise ValueError("baseline archive differs from its provenance")
            for name, expected in files.items():
                if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                    raise ValueError(f"baseline checksum mismatch: {name}")
            value["bulkBaselineManifestSha256"] = hashlib.sha256(raw).hexdigest()
        value["bulkBaselineSha256"] = hashlib.sha256(baseline.read_bytes()).hexdigest()
    elif baseline is not None:
        raise ValueError("--bulk-baseline requires population-verification mode")
    if mode in {"longitudinal", "longitudinal-persistent"}:
        if epochs not in (None, 20) or prefetch_depth not in (None, 0) or getattr(args, "server_trace", None) is True:
            raise ValueError("longitudinal pilot requires 20 epochs/update, prefetch 0 and tracing off")
        if getattr(args, "pilot_repeats", None) not in (None, 5):
            raise ValueError("longitudinal pilot requires five fresh paired blocks")
        value.update(epochs=20, prefetchDepth=0, serverTrace=False, pilotRepeats=5)
        value["datasetConfig"] = args.dataset_config or (
            "configs/paper/oct5k-longitudinal-persistent-pilot.json" if mode == "longitudinal-persistent" else
            "configs/paper/oct5k-longitudinal-pilot.json")
        value["scratchRoot"] = args.scratch_root or "/kaggle/working/aether-longitudinal-stores"
        from longitudinal_comparison import validate_config
        from longitudinal_manifests import verify
        specification = json.loads((ROOT / value["datasetConfig"]).read_text())
        validate_config(specification)
        expected_lifecycle = "persistent-per-block" if mode == "longitudinal-persistent" else "restart-per-version"
        if specification.get("serviceLifecycle", "restart-per-version") != expected_lifecycle:
            raise ValueError("selected Kaggle mode and service lifecycle differ")
        if mode == "longitudinal-persistent":
            value["serviceLifecycle"] = expected_lifecycle
        receipt, _ = verify(ROOT / specification["manifestDirectory"])
        if receipt["counts"] != specification["versions"] or receipt["seed"] != specification["seed"]:
            raise ValueError("longitudinal manifests differ from the frozen configuration")
        value["longitudinalManifestSha256"] = receipt["manifestSha256"]
    if value["mode"] in {"pilot", "primary", "all", "monai", "longitudinal", "longitudinal-persistent"} and not value["datasetConfig"]:
        raise ValueError("pilot/primary/all require --dataset-config with its path inside Kaggle")
    if value["trainingEpochs"] < 1:
        raise ValueError("--training-epochs must be positive")
    if value["epochs"] is not None:
        if value["epochs"] < 1:
            raise ValueError("--epochs must be positive")
        if mode not in {"pilot", "primary", "monai", "longitudinal", "longitudinal-persistent"}:
            raise ValueError("--epochs applies to pilot/primary runs")
    if value["pilotRepeats"] < 1:
        raise ValueError("--pilot-repeats must be positive")
    if value["prefetchDepth"] is not None and (
            value["prefetchDepth"] < 0 or (value["mode"] not in {"pilot", "primary", "monai", "longitudinal", "longitudinal-persistent"} and value["prefetchDepth"] != 1)):
        raise ValueError("custom --prefetch-depth is available only for pilot/primary and must be non-negative")
    if value["mode"] == "pilot" and value["pilotRepeats"] != 10 and not value["serverTrace"]:
        raise ValueError("custom pilot repeats require --server-trace")
    if value["serverTrace"] and value["mode"] not in {"pilot", "profile"}:
        raise ValueError("--server-trace requires pilot/profile mode; use --no-server-trace for other modes")
    if hit_path_profile and value["mode"] != "profile":
        raise ValueError("--hit-path-profile requires profile mode")
    if any(value[key] is not None for key in ("requestSizes", "prefetchDepths", "hitWarmupEpochs")) and not value["hitPathProfile"]:
        raise ValueError("hit-path sweep options require --hit-path-profile")
    if args.training_epochs is not None and value["mode"] != "smoke":
        raise ValueError("--training-epochs applies only to smoke mode")
    source_dir, notebook_dir = WORK / "source", WORK / "notebook"
    package(source_dir / "aether-paper-artifact.zip")
    with zipfile.ZipFile(source_dir / "aether-paper-artifact.zip") as archive:
        provenance = json.loads(archive.read("artifact-provenance.json"))
        if mode in {"primary", "monai", "longitudinal", "longitudinal-persistent", "population", "population-bulk", "population-layout", "population-jfr", "population-verification"} and provenance.get("sourceClean") is not True:
            raise ValueError(f"prepare {mode} requires a clean committed source snapshot")
        value["sourceManifestSha256"] = hashlib.sha256(archive.read("artifact-provenance.json")).hexdigest()
    if baseline is not None:
        shutil.copy2(baseline, source_dir / "baseline-source.zip")
        # Kaggle expands .zip uploads; opaque names preserve the exact two archives.
        shutil.copy2(source_dir / "aether-paper-artifact.zip", source_dir / "candidate-source.bin")
        shutil.copy2(baseline, source_dir / "baseline-source.bin")
    write(source_dir / "dataset-metadata.json", {"id": value["sourceDataset"], "title": "Aether Engine Source",
          "licenses": [{"name": "apache-2.0"}]})
    write(notebook_dir / "kernel-metadata.json", {"id": value["notebook"], "title": "Aether Engine VS Code",
          "code_file": "aether.ipynb", "language": "python", "kernel_type": "notebook",
          "is_private": True, "enable_gpu": True, "enable_internet": True,
          "dataset_sources": [value["sourceDataset"], *value["datasetSources"]],
          "competition_sources": [], "kernel_sources": []})
    parameters = "import json\nREMOTE_CONFIG = json.loads(" + repr(json.dumps(value)) + ")\n"
    # Embed finalization before setup: it must work even if source/setup fails.
    body = (ROOT / "scripts/kaggle_results.py").read_text(encoding="utf-8") + "\n\n"
    body += (ROOT / "kaggle/vscode_run.py").read_text(encoding="utf-8")
    cells = [{"cell_type": "code", "id": identity, "metadata": {"id": identity, "language": "python"}, "execution_count": None,
              "outputs": [], "source": text.splitlines(keepends=True)}
             for identity, text in (("configuration", parameters), ("run-aether", body))]
    write(notebook_dir / "aether.ipynb", {"nbformat": 4, "nbformat_minor": 5, "cells": cells,
          "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3", "language": "python"}}})
    write(WORK / "config.json", value)
    files = ["config.json", "source/dataset-metadata.json", "source/aether-paper-artifact.zip",
             "source/aether-paper-artifact.zip.sha256", "notebook/kernel-metadata.json", "notebook/aether.ipynb"]
    if baseline is not None:
        files.append("source/baseline-source.zip")
        files.extend(["source/candidate-source.bin", "source/baseline-source.bin"])
    write(WORK / "prepared.json", {name: hashlib.sha256((WORK / name).read_bytes()).hexdigest() for name in files})
    print(f"Prepared private notebook {value['notebook']} in mode {value['mode']}; nothing uploaded")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["setup", "prepare", "login", "check", "doctor", "upload-source", "source-status", "run", "status", "outputs", "logs"])
    parser.add_argument("--user")
    parser.add_argument("--mode", choices=["smoke", "profile", "pilot", "primary", "all", "monai", "longitudinal", "longitudinal-persistent", "population", "population-bulk", "population-layout", "population-jfr", "population-verification"])
    parser.add_argument("--bulk-baseline", type=Path, help="Frozen pre-optimization source ZIP for the verification comparison")
    parser.add_argument("--training-epochs", type=int, help="Epochs per CPU training fixture in smoke mode")
    parser.add_argument("--epochs", type=int, default=None, help="Epochs per training block for pilot/primary runs")
    parser.add_argument("--server-trace", action=argparse.BooleanOptionalAction, default=None, help="Enable diagnostic pilot flush tracing")
    parser.add_argument("--pilot-repeats", type=int, help="Pilot block count; 1 with --server-trace for a diagnostic")
    parser.add_argument("--prefetch-depth", type=int, default=None, help="Lookahead depth; primary is frozen at 0")
    parser.add_argument("--hit-path-profile", action="store_true")
    parser.add_argument("--request-sizes")
    parser.add_argument("--prefetch-depths")
    parser.add_argument("--hit-warmup-epochs", type=int)
    parser.add_argument("--dataset-config")
    parser.add_argument("--dataset-source", action="append")
    parser.add_argument("--scratch-root")
    parser.add_argument("--update", action="store_true", help="Publish a new version of an existing private source dataset")
    args = parser.parse_args()
    prepare_options_used = any((
        args.user is not None, args.mode is not None, args.training_epochs is not None,
        args.server_trace is not None, args.pilot_repeats is not None, args.hit_path_profile,
        args.prefetch_depth is not None, args.epochs is not None,
        args.request_sizes is not None, args.prefetch_depths is not None,
        args.hit_warmup_epochs is not None, args.dataset_config is not None,
        args.dataset_source is not None, args.scratch_root is not None,
        args.bulk_baseline is not None,
    ))
    if prepare_options_used and args.action != "prepare":
        parser.error("configuration options apply to prepare; prepare, upload/update source, then run")
    if args.update and args.action != "upload-source":
        parser.error("--update applies only to upload-source")
    if args.action == "setup":
        if not executable("python").is_file():
            subprocess.run([sys.executable, "-m", "venv", str(VENV)], check=True)
        subprocess.run([str(executable("python")), "-m", "pip", "install", "-r", str(ROOT / "env/requirements-kaggle.lock")], check=True)
        return
    if args.action == "prepare":
        prepare(args)
        return
    if args.action == "login":
        cli("auth", "login")
        return
    if args.action == "check":
        cli("kernels", "list", "--mine", "--page-size", "1")
        return
    if args.action == "doctor":
        cli("--version")
        credentials = Path(os.environ.get("KAGGLE_CONFIG_DIR", str(Path.home() / ".kaggle")))
        print("Credential files present:", any((credentials / name).is_file() for name in ("access_token", "kaggle.json")))
        print("Token environment variable present:", bool(os.environ.get("KAGGLE_API_TOKEN")))
        print("OAuth credentials may also be available; use the Check connection task to authenticate a read-only request.")
        print("Prepared configuration:", WORK / "config.json")
        return
    value = config()
    if args.action == "upload-source":
        validate_prepared(value)
        if args.update:
            cli("datasets", "version", "-p", WORK / "source", "-m", "Aether source " + value["sourceManifestSha256"][:16])
        else:
            cli("datasets", "create", "-p", WORK / "source")  # Private by default; no public flag.
        write(WORK / "uploaded-source.json", {"dataset": value["sourceDataset"], "manifestSha256": value["sourceManifestSha256"]})
    elif args.action == "source-status":
        require_source_ready(value)
    elif args.action == "run":
        validate_prepared(value)
        if not (WORK / "uploaded-source.json").is_file():
            raise ValueError("Run Upload source first, then wait for Source status to report ready")
        uploaded = json.loads((WORK / "uploaded-source.json").read_text())
        if uploaded != {"dataset": value["sourceDataset"], "manifestSha256": value["sourceManifestSha256"]}:
            raise ValueError("upload the freshly prepared source dataset before running this notebook")
        require_source_ready(value)
        notebook = verified_submission(value) if value["mode"] == "population-verification" else WORK / "notebook"
        cli("kernels", "push", "-p", notebook, "--accelerator", value["accelerator"])
    elif args.action == "status":
        cli("kernels", "status", value["notebook"])
    elif args.action == "outputs":
        import time
        destination = ROOT / "results/kaggle" / str(time.time_ns())
        destination.mkdir(parents=True, exist_ok=False)
        cli("kernels", "output", value["notebook"], "-p", destination, "--file-pattern", "aether-results-only\\.zip")
        archive_path = destination / "aether-results-only.zip"
        if not archive_path.is_file():
            raise RuntimeError("No results ZIP was downloaded. Run the newly prepared notebook version, then retry outputs.")
        with zipfile.ZipFile(archive_path) as archive:
            if not archive.namelist() or archive.testzip() is not None:
                raise RuntimeError("Downloaded results ZIP is empty or corrupt")
        from kaggle_results import include_notebook_log
        include_notebook_log(archive_path, destination / (value["notebook"].split("/")[1] + ".log"))
        print("Downloaded results ZIP:", archive_path)
    elif args.action == "logs":
        cli("kernels", "logs", value["notebook"])


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"Kaggle command failed (exit {error.returncode}). See the CLI message above; no later step was run.") from None
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
