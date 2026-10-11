"""Bind retrieval inputs to immutable official-PCam preparation evidence."""
import hashlib
import json
from pathlib import Path

from .common import digest
from . import prepare_pcam_manifest as pcam


def bind_inputs(data, prepared, protocol_path, *, verify_source=True):
    data, prepared = Path(data).resolve(strict=True), Path(prepared).resolve(strict=True)
    protocol_path = Path(protocol_path).resolve(strict=True)
    inventory = json.loads((prepared / "sha256.json").read_text())
    required = {"manifest.json", "sample_hashes.json", "transform_identity.json"}
    if set(inventory) != required or any(digest(prepared / name) != inventory[name] for name in required):
        raise ValueError("prepared PCam evidence checksum mismatch")
    protocol = json.loads(protocol_path.read_text())
    manifest = json.loads((prepared / "manifest.json").read_text())
    rows = json.loads((prepared / "sample_hashes.json").read_text())
    transform = json.loads((prepared / "transform_identity.json").read_text())
    transform_hash = transform.pop("sha256")
    if (manifest["schema"] != "aether-h4-pcam-manifest-v1" or manifest["dataset_verified"] is not True
            or manifest["official_samples"] != pcam.SAMPLES
            or manifest["protocol_sha256"] != digest(protocol_path)
            or manifest["sample_hashes_sha256"] != inventory["sample_hashes.json"]
            or transform["implementation_sha256"] != digest(Path(pcam.__file__))
            or transform["output"] != protocol["artifact"]
            or pcam.canonical_hash(transform) != transform_hash
            or manifest["transform_sha256"] != transform_hash):
        raise ValueError("PCam source/protocol/transform provenance mismatch")
    if len({row["sample_id"] for row in rows}) != len(rows):
        raise ValueError("duplicate prepared PCam sample ID")
    for row in rows:
        index = row["sample_id"]
        if type(index) is not int or not 0 <= index < pcam.SAMPLES or row["label"] not in (0, 1):
            raise ValueError("invalid PCam sample identity/label")
        expected = pcam.canonical_hash(dict(split="train", index=index,
                                           source_sha256=row["source_sha256"], transform_sha256=transform_hash))
        if row["artifact_id"] != expected:
            raise ValueError("prepared artifact identity differs from source/transform")
    selected, counts = pcam.nested_subsets(pcam.SAMPLES, protocol["memory_limit_bytes"],
                                         protocol["ratios"], protocol["subset_seed"])
    import numpy as np
    if len(manifest["conditions"]) != len(counts) or [row["sample_id"] for row in rows] != list(selected):
        raise ValueError("prepared subsets differ from frozen seeded policy")
    for number, (condition, count) in enumerate(zip(manifest["conditions"], counts)):
        ids = [int(index) for index in selected[:count]]
        permutation = np.random.Generator(np.random.PCG64(protocol["lookup_seed"] + number)).permutation(count)
        if (condition["sequential_ids"] != ids or condition["shuffled_ids"] != [ids[int(index)] for index in permutation]
                or condition["samples"] != count or condition["payload_bytes"] != count * pcam.PAYLOAD_BYTES
                or condition["requested_ratio"] != protocol["ratios"][number]):
            raise ValueError("prepared workload order/size differs from protocol")
    if set(manifest["source_files"]) != set(pcam.OFFICIAL):
        raise ValueError("official source inventory incomplete")
    if verify_source:
        for name, (size, md5) in pcam.OFFICIAL.items():
            pcam.verify_archive(data / name, size, md5)
            identity = manifest["source_files"][name]
            extracted_hash, extracted_size = pcam.extracted_identity(data / name)
            if (digest(data / name) != identity["gzip_sha256"]
                    or extracted_hash != identity["hdf5_sha256"]
                    or (data / name[:-3]).stat().st_size != extracted_size
                    or digest(data / name[:-3]) != extracted_hash):
                raise ValueError("actual PCam source differs from prepared manifest")
    return dict(protocol=protocol, manifest=manifest, rows=rows,
                protocol_sha256=digest(protocol_path), manifest_sha256=inventory["manifest.json"],
                dataset_sha256=pcam.canonical_hash(manifest["source_files"]),
                transform_sha256=transform_hash, source_verified=verify_source)


def sample_stream(data, rows):
    import h5py
    import numpy as np
    data = Path(data)
    with h5py.File(data / "camelyonpatch_level_2_split_train_x.h5", "r") as images, \
            h5py.File(data / "camelyonpatch_level_2_split_train_y.h5", "r") as labels:
        x, y = images["x"], labels["y"]
        if x.shape != (pcam.SAMPLES, 96, 96, 3) or x.dtype != np.uint8 or y.shape != (pcam.SAMPLES, 1, 1, 1):
            raise ValueError("actual PCam HDF5 schema mismatch")
        for row in rows:
            image = x[row["sample_id"]]
            payload = pcam.preprocess(image).tobytes(order="C")
            if (hashlib.sha256(image.tobytes(order="C")).hexdigest() != row["source_sha256"]
                    or int(y[row["sample_id"], 0, 0, 0]) != row["label"]
                    or hashlib.sha256(payload).hexdigest() != row["artifact_sha256"]):
                raise ValueError("actual source/label/artifact differs from manifest")
            yield row, payload
