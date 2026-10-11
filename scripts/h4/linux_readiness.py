"""Read-only Linux host/source readiness; never qualifies retrieval performance."""
import argparse
import json
import platform
import shutil
from pathlib import Path

from .common import digest, write_json
from .kaggle_preflight import command
from .memory_controller import discover_parent, snapshot
from .prepare_pcam_manifest import canonical_hash


def block_storage(mount_result, devices_result):
    if mount_result.get("returncode") != 0 or devices_result.get("returncode") != 0:
        raise ValueError("required mount/block-device evidence unavailable")
    mounts = json.loads(mount_result["stdout"])["filesystems"]
    if len(mounts) != 1:
        raise ValueError("scratch mount is ambiguous")
    mount = mounts[0]
    if mount["fstype"] in ("tmpfs", "ramfs", "overlay", "nfs", "nfs4", "cifs"):
        raise ValueError("scratch is not a directly attributable block-backed filesystem")
    def walk(rows):
        for row in rows:
            yield row
            yield from walk(row.get("children", []))
    matches = [row for row in walk(json.loads(devices_result["stdout"])["blockdevices"])
               if row.get("maj:min") == mount.get("maj:min")]
    if not matches or any(row.get("type") in ("loop", "rom") for row in matches):
        raise ValueError("scratch mount lacks supported block-device attribution")
    if "rw" not in mount["options"].split(","):
        raise ValueError("scratch mount is read-only")
    return dict(mount=mount, devices=matches,
                limitation="guest block-device attribution does not establish hypervisor cache misses")


def candidate_identity(root):
    root = Path(root).resolve()
    receipt = json.loads((root / "h4-candidate-inventory.json").read_text())
    patch = root / "scripts/h4/storage-revision/bounded-storage-v1.patch"
    declared = json.loads(patch.with_suffix(".json").read_text())
    if (receipt.get("schema") != "aether-h4-storage-candidate-source-v1"
            or receipt.get("patch_sha256") != digest(patch)
            or declared.get("patch_sha256") != digest(patch)
            or receipt.get("baseline_transport_sha256") != declared.get("baseline_transport_sha256")):
        raise ValueError("isolated H4 candidate/patch identity differs")
    files = receipt.get("files", {})
    if not files or receipt.get("sources_sha256") != canonical_hash(files):
        raise ValueError("candidate inventory identity is invalid")
    for name, expected in files.items():
        path = (root / name).resolve(strict=True)
        if not path.is_relative_to(root) or (root / name).is_symlink() or digest(path) != expected:
            raise ValueError("candidate source changed: " + name)
    import paper_common
    if paper_common.ROOT.resolve() != root:
        raise ValueError("run readiness from the isolated candidate, not the H2/root checkout")
    paper_common.java_classpath()
    return dict(patch_sha256=digest(patch), candidate_sources_sha256=receipt["sources_sha256"],
                java_build_sources=paper_common.java_build_sources(root))


def delegation_identity():
    parent = discover_parent()
    if "memory" not in (parent / "cgroup.subtree_control").read_text().split():
        raise RuntimeError("memory controller is not delegated; no parent changes attempted")
    return dict(parent=str(parent), state=snapshot(parent),
                limitation="read-only discovery; child creation and process migration still require the capability probe")


def run(output, scratch):
    output, scratch = Path(output).resolve(), Path(scratch).resolve(strict=True)
    if not scratch.is_dir():
        raise ValueError("existing scratch directory required")
    output.mkdir(parents=True, exist_ok=False)
    report = dict(schema="aether-h4-linux-readiness-v1", readiness_passed=False,
                  primary_h4_eligible=False, out_of_core_verified=False, parent_modified=False,
                  errors=[], checks={})
    if platform.system() != "Linux":
        report["errors"].append("controlled execution requires Linux")
    checks = report["checks"]
    checks["findmnt"] = command(["findmnt", "-J", "-T", str(scratch), "-o", "SOURCE,FSTYPE,OPTIONS,MAJ:MIN,TARGET"])
    checks["lsblk"] = command(["lsblk", "-J", "-o", "NAME,TYPE,MAJ:MIN,SIZE,FSTYPE,MOUNTPOINTS"])
    for name, check in (("block_storage", lambda: block_storage(checks["findmnt"], checks["lsblk"])),
                        ("candidate", lambda: candidate_identity(Path(__file__).resolve().parents[2])),
                        ("delegation", delegation_identity)):
        try:
            checks[name] = check()
        except (OSError, RuntimeError, ValueError, KeyError, TypeError) as error:
            report["errors"].append(name + ": " + str(error))
    usage = shutil.disk_usage(scratch)
    checks["disk"] = dict(free_bytes=usage.free, required_bytes=3 * 8 * 1024 ** 3 + 2 * 1024 ** 3,
                          limitation="admission-only reserve; official inputs/prepared artifacts need additional capacity")
    if usage.free < checks["disk"]["required_bytes"]:
        report["errors"].append("insufficient safe capacity for 8 GiB admission")
    report["readiness_passed"] = not report["errors"]
    report["next_gates"] = ["delegated Python/JVM capability probe and cleanup", "1/2/4/8 GiB capacity receipts",
                            "official PCam source validation", "measured reclaim/refault and physical read windows"]
    write_json(output / "readiness.json", report)
    write_json(output / "sha256.json", {"readiness.json": digest(output / "readiness.json")})
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    args = parser.parse_args()
    result = run(args.output, args.scratch)
    print(json.dumps(dict(readiness_passed=result["readiness_passed"], errors=result["errors"])))
    return 0 if result["readiness_passed"] else 2


if __name__ == "__main__":
    raise SystemExit(main())
