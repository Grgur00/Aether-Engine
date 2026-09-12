"""Local Kaggle CLI workflow for VS Code. Credentials stay with Kaggle's auth tools."""
import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
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
    if {p.name for p in (WORK / "source").iterdir()} != allowed:
        raise ValueError("Unexpected files in source upload folder; inspect before preparing again")
    metadata = json.loads((WORK / "notebook/kernel-metadata.json").read_text())
    if metadata.get("is_private") is not True or metadata.get("id") != value["notebook"] or "id_no" in metadata:
        raise ValueError("Notebook must match the prepared private notebook")


def prepare(args):
    from package_artifact import package
    previous = json.loads((WORK / "config.json").read_text()) if (WORK / "config.json").is_file() else {}
    user = (args.user or previous.get("user", "")).lower()
    if not re.fullmatch(r"[a-z0-9_-]+", user):
        raise ValueError("provide a valid Kaggle username with --user")
    value = {"user": user, "notebook": user + "/aether-engine-vs-code",
             "sourceDataset": user + "/aether-engine-source", "accelerator": "NvidiaTeslaT4",
             "mode": args.mode or previous.get("mode", "smoke"),
             "trainingEpochs": args.training_epochs if args.training_epochs is not None else previous.get("trainingEpochs", 1),
             "datasetConfig": args.dataset_config or previous.get("datasetConfig"),
             "scratchRoot": args.scratch_root or previous.get("scratchRoot"),
             "datasetSources": args.dataset_source if args.dataset_source is not None else previous.get("datasetSources", [])}
    if value["mode"] in {"pilot", "primary", "all"} and not value["datasetConfig"]:
        raise ValueError("pilot/primary/all require --dataset-config with its path inside Kaggle")
    if value["trainingEpochs"] < 1:
        raise ValueError("--training-epochs must be positive")
    if args.training_epochs is not None and value["mode"] != "smoke":
        raise ValueError("--training-epochs applies only to smoke mode")
    source_dir, notebook_dir = WORK / "source", WORK / "notebook"
    package(source_dir / "aether-paper-artifact.zip")
    with zipfile.ZipFile(source_dir / "aether-paper-artifact.zip") as archive:
        value["sourceManifestSha256"] = hashlib.sha256(archive.read("artifact-provenance.json")).hexdigest()
    write(source_dir / "dataset-metadata.json", {"id": value["sourceDataset"], "title": "Aether Engine Source",
          "licenses": [{"name": "apache-2.0"}]})
    write(notebook_dir / "kernel-metadata.json", {"id": value["notebook"], "title": "Aether Engine VS Code",
          "code_file": "aether.ipynb", "language": "python", "kernel_type": "notebook",
          "is_private": True, "enable_gpu": True, "enable_internet": True,
          "dataset_sources": [value["sourceDataset"], *value["datasetSources"]],
          "competition_sources": [], "kernel_sources": []})
    parameters = "import json\nREMOTE_CONFIG = json.loads(" + repr(json.dumps(value)) + ")\n"
    body = (ROOT / "kaggle/vscode_run.py").read_text(encoding="utf-8")
    cells = [{"cell_type": "code", "id": identity, "metadata": {"id": identity, "language": "python"}, "execution_count": None,
              "outputs": [], "source": text.splitlines(keepends=True)}
             for identity, text in (("configuration", parameters), ("run-aether", body))]
    write(notebook_dir / "aether.ipynb", {"nbformat": 4, "nbformat_minor": 5, "cells": cells,
          "metadata": {"kernelspec": {"name": "python3", "display_name": "Python 3", "language": "python"}}})
    write(WORK / "config.json", value)
    files = ["config.json", "source/dataset-metadata.json", "source/aether-paper-artifact.zip",
             "source/aether-paper-artifact.zip.sha256", "notebook/kernel-metadata.json", "notebook/aether.ipynb"]
    write(WORK / "prepared.json", {name: hashlib.sha256((WORK / name).read_bytes()).hexdigest() for name in files})
    print(f"Prepared private notebook {value['notebook']} in mode {value['mode']}; nothing uploaded")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["setup", "prepare", "login", "check", "doctor", "upload-source", "source-status", "run", "status", "outputs", "logs"])
    parser.add_argument("--user")
    parser.add_argument("--mode", choices=["smoke", "profile", "pilot", "primary", "all"])
    parser.add_argument("--training-epochs", type=int, help="Epochs per CPU training fixture in smoke mode")
    parser.add_argument("--dataset-config")
    parser.add_argument("--dataset-source", action="append")
    parser.add_argument("--scratch-root")
    parser.add_argument("--update", action="store_true", help="Publish a new version of an existing private source dataset")
    args = parser.parse_args()
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
        cli("kernels", "push", "-p", WORK / "notebook", "--accelerator", value["accelerator"])
    elif args.action == "status":
        cli("kernels", "status", value["notebook"])
    elif args.action == "outputs":
        import time
        destination = ROOT / "results/kaggle" / str(time.time_ns())
        destination.mkdir(parents=True, exist_ok=False)
        cli("kernels", "output", value["notebook"], "-p", destination, "--file-pattern", "aether-results-only\\.zip")
        print("Downloaded results:", destination)
    elif args.action == "logs":
        cli("kernels", "logs", value["notebook"])


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(f"Kaggle command failed (exit {error.returncode}). See the CLI message above; no later step was run.") from None
    except (ValueError, RuntimeError) as error:
        raise SystemExit(str(error)) from None
