"""Package source, manifests and optional measurements; exclude datasets and caches."""
import argparse
import json
import subprocess
import zipfile
from pathlib import Path

from paper_common import ROOT, capture, sha256

EXCLUDED = {".git", ".gradle", ".venv", "__pycache__", "node_modules", "build", "bin", ".idea", ".vscode", ".pytest_cache", ".mypy_cache"}
DIRECTORIES = ("build-logic", "gradle", "modules", "examples", "clients/python", "scripts", "configs", "env", "docker", "paper", "kaggle")
ROOT_FILES = ("README.md", "LICENSE", "CITATION.cff", "Makefile", "build.gradle.kts", "settings.gradle.kts",
              "gradle.properties", "gradlew", "gradlew.bat", ".dockerignore", ".editorconfig", ".gitattributes")


def package(output, results=None):
    paths = {ROOT / name for name in ROOT_FILES if (ROOT / name).is_file()}
    for name in DIRECTORIES:
        for path in (ROOT / name).rglob("*"):
            relative = path.relative_to(ROOT)
            if not path.is_file() or path.is_symlink() or any(part in EXCLUDED for part in relative.parts):
                continue
            if str(relative).replace("\\", "/").startswith("clients/python/") and path.suffix not in {".py", ".toml", ".md"}:
                continue
            paths.add(path)
    git = ["git", "-c", f"safe.directory={ROOT.as_posix()}"]
    commit, status = capture(git + ["rev-parse", "HEAD"]), capture(git + ["status", "--porcelain"])
    provenance = {"schema": "aether-source-archive-v1", "gitCommit": commit, "gitStatus": status,
                  "sourceClean": status.get("returncode") == 0 and not status.get("stdout"),
                  "files": {path.relative_to(ROOT).as_posix(): sha256(path) for path in sorted(paths)}}
    output = Path(output).resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in sorted(paths):
            archive.write(path, path.relative_to(ROOT).as_posix())
        archive.writestr("artifact-provenance.json", json.dumps(provenance, indent=2) + "\n")
        if results is not None:
            results = Path(results).resolve()
            for path in sorted(results.rglob("*")):
                if path.is_file() and not path.is_symlink() and path.suffix in {".json", ".csv", ".log", ".pdf", ".png", ".tex", ".txt"}:
                    archive.write(path, "results/" + path.relative_to(results).as_posix())
    output.with_suffix(output.suffix + ".sha256").write_text(f"{sha256(output)}  {output.name}\n", encoding="utf-8")
    print(f"Source artifact: {output} ({len(paths)} files; sourceClean={provenance['sourceClean']})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=ROOT / "build/aether-paper-artifact.zip")
    parser.add_argument("--results", type=Path)
    args = parser.parse_args()
    package(args.output, args.results)
