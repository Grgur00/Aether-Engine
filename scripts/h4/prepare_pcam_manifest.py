"""Acquire checksum-verified official PCam training data and freeze H4 inputs.

Downloading, extraction and manifest hashing are outside backend timing.
"""
import argparse
import gzip
import hashlib
import json
import os
import shutil
import tempfile
import time
import urllib.request
from pathlib import Path

from .common import digest, write_json

OFFICIAL = {
    "camelyonpatch_level_2_split_train_x.h5.gz": (6421353462, "1571f514728f59376b705fc836ff4b63"),
    "camelyonpatch_level_2_split_train_y.h5.gz": (21378, "35c2d7259d906cfc8143347bb8e05be7"),
}
MIRROR = "https://zenodo.org/api/records/2546921/files/"
SAMPLES = 262144
PAYLOAD_BYTES = 110592
EXTRACTION_LIMIT = 8 * 1024 ** 3
RESERVE = 2 * 1024 ** 3


def md5(path):
    checksum = hashlib.md5(usedforsecurity=False)
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 ** 2), b""):
            checksum.update(chunk)
    return checksum.hexdigest()


def verify_archive(path, size, expected):
    path = Path(path)
    if path.stat().st_size != size or md5(path) != expected:
        raise ValueError("official PCam archive size/checksum mismatch: " + path.name)


def download_file(destination, url, size, expected, *, timeout_seconds=900):
    if timeout_seconds <= 0:
        raise ValueError("download deadline must be positive")
    destination = Path(destination)
    if destination.exists():
        verify_archive(destination, size, expected)
        return
    temporary = destination.with_name(destination.name + ".partial")
    deadline = time.monotonic() + timeout_seconds
    try:
        checksum = hashlib.md5(usedforsecurity=False)
        received = 0
        if temporary.exists():
            with temporary.open("rb") as existing:
                for chunk in iter(lambda: existing.read(1024 ** 2), b""):
                    received += len(chunk)
                    checksum.update(chunk)
            if received > size:
                raise ValueError("partial download exceeds official size")
        announced = received
        with temporary.open("ab") as stream:
            while received < size:
                if time.monotonic() >= deadline:
                    raise TimeoutError("PCam transfer deadline reached; verified-size partial retained for resumption")
                end = min(received + 8 * 1024 ** 2, size) - 1
                request = urllib.request.Request(url, headers={
                    "User-Agent": "AetherML-H4-source-preparation/1", "Range": f"bytes={received}-{end}"})
                with urllib.request.urlopen(request, timeout=30) as response:
                    if size > 8 * 1024 ** 2 or received:
                        if response.status != 206 or response.headers.get("Content-Range") != f"bytes {received}-{end}/{size}":
                            raise ValueError("official mirror did not honor bounded range request")
                    remaining = end - received + 1
                    range_deadline = min(time.monotonic() + 90, deadline)
                    while remaining:
                        if time.monotonic() > range_deadline:
                            raise TimeoutError("PCam transfer deadline reached; partial retained for resumption")
                        chunk = response.read(min(64 * 1024, remaining))
                        if not chunk:
                            raise OSError("truncated PCam range; partial retained")
                        stream.write(chunk)
                        received += len(chunk)
                        remaining -= len(chunk)
                        checksum.update(chunk)
                    if response.read(1):
                        raise ValueError("download exceeds requested range")
                stream.flush()
                os.fsync(stream.fileno())
                if received - announced >= 256 * 1024 ** 2:
                    print(f"{destination.name}: {received}/{size} bytes", flush=True)
                    announced = received
        if received != size or checksum.hexdigest() != expected:
            raise ValueError("download does not match official PCam size/checksum")
        if destination.exists():
            raise FileExistsError("destination appeared during download")
        os.replace(temporary, destination)
        print("Verified download: " + destination.name, flush=True)
    except ValueError:
        temporary.unlink(missing_ok=True)
        raise


def extract_archive(archive, maximum=EXTRACTION_LIMIT):
    archive = Path(archive)
    destination = archive.with_suffix("")
    if destination.exists():
        checksum, total = extracted_identity(archive, maximum)
        if destination.stat().st_size != total or digest(destination) != checksum:
            raise ValueError("existing extracted file differs from verified gzip")
        return {"file": destination.name, "bytes": total, "sha256": checksum}
    expected = hashlib.sha256()
    descriptor, temporary = tempfile.mkstemp(prefix=destination.name + ".", dir=destination.parent)
    try:
        total = 0
        with os.fdopen(descriptor, "wb") as output, gzip.open(archive, "rb") as source:
            while chunk := source.read(1024 ** 2):
                total += len(chunk)
                if total > maximum:
                    raise ValueError("PCam extraction exceeds safe size bound")
                output.write(chunk)
                expected.update(chunk)
            output.flush()
            os.fsync(output.fileno())
        checksum = expected.hexdigest()
        if destination.exists():
            if digest(destination) != checksum:
                raise ValueError("existing extracted file differs from verified gzip")
        else:
            os.replace(temporary, destination)
        return {"file": destination.name, "bytes": total, "sha256": checksum}
    finally:
        if os.path.exists(temporary):
            os.unlink(temporary)


def extracted_identity(archive, maximum=EXTRACTION_LIMIT):
    checksum, total = hashlib.sha256(), 0
    with gzip.open(archive, "rb") as source:
        while chunk := source.read(1024 ** 2):
            total += len(chunk)
            if total > maximum:
                raise ValueError("PCam extraction exceeds safe size bound")
            checksum.update(chunk)
    return checksum.hexdigest(), total


def acquire(directory, *, timeout_seconds=900):
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    pending = sum(max(0, size - ((directory / (name + ".partial")).stat().st_size
                               if (directory / (name + ".partial")).exists() else 0))
                  for name, (size, _) in OFFICIAL.items() if not (directory / name).exists())
    # Leave room for bounded extraction and small manifests, not backend stores.
    extraction = EXTRACTION_LIMIT if any(not (directory / name[:-3]).exists() for name in OFFICIAL) else 0
    required = pending + extraction + RESERVE
    free = shutil.disk_usage(directory).free
    if free < required:
        raise RuntimeError(f"PCam source preparation needs {required} free bytes; only {free} available")
    sources = {}
    for name, (size, checksum) in sorted(OFFICIAL.items(), key=lambda item: item[1][0]):
        url = MIRROR + name + "/content"
        download_file(directory / name, url, size, checksum, timeout_seconds=timeout_seconds)
        sources[name] = {"url": url, "bytes": size, "md5": checksum,
                         "sha256": digest(directory / name),
                         "extracted": extract_archive(directory / name)}
    receipt = {"schema": "aether-h4-pcam-source-v1", "source": "https://github.com/basveeling/pcam",
               "mirror_record": "https://zenodo.org/records/2546921", "official_gzip_verified": True,
               "files": sources}
    receipt_path = directory / "source-receipt.json"
    if receipt_path.exists() and json.loads(receipt_path.read_text()) != receipt:
        raise ValueError("existing PCam source receipt differs")
    write_json(receipt_path, receipt)
    return receipt


def canonical_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":"),
                                     allow_nan=False).encode("ascii")).hexdigest()


def source_status(directory):
    directory = Path(directory)
    files = {}
    for name, (size, expected) in OFFICIAL.items():
        archive, partial = directory / name, directory / (name + ".partial")
        entry = {"expected_bytes": size, "official_archive_verified": False,
                 "partial_bytes": partial.stat().st_size if partial.exists() else 0}
        if archive.exists():
            try:
                verify_archive(archive, size, expected)
                entry.update(official_archive_verified=True, sha256=digest(archive), md5=expected)
            except (OSError, ValueError) as error:
                entry["error"] = str(error)
        files[name] = entry
    return {"schema": "aether-h4-source-preparation-status-v1", "files": files,
            "all_official_archives_verified": all(entry["official_archive_verified"] for entry in files.values()),
            "primary_h4_eligible": False}


def preprocess(image):
    import numpy as np
    if image.shape != (96, 96, 3) or image.dtype != np.uint8:
        raise ValueError("PCam sample must be uint8 RGB 96x96")
    return np.ascontiguousarray((image.astype(np.float32) / np.float32(255)).transpose(2, 0, 1), dtype="<f4")


def nested_subsets(sample_count, memory_limit, ratios, seed):
    import numpy as np
    if type(seed) is not int or seed < 0 or type(memory_limit) is not int or memory_limit <= 0:
        raise ValueError("invalid subset seed or memory budget")
    counts = [int(memory_limit * ratio // PAYLOAD_BYTES) for ratio in ratios]
    if any(count <= 0 or count > sample_count for count in counts) or counts != sorted(set(counts)):
        raise ValueError("subset sizes must be increasing, nonempty and within the official split")
    permutation = np.random.Generator(np.random.PCG64(seed)).permutation(sample_count)
    return permutation[:counts[-1]], counts


def prepare(directory, output, protocol_path):
    import h5py
    import numpy as np
    directory, output = Path(directory).resolve(), Path(output).resolve()
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("manifest output must be empty; preserve frozen evidence")
    protocol_path = Path(protocol_path)
    protocol = json.loads(protocol_path.read_text())
    sources = {}
    for name, (size, expected) in OFFICIAL.items():
        verify_archive(directory / name, size, expected)
        extracted_hash, extracted_size = extracted_identity(directory / name)
        extracted = directory / name[:-3]
        if extracted.stat().st_size != extracted_size or digest(extracted) != extracted_hash:
            raise ValueError("HDF5 content differs from verified official gzip")
        sources[name] = {"gzip_sha256": digest(directory / name),
                         "hdf5_sha256": extracted_hash}
    transform = {"schema": "aether-h4-transform-v1", "input": "uint8 RGB [96,96,3]",
                 "output": protocol["artifact"], "implementation_sha256": digest(Path(__file__)),
                 "identity_policy": "official train index + source image SHA256 + transform SHA256"}
    transform_hash = canonical_hash(transform)
    selected, counts = nested_subsets(SAMPLES, protocol["memory_limit_bytes"], protocol["ratios"], protocol["subset_seed"])
    rows = {}
    with h5py.File(directory / "camelyonpatch_level_2_split_train_x.h5", "r") as images, \
            h5py.File(directory / "camelyonpatch_level_2_split_train_y.h5", "r") as labels:
        x, y = images["x"], labels["y"]
        if x.shape != (SAMPLES, 96, 96, 3) or x.dtype != np.uint8 or y.shape != (SAMPLES, 1, 1, 1):
            raise ValueError("official PCam training HDF5 schema mismatch")
        for start in range(0, SAMPLES, 65536):
            if not np.isin(y[start:start + 65536], [0, 1]).all():
                raise ValueError("PCam labels must be binary")
        # Read in source order, then restore the frozen subset order in metadata.
        for done, index in enumerate(sorted(int(index) for index in selected), 1):
            image = x[index]
            source_hash = hashlib.sha256(image.tobytes(order="C")).hexdigest()
            payload = preprocess(image).tobytes(order="C")
            identity = canonical_hash({"split": "train", "index": index,
                                       "source_sha256": source_hash, "transform_sha256": transform_hash})
            rows[index] = {"sample_id": index, "label": int(y[index, 0, 0, 0]),
                           "source_sha256": source_hash, "artifact_id": identity,
                           "artifact_sha256": hashlib.sha256(payload).hexdigest()}
            if done % 4096 == 0:
                print(f"Hashed {done}/{len(selected)} PCam artifacts", flush=True)
    conditions = []
    for condition, (ratio, count) in enumerate(zip(protocol["ratios"], counts)):
        ids = [int(index) for index in selected[:count]]
        order = np.random.Generator(np.random.PCG64(protocol["lookup_seed"] + condition)).permutation(count)
        conditions.append({"requested_ratio": ratio, "samples": count, "payload_bytes": count * PAYLOAD_BYTES,
                           "actual_payload_memory_ratio": count * PAYLOAD_BYTES / protocol["memory_limit_bytes"],
                           "sequential_ids": ids, "shuffled_ids": [ids[int(position)] for position in order]})
    write_json(output / "transform_identity.json", dict(transform, sha256=transform_hash))
    write_json(output / "sample_hashes.json", [rows[int(index)] for index in selected])
    manifest = {"schema": "aether-h4-pcam-manifest-v1", "dataset_verified": True,
                "official_samples": SAMPLES, "source_files": sources, "conditions": conditions,
                "subset_seed": protocol["subset_seed"], "lookup_seed": protocol["lookup_seed"],
                "rng": "numpy.PCG64", "numpy_version": np.__version__, "h5py_version": h5py.__version__,
                "protocol_sha256": digest(protocol_path), "transform_sha256": transform_hash,
                "sample_hashes_sha256": digest(output / "sample_hashes.json")}
    write_json(output / "manifest.json", manifest)
    write_json(output / "sha256.json", {name: digest(output / name) for name in
               ("manifest.json", "sample_hashes.json", "transform_identity.json")})
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--data", type=Path, required=True)
    parser.add_argument("--download", action="store_true")
    parser.add_argument("--max-download-seconds", type=int, default=900)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--protocol", type=Path, default=Path(__file__).with_name("protocol.json"))
    args = parser.parse_args()
    if not args.download and args.output is None:
        parser.error("request --download and/or --output")
    if args.max_download_seconds <= 0:
        parser.error("download deadline must be positive")
    try:
        if args.download:
            acquire(args.data, timeout_seconds=args.max_download_seconds)
        if args.output:
            prepare(args.data, args.output, args.protocol)
    except (OSError, ValueError, RuntimeError) as error:
        status = source_status(args.data)
        status.update(completed=False, error_type=type(error).__name__, error=str(error))
        write_json(args.data.parent / "preparation-status.json", status)
        print("PCam preparation incomplete: " + str(error), flush=True)
        return 2
    status = source_status(args.data)
    status.update(completed=True, manifest_created=args.output is not None)
    write_json(args.data.parent / "preparation-status.json", status)
    print("PCam source preparation complete; no storage benchmark was run", flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
