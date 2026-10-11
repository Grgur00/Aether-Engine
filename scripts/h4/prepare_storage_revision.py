"""Prepare a separate, source-inventoried H4 candidate; never edit H2 or root Java."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import zipfile

from .common import digest, write_json
from .export_storage_revision import BASELINE_SHA256, FILES
from .prepare_pcam_manifest import canonical_hash


def prepare(bundle, patch, output):
    bundle, patch, output = Path(bundle).resolve(), Path(patch).resolve(), Path(output).resolve()
    if output.exists() or digest(bundle) != BASELINE_SHA256:
        raise ValueError("new output path and verified v3 baseline required")
    receipt = json.loads(patch.with_suffix(".json").read_text(encoding="utf-8"))
    if (receipt.get("patch_sha256") != digest(patch)
            or receipt.get("baseline_transport_sha256") != BASELINE_SHA256
            or not set(receipt.get("changed_sources", {})).issubset(FILES)):
        raise ValueError("candidate patch identity or scope mismatch")
    with zipfile.ZipFile(bundle) as archive:
        for entry in archive.infolist():
            if entry.is_dir() or not (output / entry.filename).resolve().is_relative_to(output):
                raise ValueError("unsafe baseline archive member")
        archive.extractall(output)
    baseline = json.loads((output / "h4-upload-inventory.json").read_text())["files"]
    for name, expected in baseline.items():
        if digest(output / name) != expected:
            raise ValueError("baseline extraction mismatch: " + name)
    subprocess.run(["git", "apply", "--check", str(patch)], cwd=output, check=True)
    subprocess.run(["git", "apply", str(patch)], cwd=output, check=True)
    for name, expected in baseline.items():
        if name not in receipt["changed_sources"] and digest(output / name) != expected:
            raise ValueError("patch changed an undeclared baseline file: " + name)
    permitted = set(baseline) | set(receipt["changed_sources"]) | {"h4-upload-inventory.json"}
    for path in output.rglob("*"):
        if path.is_symlink() or (path.is_file() and path.relative_to(output).as_posix() not in permitted):
            raise ValueError("patch added an undeclared file or link")
    for name, identity in receipt["changed_sources"].items():
        # Patch application normalizes line endings; compare source text as well as the patch digest.
        if name not in FILES or not (output / name).is_file():
            raise ValueError("missing patched source: " + name)
        text = (output / name).read_text(encoding="utf-8")
        if hashlib.sha256(text.encode("utf-8")).hexdigest() != identity.get("candidate_text_sha256"):
            raise ValueError("patched source differs from declared candidate: " + name)
    root = Path(__file__).resolve().parents[2]
    for module in ("aether-sstable", "aether-engine", "aether-lsm", "aether-training-cache"):
        source = root / "modules" / module / "src/test"
        shutil.copytree(source, output / "modules" / module / "src/test", dirs_exist_ok=True)
    for source in (root / "scripts/h4").rglob("*"):
        if source.is_file() and "__pycache__" not in source.parts:
            target = output / source.relative_to(root)
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(source, target)
    sources = {path.relative_to(output).as_posix(): digest(path) for path in output.rglob("*") if path.is_file()}
    write_json(output / "h4-candidate-inventory.json", {
        "schema": "aether-h4-storage-candidate-source-v1", "baseline_transport_sha256": BASELINE_SHA256,
        "patch_sha256": digest(patch), "sources_sha256": canonical_hash(sources), "files": sources,
        "scope": "H4-only; H2 unchanged", "capacity_qualified": False,
        "primary_h4_eligible": False, "out_of_core_verified": False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--patch", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    prepare(args.bundle, args.patch, args.output)


if __name__ == "__main__":
    main()
