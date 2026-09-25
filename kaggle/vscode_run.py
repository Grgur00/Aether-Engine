"""Body of the prepared notebook; bundle_results is embedded before this code."""
import hashlib
import json
import os
import shutil
import subprocess
import traceback
import zipfile
from pathlib import Path


def run_logged(command, *, cwd):
    with (results / "run.log").open("a", encoding="utf-8") as log:
        message = "$ " + " ".join(map(str, command)) + "\n"
        print(message, end="", flush=True)
        log.write(message)
        with subprocess.Popen(command, cwd=cwd, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                              text=True, encoding="utf-8", errors="replace",
                              env={**os.environ, "PYTHONUNBUFFERED": "1"}) as process:
            for line in process.stdout:
                print(line, end="", flush=True)
                log.write(line)
                log.flush()
            code = process.wait()
        if code:
            raise subprocess.CalledProcessError(code, command)


def prepare_evolution_data(python, config_path):
    configuration = json.loads(config_path.read_text(encoding="utf-8"))
    for name, spec in configuration.items():
        if not spec.get("evolutionSourceManifest"):
            continue
        v1, v2 = Path(spec["manifestV1"]), Path(spec["manifestV2"])
        if v1.name != "v1.csv" or v2.name != "v2.csv" or v1.parent != v2.parent:
            raise ValueError("generated evolution manifests require v1.csv and v2.csv in one directory")
        run_logged([python, "scripts/prepare_evolution.py", "--manifest", spec["evolutionSourceManifest"],
                    "--output", str(v1.parent), "--v1-size", str(spec["samplesV1"]),
                    "--v2-size", str(spec["samplesV2"]), "--reusable", str(spec["expectedReusable"]),
                    "--split", spec.get("split", "train"), "--seed", str(spec["evolutionSeed"])], cwd=repo)
        receipt_dir = results / "data-preparation" / name
        receipt_dir.mkdir(parents=True, exist_ok=False)
        shutil.copy2(v1.parent / "evolution.json", receipt_dir / "evolution.json")
    run_logged([python, "scripts/validate_manifests.py", "--config", str(config_path)], cwd=repo)


results = Path("/kaggle/working/aether-results")
repo = Path("/kaggle/working/Aether-Engine")
venv = Path("/kaggle/working/aether-paper-venv")
runtime_path = Path("/kaggle/working/aether-paper-runtime.json")
results.mkdir(parents=True, exist_ok=False)
repo_created = False
venv_existed = venv.exists()
status = {"status": "running", "config": REMOTE_CONFIG}
try:
    source = Path("/kaggle/input") / REMOTE_CONFIG["sourceDataset"].split("/")[1]
    repo.mkdir(parents=True, exist_ok=False)
    repo_created = True
    archives = list(source.rglob("aether-paper-artifact.zip"))
    if archives:
        if len(archives) != 1:
            raise ValueError("ambiguous source archive")
        with zipfile.ZipFile(archives[0]) as archive:
            for member in archive.infolist():
                if not (repo / member.filename).resolve().is_relative_to(repo):
                    raise ValueError("source archive path escapes repository")
            archive.extractall(repo)
    else:
        manifests = list(source.rglob("artifact-provenance.json"))
        if len(manifests) != 1:
            raise ValueError("source dataset has no unique artifact manifest")
        shutil.copytree(manifests[0].parent, repo, dirs_exist_ok=True)
    manifest_path = repo / "artifact-provenance.json"
    if hashlib.sha256(manifest_path.read_bytes()).hexdigest() != REMOTE_CONFIG["sourceManifestSha256"]:
        raise ValueError("attached source dataset differs from the locally prepared notebook")
    manifest = json.loads(manifest_path.read_text())
    if not manifest.get("files"):
        raise ValueError("empty source manifest")
    for relative, expected in manifest["files"].items():
        path = (repo / relative).resolve()
        if not path.is_relative_to(repo) or hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError(f"source checksum mismatch: {relative}")
    run_logged(["bash", str(repo / "kaggle/setup.sh")], cwd=repo)
    runtime = json.loads(Path("/kaggle/working/aether-paper-runtime.json").read_text())
    os.environ["JAVA_HOME"] = runtime["javaHome"]
    os.environ["PATH"] = runtime["javaHome"] + "/bin:" + os.environ["PATH"]
    os.environ["PYTHONPATH"] = f"{repo / 'clients/python'}:{repo / 'scripts'}"
    python = "/kaggle/working/aether-paper-venv/bin/python"
    if REMOTE_CONFIG["mode"] == "all":
        run_logged([python, "-m", "pip", "install", "-r", "env/requirements-dali.lock"], cwd=repo)
    if REMOTE_CONFIG["mode"] in {"monai", "longitudinal"}:
        run_logged([python, "-m", "pip", "install", "--no-deps", "-r", "env/requirements-monai.lock"], cwd=repo)
    run_logged([python, "scripts/validate_gpu.py"], cwd=repo)
    if REMOTE_CONFIG["mode"] in {"pilot", "primary", "monai"} and REMOTE_CONFIG.get("datasetConfig"):
        prepare_evolution_data(python, repo / REMOTE_CONFIG["datasetConfig"])
    command = [python, "scripts/reproduce.py", REMOTE_CONFIG["mode"], "--output", str(results)]
    if REMOTE_CONFIG["mode"] == "monai":
        command = [python, "scripts/monai_comparison.py", "--output", str(results / "monai-pilot")]
    if REMOTE_CONFIG["mode"] == "longitudinal":
        # Separate smoke stores/results; a failure prevents all pilot measurements.
        smoke = [python, "scripts/longitudinal_comparison.py", "--smoke",
                 "--config", REMOTE_CONFIG["datasetConfig"], "--output", str(results / "longitudinal-smoke"),
                 "--scratch-root", REMOTE_CONFIG["scratchRoot"]]
        run_logged(smoke, cwd=repo)
        command = [python, "scripts/longitudinal_comparison.py", "--output", str(results / "longitudinal-pilot")]
    if REMOTE_CONFIG["mode"] == "smoke":
        command += ["--training-epochs", str(REMOTE_CONFIG.get("trainingEpochs", 1))]
    if REMOTE_CONFIG.get("datasetConfig"):
        command += ["--config", REMOTE_CONFIG["datasetConfig"]]
    if REMOTE_CONFIG.get("scratchRoot"):
        command += ["--scratch-root", REMOTE_CONFIG["scratchRoot"]]
    if REMOTE_CONFIG.get("serverTrace"):
        command += ["--server-trace"]
    if REMOTE_CONFIG.get("hitPathProfile"):
        command.append("--hit-path-profile")
    if REMOTE_CONFIG.get("requestSizes") is not None:
        command += ["--request-sizes", REMOTE_CONFIG["requestSizes"]]
    if REMOTE_CONFIG.get("prefetchDepths") is not None:
        command += ["--prefetch-depths", REMOTE_CONFIG["prefetchDepths"]]
    if REMOTE_CONFIG.get("hitWarmupEpochs") is not None:
        command += ["--hit-warmup-epochs", str(REMOTE_CONFIG["hitWarmupEpochs"])]
    if REMOTE_CONFIG["mode"] in {"pilot", "monai"}:
        command += ["--pilot-repeats", str(REMOTE_CONFIG.get("pilotRepeats", 10))]
    if REMOTE_CONFIG.get("prefetchDepth") is not None and REMOTE_CONFIG["mode"] != "longitudinal":
        command += ["--prefetch-depth", str(REMOTE_CONFIG["prefetchDepth"])]
    if REMOTE_CONFIG.get("epochs") is not None and REMOTE_CONFIG["mode"] != "longitudinal":
        command += ["--epochs", str(REMOTE_CONFIG["epochs"])]
    run_logged(command, cwd=repo)
    status["status"] = "passed"
except BaseException as error:
    status.update(status="failed", errorType=type(error).__name__, error=str(error))
    with (results / "run.log").open("a", encoding="utf-8") as log:
        traceback.print_exc(file=log)
    raise
finally:
    (results / "run-status.json").write_text(json.dumps(status, indent=2) + "\n", encoding="utf-8")
    archive_path = bundle_results(results, "/kaggle/working/aether-results-only.zip", runtime_path)
    print("Verified Kaggle results ZIP:", archive_path, flush=True)
    # Remove only this run's generated files, after ZIP verification succeeds.
    shutil.rmtree(results)
    if repo_created:
        shutil.rmtree(repo)
    if not venv_existed and venv.exists():
        shutil.rmtree(venv)
    runtime_path.unlink(missing_ok=True)
