"""Export an isolated H4 candidate as a reviewable patch against verified v3 source."""
import argparse
import difflib
import hashlib
import json
from pathlib import Path
import zipfile

from .common import digest, write_json

BASELINE_SHA256 = "3488c44729b081061e3e4d92fa66bfbd8956940be3a6660aed3ff92b3f552521"
FILES = (
    "modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableReader.java",
    "modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableVerifier.java",
    "modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentAetherDatabase.java",
    "modules/aether-engine/src/main/java/io/aetherdb/engine/Aether.java",
    "modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionDroppingIterator.java",
    "modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCache.java",
    "modules/aether-engine/src/test/java/io/aetherdb/engine/H4StreamingStorageTest.java",
    "modules/aether-lsm/src/test/java/io/aetherdb/lsm/compaction/H4StreamingRetentionTest.java",
    "modules/aether-sstable/src/test/java/io/aetherdb/sstable/H4BoundedBlockCacheTest.java",
)


def export(bundle, candidate, output):
    bundle, candidate, output = Path(bundle), Path(candidate), Path(output)
    if digest(bundle) != BASELINE_SHA256:
        raise ValueError("baseline transport differs from verified H4 CPU preflight v3")
    patches, changed = [], {}
    with zipfile.ZipFile(bundle) as archive:
        inventory = json.loads(archive.read("h4-upload-inventory.json"))["files"]
        for name in FILES:
            before = archive.read(name).decode("utf-8").splitlines(keepends=True) if name in inventory else []
            after = (candidate / name).read_text(encoding="utf-8").splitlines(keepends=True)
            before = [line.rstrip("\r\n") + "\n" for line in before]
            after = [line.rstrip("\r\n") + "\n" for line in after]
            if before != after:
                patches.extend(difflib.unified_diff(before, after, fromfile="a/" + name if before else "/dev/null", tofile="b/" + name))
                changed[name] = {"baseline_sha256": inventory.get(name), "candidate_sha256": digest(candidate / name),
                                 "candidate_text_sha256": hashlib.sha256("".join(after).encode("utf-8")).hexdigest()}
        for name, expected in inventory.items():
            if name not in FILES and digest(candidate / name) != expected:
                raise ValueError("unreviewed candidate change: " + name)
    if not patches:
        raise ValueError("candidate has no storage revision")
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text("".join(patches), encoding="utf-8", newline="\n")
    write_json(output.with_suffix(".json"), {
        "schema": "aether-h4-storage-candidate-patch-v1", "baseline_transport_sha256": BASELINE_SHA256,
        "patch_sha256": digest(output), "changed_sources": changed,
        "scope": "H4-only experimental candidate; not frozen H2", "capacity_qualified": False,
        "primary_h4_eligible": False, "out_of_core_verified": False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--candidate", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    export(args.bundle, args.candidate, args.output)


if __name__ == "__main__":
    main()
