"""Explicit multi-session H2 amendment; immutable V0 and original worker harness."""
import argparse
import hashlib
import json
import os
import shutil
import sys
import tempfile
import zipfile
from pathlib import Path, PurePosixPath

PARTITIONS = [[0, 9], [9, 18], [18, 24]]


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def file_hash(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def assignments(protocol, session):
    if type(session) is not int or not 1 <= session <= 3:
        raise ValueError("session must be 1, 2 or 3")
    start, end = PARTITIONS[session - 1]
    return protocol["schedule"][start:end]


def extract_evidence(path, destination):
    destination = Path(destination).resolve()
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("duplicate ZIP entries")
        for entry in archive.infolist():
            target = (destination / entry.filename).resolve()
            name = PurePosixPath(entry.filename)
            if (entry.orig_filename != entry.filename or not target.is_relative_to(destination)
                    or "\\" in entry.filename or ":" in entry.filename
                    or name.is_absolute() or ".." in name.parts
                    or (entry.external_attr >> 16) & 0o170000 == 0o120000):
                raise ValueError("unsafe evidence ZIP entry")
        archive.extractall(destination)


def validate_session(root, protocol, session, engine):
    root = Path(root)
    meta = engine.load_campaign(root)
    if meta["protocol"] != protocol:
        raise ValueError("session treatment protocol changed")
    if engine.verify_frozen(root / "frozen", check_source=False) != protocol:
        raise ValueError("archived frozen bundle changed")
    reference = read(root / "input-preflight.json")
    for key in ("manifests", "referenceHashes", "transformIdentity"):
        if reference[key] != protocol[key]:
            raise ValueError("session input identity changed")
    expected = assignments(protocol, session)
    if {p.name for p in (root / "blocks").iterdir() if p.is_dir()} != {f"{a['block']:02d}" for a in expected}:
        raise ValueError("session has missing, duplicate or unscheduled blocks")
    engine.archived_attempt(root / "protocol-preflight", meta, protocol, reference,
        dict(block=0, seed=protocol["config"]["seed"], backendOrder=list(engine.BACKENDS)),
        measurement_role="excluded preflight")
    blocks = [engine.archived_attempt(root / "blocks" / f"{a['block']:02d}", meta,
        protocol, reference, a, measurement_role="confirmatory") for a in expected]
    return meta, blocks


def checkpoint(root, destination, engine):
    root, destination = Path(root).resolve(), Path(destination).resolve()
    if destination.is_relative_to(root):
        raise ValueError("checkpoint must be outside evidence directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    fd, temporary = tempfile.mkstemp(dir=destination.parent, suffix=".zip")
    os.close(fd)
    try:
        inventory = {}
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(root.rglob("*")):
                if path.is_symlink():
                    raise ValueError("evidence symlink forbidden")
                if path.is_file() and path.name != ".campaign.lock":
                    name = "h2-confirmatory/" + path.relative_to(root).as_posix()
                    inventory[name] = file_hash(path)
                    archive.write(path, name)
            archive.writestr("checkpoint.json", json.dumps(dict(schema="aether-h2-session-checkpoint-v1",
                files=inventory, inventoryHash=engine.digest(inventory),
                inference="withheld until all 24 scheduled blocks", continuation="explicit session amendment")))
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None or any(hashlib.sha256(archive.read(n)).hexdigest() != h for n, h in inventory.items()):
                raise ValueError("checkpoint verification failed")
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(f"Verified amended H2 checkpoint: {destination}", flush=True)


def run(args, engine):
    protocol = engine.verify_frozen(args.frozen)
    amendment = read(args.amendment)
    if (amendment["schema"] != "aether-h2-session-amendment-v1"
            or amendment["originalProtocolHash"] != engine.digest(protocol)
            or amendment["partitions"] != PARTITIONS
            or amendment["controllerSha256"] != file_hash(__file__)
            or amendment["firstSessionEvidenceSha256"] != file_hash(args.previous)):
        raise ValueError("amendment, controller or previous evidence drift")
    if args.session != 2:
        raise ValueError("this launch authorizes only session 2, blocks 10-18")
    engine.validate_runtime(protocol["runtime"])
    if any(os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise RuntimeError("unrecorded JVM options forbidden")
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    previous = output / "previous-session-01"
    extract_evidence(args.previous, previous)
    prior_meta, prior_blocks = validate_session(previous / "h2-confirmatory", protocol, 1, engine)
    engine.write_json(output / "amendment.json", amendment)
    shutil.copy2(__file__, output / "session-controller.py")
    current = output / "session-02"
    args.scratch_root.mkdir(parents=True, exist_ok=False)
    scratch = Path(tempfile.mkdtemp(prefix="h2-session-02-", dir=args.scratch_root))
    with engine.campaign(current, protocol) as meta:
        config = dict(protocol["config"])
        config["manifestDirectory"] = str((engine.ROOT / config["manifestDirectory"]).resolve())
        import longitudinal_comparison as comparison
        import monai_comparison as base
        from h2_input_paths import binding, input_paths, resolve_path
        input_binding = binding(args.input_root)
        config["sourceManifest"] = str(resolve_path(config["sourceManifest"], input_binding))
        with input_paths(base.workload, input_binding, comparison=comparison):
            paths, reference = engine.input_preflight(config)
        reference["inputBinding"] = input_binding
        for key in ("manifests", "referenceHashes", "transformIdentity"):
            if reference[key] != protocol[key]:
                raise ValueError("new session deterministic inputs differ")
        shutil.copytree(args.frozen, current / "frozen")
        engine.write_json(current / "input-preflight.json", reference)
        engine.write_artifact_manifest(paths, reference, current / "artifact-identities.json")
        engine.write_json(current / "session.json", dict(session=2, blockRange=[10, 18],
            amendmentHash=engine.digest(amendment), environmentId=meta["environmentId"],
            previousEnvironmentId=prior_meta["environmentId"]))
        identity = dict(protocolHash=meta["protocolHash"], frozenProtocolHash=engine.digest(protocol),
            sourceHash=protocol["sourceHash"], environmentId=meta["environmentId"])
        guard = lambda: engine.verify_frozen(args.frozen)
        engine.run_with_retries(current / "protocol-preflight", scratch / "protocol-preflight",
            dict(block=0, seed=config["seed"], backendOrder=list(engine.BACKENDS)), protocol, reference, paths,
            {**identity, "blockIndex": 0, "measurementRole": "excluded preflight"}, source_guard=guard)
        checkpoint(output, args.checkpoint_zip, engine)
        for assignment in assignments(protocol, 2):
            engine.run_with_retries(current / "blocks" / f"{assignment['block']:02d}",
                scratch / f"block-{assignment['block']:02d}", assignment, protocol, reference, paths,
                {**identity, "blockIndex": assignment["block"], "measurementRole": "confirmatory"}, source_guard=guard)
            count = assignment["block"] + 1
            engine.write_json(output / "progress.json", dict(validPairedBlocks=count, plannedPairedBlocks=24,
                percentComplete=100 * count / 24, inference="withheld until 24 valid paired blocks"))
            engine.remove_owned(scratch / f"block-{assignment['block']:02d}", scratch)
            checkpoint(output, args.checkpoint_zip, engine)
        _, blocks = validate_session(current, protocol, 2, engine)
        if len(prior_blocks + blocks) != 18 or len({b["blockIndex"] for b in prior_blocks + blocks}) != 18:
            raise ValueError("overlapping session evidence")
        engine.write_json(output / "launch-completion.json", dict(status="partial-campaign",
            validPairedBlocks=18, plannedPairedBlocks=24, session=2, inference="withheld until 24 valid paired blocks"))
        checkpoint(output, args.checkpoint_zip, engine)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("candidate-root", "harness-root", "frozen", "amendment", "previous", "output", "scratch-root", "input-root", "checkpoint-zip"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--session", type=int, required=True)
    args = parser.parse_args()
    # Resolve dependencies from the preserved candidate and original harness, not today's checkout.
    sys.path.insert(0, str(args.candidate_root.resolve() / "scripts"))
    sys.path.insert(0, str(args.harness_root.resolve() / "scripts"))
    import h2_confirmatory as engine
    run(args, engine)


if __name__ == "__main__":
    main()
