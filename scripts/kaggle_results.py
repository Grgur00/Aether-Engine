"""Standard-library-only result bundling, also embedded in prepared Kaggle notebooks."""
import hashlib
import shutil
import zipfile
from pathlib import Path


def bundle_results(results, destination, runtime=None):
    results, destination = Path(results).resolve(), Path(destination).resolve()
    if destination.is_relative_to(results):
        raise ValueError("results ZIP must be outside the results directory")
    results.mkdir(parents=True, exist_ok=True)
    destination.parent.mkdir(parents=True, exist_ok=True)
    if runtime is not None and Path(runtime).is_file():
        shutil.copy2(runtime, results / "aether-paper-runtime.json")
    files = []
    for path in sorted(results.rglob("*")):
        if path.is_symlink() or not path.resolve().is_relative_to(results):
            raise ValueError(f"result path is not an owned regular path: {path}")
        if path.is_file() and path != results / "SHA256SUMS":
            files.append(path)
    def digest(stream):
        value = hashlib.sha256()
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            value.update(block)
        return value.hexdigest()
    hashes = {}
    for path in files:
        with path.open("rb") as stream:
            hashes[path.relative_to(results).as_posix()] = digest(stream)
    manifest = results / "SHA256SUMS"
    manifest.write_text("".join(f"{value}  {name}\n" for name, value in hashes.items()), encoding="utf-8")
    files.append(manifest)
    with manifest.open("rb") as stream:
        hashes["SHA256SUMS"] = digest(stream)
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, allowZip64=True) as archive:
            for path in files:
                archive.write(path, path.relative_to(results).as_posix())
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None or set(archive.namelist()) != set(hashes):
                raise RuntimeError("results ZIP verification failed")
            for name, expected in hashes.items():
                with archive.open(name) as stream:
                    if digest(stream) != expected:
                        raise RuntimeError(f"results ZIP checksum mismatch: {name}")
        temporary.replace(destination)
    finally:
        temporary.unlink(missing_ok=True)
    return destination


def include_notebook_log(destination, log):
    """Fold Kaggle CLI's extra platform log into the ZIP without extracting it."""
    destination, log = Path(destination), Path(log)
    if not log.is_file():
        return
    temporary = destination.with_suffix(destination.suffix + ".tmp")
    hashes = {}
    try:
        with zipfile.ZipFile(destination) as source, zipfile.ZipFile(temporary, "w", zipfile.ZIP_DEFLATED) as target:
            for entry in source.infolist():
                if entry.is_dir() or entry.filename in {"SHA256SUMS", "kaggle-notebook.log"}:
                    continue
                digest = hashlib.sha256()
                with source.open(entry) as incoming, target.open(entry, "w", force_zip64=True) as outgoing:
                    for block in iter(lambda: incoming.read(1024 * 1024), b""):
                        digest.update(block)
                        outgoing.write(block)
                hashes[entry.filename] = digest.hexdigest()
            digest = hashlib.sha256()
            with log.open("rb") as incoming, target.open("kaggle-notebook.log", "w", force_zip64=True) as outgoing:
                for block in iter(lambda: incoming.read(1024 * 1024), b""):
                    digest.update(block)
                    outgoing.write(block)
            hashes["kaggle-notebook.log"] = digest.hexdigest()
            target.writestr("SHA256SUMS", "".join(f"{value}  {name}\n" for name, value in hashes.items()))
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise RuntimeError("results ZIP verification failed after adding notebook log")
        temporary.replace(destination)
        log.unlink()
    finally:
        temporary.unlink(missing_ok=True)
