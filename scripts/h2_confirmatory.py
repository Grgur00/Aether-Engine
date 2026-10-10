"""Frozen H2 lifecycle orchestration. Whole blocks only; no partial lifecycle resume."""
import argparse
import contextlib
import hashlib
import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path

HARNESS_ROOT = Path(__file__).resolve().parents[1]
if __name__ == "__main__":
    bootstrap = argparse.ArgumentParser(add_help=False)
    bootstrap.add_argument("--candidate-root", type=Path)
    selected, _ = bootstrap.parse_known_args()
    if selected.candidate_root is not None:
        candidate = selected.candidate_root.resolve()
        if not (candidate / "scripts/h2_bootstrap.py").is_file():
            raise ValueError("candidate must be the frozen same-JVM H2 pilot checkout")
        sys.path.insert(0, str(candidate / "scripts"))

from evidence import digest
from h2_protocol import (ROOT, BACKENDS, audit_pilot, freeze, read,
                         validate_runtime, validate_stages, verify_frozen)
from longitudinal_comparison import preflight as input_preflight
from longitudinal_state import (capacity_required, check_capacity, cumulative, load_receipt,
                                pid_alive, remove_owned, save_receipt)
from paper_common import sha256, write_json
from persistent_service import PersistentService
from system_campaign import campaign, load_campaign


class TechnicalFailure(RuntimeError):
    pass


@contextlib.contextmanager
def technical_service(factory, scratch, reports, identity):
    try:
        with factory(scratch, reports, identity) as service:
            yield service
    except (OSError, RuntimeError, EOFError, TimeoutError, KeyError, TypeError, ValueError) as error:
        raise TechnicalFailure(f"backend lifecycle/correctness failure: {error}") from error


def seal(directory):
    directory = Path(directory)
    files = {p.relative_to(directory).as_posix(): sha256(p) for p in sorted(directory.rglob("*"))
             if p.is_file() and p.name != "completion.json"}
    write_json(directory / "completion.json", dict(status="passed", files=files, inventoryHash=digest(files)))


def verify_seal(directory):
    directory = Path(directory)
    receipt = read(directory / "completion.json")
    files = {p.relative_to(directory).as_posix(): sha256(p) for p in sorted(directory.rglob("*"))
             if p.is_file() and p.name != "completion.json"}
    if receipt.get("status") != "passed" or receipt["files"] != files or receipt["inventoryHash"] != digest(files):
        raise ValueError("completed H2 evidence missing, altered or extended")


def validate_block(block, protocol, reference, assignment, *, gpu=True):
    if (block["experiment"] != "H2-confirmatory" or block["blockIndex"] != assignment["block"]
            or block["seed"] != assignment["seed"] or block["backendOrder"] != assignment["backendOrder"]
            or block["correctnessPassed"] is not True or block["technicalFailure"] is not False
            or block["frozenProtocolHash"] != digest(protocol) or block["sourceHash"] != protocol["sourceHash"]
            or set(block["backendResults"]) != set(BACKENDS)):
        raise ValueError("H2 block identity/order/correctness mismatch")
    for backend in BACKENDS:
        result = block["backendResults"][backend]
        stages = result["stages"]
        validate_stages(stages, protocol["config"], reference, backend, assignment["block"])
        if result["cumulativeMs"] != cumulative(stages):
            raise ValueError("H2 cumulative endpoint differs from measured phases")
        if gpu and any(s.get("device") != "cuda" for s in stages):
            raise ValueError("CPU fixtures cannot enter confirmatory evidence")
        if any(s.get("traceEnabled") is not False or s.get("trainingState") != protocol["training"]["state"]
               for s in stages):
            raise ValueError("training state or tracing drift")
        if any(s.get("inputBinding") != reference.get("inputBinding") for s in stages):
            raise ValueError("worker input mount binding differs from the verified preflight")
    for version in range(5):
        for key in ("tensorSha256", "initialModelSha256", "modelSha256", "sampleOrderSha256", "modelSeed"):
            if len({block["backendResults"][b]["stages"][version][key] for b in BACKENDS}) != 1:
                raise ValueError(f"paired V{version} {key} mismatch")
    return block


def assert_no_live_leases(reports):
    for lease in Path(reports).rglob("*.lease.json"):
        if any(pid_alive(pid) for pid in read(lease)["pids"]):
            raise RuntimeError("interrupted H2 worker/service is still alive; do not start another attempt")


def subprocess_job(request, reports, name):
    request_path, result_path = reports / f"{name}.request.json", reports / f"{name}.worker.json"
    write_json(request_path, request)
    started = time.perf_counter()
    try:
        with (reports / f"{name}.log").open("x", encoding="utf-8") as log:
            process = subprocess.Popen([sys.executable, str(HARNESS_ROOT / "scripts/h2_worker.py"),
                            "--candidate-root", str(ROOT), "--request", str(request_path), "--output", str(result_path)],
                           cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
            launch_lease = reports / f"{name}.launch.lease.json"
            try:
                write_json(launch_lease, {"pids": [process.pid]})
            except BaseException:
                process.terminate()
                process.wait()
                raise
            try:
                code = process.wait()
                if code:
                    raise subprocess.CalledProcessError(code, process.args)
            finally:
                if process.poll() is not None:
                    launch_lease.unlink(missing_ok=True)
        value = read(result_path)
    except (subprocess.CalledProcessError, OSError, ValueError) as error:
        raise TechnicalFailure(f"worker crash/missing or malformed output: {name}: {error}") from error
    value["workerProcessWallMs"] = (time.perf_counter() - started) * 1000
    return value


def backend_summary(name, stages, assignment, protocol):
    values = cumulative(stages)
    return dict(experiment="H2-confirmatory", block=assignment["block"], backend=name,
        backend_position=assignment["backendOrder"].index(name) + 1,
        protocol_hash=digest(protocol), source_hash=protocol["sourceHash"], seed=assignment["seed"],
        daemon_pid_by_version={f"V{i}": s["engineInfo"]["pid"] for i, s in enumerate(stages)} if name == "aether" else None,
        versions={f"V{i}": dict(samples=protocol["config"]["versions"][i], epochs=len(s["epochs"]),
            prepare_seconds=s["timingsMs"]["scanAdmission"] / 1000,
            train_seconds=s["timingsMs"]["training"] / 1000, cumulative_seconds=values[f"V{i}"] / 1000,
            reused=s["reusedSamples"], recomputed=s["preprocessCalls"],
            reuse_rate=s["reusedSamples"] / protocol["config"]["versions"][i],
            timings_seconds={k: v / 1000 for k, v in s["timingsMs"].items()},
            tensor_sha256=s["tensorSha256"], disk=s.get("disk"), process_usage=s.get("processUsage"),
            bytes_written=s.get("bytesWritten"), bytes_written_scope="existing pilot Linux process write_bytes counters",
            gpu_utilization=s.get("gpuUtilization")) for i, s in enumerate(stages)},
        cumulative_v4_seconds=values["V4"] / 1000, correctness_passed=True, technical_failure=False,
        endpointBoundary=protocol["boundary"])


def run_attempt(assignment, reports, scratch, protocol, reference, paths, identity, *, job=subprocess_job,
                service_factory=None):
    reports, scratch = Path(reports), Path(scratch)
    reports.mkdir(parents=True, exist_ok=False)
    write_json(reports / "attempt-started.json", {**identity, "assignment": assignment, "controllerPid": os.getpid()})
    config = protocol["config"]
    stages = {b: [] for b in BACKENDS}
    if service_factory is None:
        from h2_bootstrap import h2_daemon
        service_factory = lambda *a: PersistentService(*a, factory=h2_daemon)
    with technical_service(service_factory, scratch, reports, identity) as service:
        # Unlike the exploratory runner, complete one backend lifecycle before the next.
        for backend in assignment["backendOrder"]:
            for version in range(5):
                print(f"H2 block {assignment['block'] + 1}/24 {backend} V{version}", flush=True)
                def worker(name, stage, live):
                    request = dict(config=config, backend=name, version=stage, store=str(live),
                        manifest=str(paths[stage]), modelSeed=assignment["seed"] + stage,
                        referenceHash=reference["referenceHashes"][stage], cpuFixture=False,
                        lease=str(reports / f"v{stage}-{name}.lease.json"))
                    if name == "aether":
                        request["service"] = service.daemon
                    if "inputBinding" in reference:
                        request["inputBinding"] = reference["inputBinding"]
                    return job(request, reports, f"v{stage}-{name}")
                try:
                    stages[backend].append(service.run_stage(backend, version, worker))
                finally:
                    live_parent = scratch / backend
                    for diagnostic in live_parent.glob("live.*"):
                        if diagnostic.is_file():
                            shutil.copy2(diagnostic, reports / f"v{version}-{backend}-{diagnostic.name}")
            try:
                validate_stages(stages[backend], config, reference, backend, assignment["block"])
            except (KeyError, TypeError, ValueError) as error:
                raise TechnicalFailure(f"mandatory {backend} correctness failed: {error}") from error
    block = dict(experiment="H2-confirmatory", blockIndex=assignment["block"], seed=assignment["seed"],
        backendOrder=assignment["backendOrder"], correctnessPassed=True, technicalFailure=False,
        frozenProtocolHash=digest(protocol), sourceHash=protocol["sourceHash"],
        backendResults={b: dict(stages=s, cumulativeMs=cumulative(s)) for b, s in stages.items()})
    try:
        validate_block(block, protocol, reference, assignment)
    except (KeyError, TypeError, ValueError) as error:
        raise TechnicalFailure(f"mandatory paired correctness failed: {error}") from error
    for name, values in stages.items():
        write_json(reports / f"{name}-summary.json", backend_summary(name, values, assignment, protocol))
    save_receipt(reports / "paired.json", block, identity)
    seal(reports)
    return block


def load_completed(reports, identity, protocol, reference, assignment):
    verify_seal(reports)
    block = load_receipt(Path(reports) / "paired.json", identity)
    validate_block(block, protocol, reference, assignment)
    for backend in BACKENDS:
        for version, stage in enumerate(block["backendResults"][backend]["stages"]):
            if load_receipt(Path(reports) / f"v{version}-{backend}.json", {**identity, "backend": backend, "version": version}) != stage:
                raise ValueError("archived paired and per-stage evidence differ")
    return block


def run_with_retries(parent, scratch, assignment, protocol, reference, paths, identity, *, execute=run_attempt,
                     source_guard=None):
    parent, scratch = Path(parent), Path(scratch)
    parent.mkdir(parents=True, exist_ok=True)
    for attempt in range(1, protocol["maxAttemptsPerBlock"] + 1):
        reports = parent / f"attempt-{attempt:02d}"
        attempt_identity = {**identity, "attempt": attempt}
        if reports.exists():
            if (reports / "completion.json").exists():
                return load_completed(reports, attempt_identity, protocol, reference, assignment)
            assert_no_live_leases(reports)
            started = read(reports / "attempt-started.json")
            if any(started.get(k) != v for k, v in attempt_identity.items()) or started["assignment"] != assignment:
                raise ValueError("interrupted attempt ownership/assignment differs")
            if not (reports / "failure.json").exists():
                write_json(reports / "failure.json", dict(reason="technical interruption before completion",
                    policy="whole four-backend block invalidated; all partial timings excluded", **attempt_identity))
            continue
        try:
            if source_guard is not None:
                source_guard()
            scratch.parent.mkdir(parents=True, exist_ok=True)
            check_capacity(scratch.parent, capacity_required(protocol["config"]["versions"][-1],
                                                             protocol["config"]["imageSize"]))
            return execute(assignment, reports, scratch / f"attempt-{attempt:02d}", protocol, reference, paths, attempt_identity)
        except (TechnicalFailure, subprocess.CalledProcessError, EOFError, TimeoutError) as error:
            assert_no_live_leases(reports)
            write_json(reports / "failure.json", dict(reason=str(error), errorType=type(error).__name__,
                policy="whole four-backend block invalidated; order and seed unchanged", **attempt_identity))
    raise RuntimeError("predeclared technical-attempt limit reached; preserve evidence, do not change the protocol")


def launch_limit(value):
    if type(value) is not int or not 1 <= value <= 24:
        raise ValueError("launch limit must be an integer from 1 to 24; planned sample stays 24")
    return value


def checkpoint(output, destination):
    """Publish a complete ZIP atomically, outside all measured backend phases."""
    output, destination = Path(output).resolve(), Path(destination).resolve()
    if destination.is_relative_to(output):
        raise ValueError("checkpoint ZIP must be outside the campaign directory")
    destination.parent.mkdir(parents=True, exist_ok=True)
    inventory = {}
    descriptor, temporary = tempfile.mkstemp(prefix=".h2-checkpoint-", suffix=".zip", dir=destination.parent)
    os.close(descriptor)
    try:
        with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for path in sorted(output.rglob("*")):
                if path.is_symlink():
                    raise ValueError("checkpoint must not follow symbolic links")
                if path.is_file() and path.name != ".campaign.lock":
                    name = "h2-confirmatory/" + path.relative_to(output).as_posix()
                    inventory[name] = sha256(path)
                    archive.write(path, name)
            archive.writestr("checkpoint.json", json.dumps(dict(schema="aether-h2-checkpoint-v1",
                files=inventory, inventoryHash=digest(inventory), inference="withheld until 24 valid paired blocks",
                continuation="same-host resume only; cross-host pooling is not authorized"), sort_keys=True))
        with zipfile.ZipFile(temporary) as archive:
            if archive.testzip() is not None:
                raise ValueError("checkpoint archive CRC failure")
            for name, expected in inventory.items():
                if hashlib.sha256(archive.read(name)).hexdigest() != expected:
                    raise ValueError("checkpoint archive digest failure")
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)
    print(f"H2 verified checkpoint: {destination}", flush=True)


def run(frozen, output, scratch_root, *, resume=False, preflight_only=False,
        stop_after_blocks=24, checkpoint_zip=None, input_root=None):
    launch_limit(stop_after_blocks)
    if preflight_only and stop_after_blocks != 24:
        raise ValueError("preflight-only cannot have a confirmatory launch limit")
    protocol = verify_frozen(frozen)
    validate_runtime(protocol["runtime"])
    if any(os.environ.get(k) for k in ("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS")):
        raise RuntimeError("unrecorded JVM environment options forbidden; JFR/tracing must stay disabled")
    config = dict(protocol["config"])
    config["manifestDirectory"] = str((ROOT / config["manifestDirectory"]).resolve())
    scratch_root = Path(scratch_root).resolve()
    scratch_root.mkdir(parents=True, exist_ok=True)
    output = Path(output).resolve()
    with campaign(output, protocol, resume=resume) as meta:
        launches = read(output / "launches.json") if (output / "launches.json").exists() else []
        launches.append(dict(stopAfterBlocks=stop_after_blocks, preflightOnly=preflight_only,
                            plannedPairedBlocks=24, controllerPid=os.getpid()))
        write_json(output / "launches.json", launches)
        input_binding = None
        if input_root is not None:
            import longitudinal_comparison as comparison
            import monai_comparison as base
            from h2_input_paths import LOGICAL_ROOT, binding, input_paths, resolve_path
            input_binding = binding(input_root)
            if input_binding["logicalRoot"] != LOGICAL_ROOT:
                raise ValueError("frozen logical input root changed")
            config["sourceManifest"] = str(resolve_path(config["sourceManifest"], input_binding))
            with input_paths(base.workload, input_binding, comparison=comparison):
                paths, reference = input_preflight(config)
            reference["inputBinding"] = input_binding
        else:
            paths, reference = input_preflight(config)
        if reference["manifests"] != protocol["manifests"]:
            raise ValueError("input manifest identity changed since freeze")
        for key in ("referenceHashes", "transformIdentity"):
            if reference[key] != protocol[key]:
                raise ValueError("deterministic prepared artifacts differ from the pilot")
        identity = dict(protocolHash=meta["protocolHash"], frozenProtocolHash=digest(protocol),
                        sourceHash=protocol["sourceHash"], environmentId=meta["environmentId"])
        workspace_path = output / "workspace.json"
        if workspace_path.exists():
            workspace = read(workspace_path)
            scratch = Path(workspace["path"]).resolve()
            if scratch.parent != scratch_root or workspace["protocolHash"] != meta["protocolHash"]:
                raise ValueError("scratch ownership/protocol drift")
        else:
            check_capacity(scratch_root, capacity_required(config["versions"][-1], config["imageSize"]))
            scratch = Path(tempfile.mkdtemp(prefix="h2-", dir=scratch_root)).resolve()
            workspace = dict(path=str(scratch), protocolHash=meta["protocolHash"])
            write_json(workspace_path, workspace)
            write_json(scratch / "owner.json", workspace)
        if read(scratch / "owner.json") != workspace:
            raise ValueError("scratch owner receipt changed")
        if not (output / "input-preflight.json").exists():
            shutil.copytree(frozen, output / "frozen")
            write_json(output / "input-preflight.json", reference)
            write_artifact_manifest(paths, reference, output / "artifact-identities.json")
        else:
            if verify_frozen(output / "frozen") != protocol:
                raise ValueError("archived frozen protocol changed")
            saved = read(output / "input-preflight.json")
            if saved.get("inputBinding") != reference.get("inputBinding"):
                raise ValueError("input mount binding changed during same-host resume")
            for key in ("referenceHashes", "transformIdentity", "manifests"):
                if saved[key] != reference[key]:
                    raise ValueError("input preflight identity changed")
        # Full 100-epoch lifecycle/backend, discarded from inference regardless of its timings.
        assignment = dict(block=0, seed=config["seed"], backendOrder=list(BACKENDS))
        run_with_retries(output / "protocol-preflight", scratch / "protocol-preflight", assignment,
                         protocol, reference, paths, {**identity, "blockIndex": 0, "measurementRole": "excluded preflight"},
                         source_guard=lambda: verify_frozen(frozen))
        if checkpoint_zip is not None:
            checkpoint(output, checkpoint_zip)
        if preflight_only:
            return
        blocks = []
        for assignment in protocol["schedule"][:stop_after_blocks]:
            verify_frozen(frozen)
            check_capacity(scratch_root, capacity_required(config["versions"][-1], config["imageSize"]))
            block = run_with_retries(output / "blocks" / f"{assignment['block']:02d}",
                scratch / f"block-{assignment['block']:02d}", assignment, protocol, reference, paths,
                {**identity, "blockIndex": assignment["block"], "measurementRole": "confirmatory"},
                source_guard=lambda: verify_frozen(frozen))
            blocks.append(block)
            # Only completion counts, never intermediate significance/optional stopping.
            write_json(output / "progress.json", dict(validPairedBlocks=len(blocks), plannedPairedBlocks=24,
                percentComplete=100 * len(blocks) / 24, inference="withheld until 24 valid paired blocks"))
            remove_owned(scratch / f"block-{assignment['block']:02d}", scratch)
            if checkpoint_zip is not None:
                checkpoint(output, checkpoint_zip)
        if stop_after_blocks < 24:
            write_json(output / "launch-completion.json", dict(status="partial-campaign",
                validPairedBlocks=len(blocks), plannedPairedBlocks=24,
                inference="withheld until 24 valid paired blocks", launchLimit=stop_after_blocks))
            if checkpoint_zip is not None:
                checkpoint(output, checkpoint_zip)
            print(f"H2 launch finished: {len(blocks)}/24 blocks; no confirmatory inference.", flush=True)
        else:
            analyze_output(output)
            if checkpoint_zip is not None:
                checkpoint(output, checkpoint_zip)


def write_artifact_manifest(paths, reference, output):
    from aether_ml.identity import artifact_key
    from longitudinal_manifests import read_rows
    versions = []
    previous = 0
    for version, path in enumerate(paths):
        entries = []
        for row in read_rows(path)[1]:
            key = artifact_key("monai-pilot-v1", row["source_identity"], reference["transformIdentity"], "1")
            entries.append(dict(sampleId=row["sample_id"], sourceIdentity=row["source_identity"],
                imageSha256=row["image_sha256"], maskSha256=row["mask_sha256"],
                artifactIdentity=dict(namespace=key.namespace, sampleId=key.sample_id,
                                      transformation=key.transform.digest.hex())))
        versions.append(dict(version=f"V{version}", manifestSha256=sha256(path), entries=entries,
                             reused=previous, added=len(entries) - previous, identityDigest=digest(entries)))
        previous = len(entries)
    write_json(output, dict(schema="aether-h2-artifact-identities-v1", versions=versions))


def analyze_output(output):
    from h2_analysis import analyze, plot
    output = Path(output)
    meta = load_campaign(output)
    protocol = verify_frozen(output / "frozen", check_source=False)
    if protocol != meta["protocol"]:
        raise ValueError("archived protocol differs from campaign")
    reference = read(output / "input-preflight.json")
    for key in ("manifests", "referenceHashes", "transformIdentity"):
        if reference[key] != protocol[key]:
            raise ValueError("archived input identity differs from the frozen protocol")
    preflight_assignment = dict(block=0, seed=protocol["config"]["seed"], backendOrder=list(BACKENDS))
    archived_attempt(output / "protocol-preflight", meta, protocol, reference, preflight_assignment,
                     measurement_role="excluded preflight")
    expected_directories = {f"{a['block']:02d}" for a in protocol["schedule"]}
    if {p.name for p in (output / "blocks").iterdir() if p.is_dir()} != expected_directories:
        raise ValueError("unscheduled or missing block directories")
    blocks = []
    for assignment in protocol["schedule"]:
        parent = output / "blocks" / f"{assignment['block']:02d}"
        blocks.append(archived_attempt(parent, meta, protocol, reference, assignment,
                                       measurement_role="confirmatory"))
    summary = analyze(blocks)
    write_json(output / "analysis.json", summary)
    plot(blocks, output / "figures")
    write_json(output / "results-and-limitations.json", dict(conclusion=summary["conclusion"],
        primary=summary["comparisons"]["mmap"], limitations=[protocol["pageCache"], protocol["resourceLimitations"],
        "one OCT5K workload, approximately 95% additive reuse; no general reuse-rate claim",
        "PersistentDataset has no matching fsync durability guarantee", "secondary MONAI comparisons are descriptive"],
        failedAttempts=[str(p.relative_to(output)) for p in output.glob("blocks/*/attempt-*/failure.json")]))
    files = {p.relative_to(output).as_posix(): sha256(p) for p in sorted(output.rglob("*"))
             if p.is_file() and p.name not in {".campaign.lock", "campaign-completion.json"}}
    write_json(output / "campaign-completion.json", dict(status="complete", validPairedBlocks=24,
        protocolHash=digest(protocol), files=files, inventoryHash=digest(files)))


def archived_attempt(parent, meta, protocol, reference, assignment, *, measurement_role):
    attempts = sorted(Path(parent).glob("attempt-*"))
    successful = [p for p in attempts if (p / "completion.json").exists()]
    if len(successful) != 1:
        raise ValueError("requires exactly one valid whole-block attempt per scheduled block/preflight")
    for number, path in enumerate(attempts, 1):
        if path.name != f"attempt-{number:02d}" or number > protocol["maxAttemptsPerBlock"]:
            raise ValueError("attempt history differs from the predeclared failure policy")
        if path != successful[0] and not (path / "failure.json").is_file():
            raise ValueError("failed attempt reason missing")
        if path > successful[0]:
            raise ValueError("valid completed block was retried")
    attempt = int(successful[0].name.split("-")[-1])
    return load_completed(successful[0], dict(protocolHash=meta["protocolHash"],
        frozenProtocolHash=digest(protocol), sourceHash=protocol["sourceHash"], environmentId=meta["environmentId"],
        blockIndex=assignment["block"], attempt=attempt, measurementRole=measurement_role),
        protocol, reference, assignment)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--candidate-root", type=Path, required=True,
                        help="exact preserved H2 pilot checkout, not the main worktree")
    commands = parser.add_subparsers(dest="command", required=True)
    audit = commands.add_parser("audit-pilot")
    audit.add_argument("--pilot-output", type=Path, required=True)
    frozen = commands.add_parser("freeze")
    frozen.add_argument("--config", type=Path, default=HARNESS_ROOT / "configs/paper/oct5k-h2-confirmatory.json")
    frozen.add_argument("--pilot-output", type=Path, required=True)
    frozen.add_argument("--output", type=Path, required=True)
    runner = commands.add_parser("run")
    runner.add_argument("--frozen", type=Path, required=True)
    runner.add_argument("--output", type=Path, required=True)
    runner.add_argument("--scratch-root", type=Path, required=True)
    runner.add_argument("--resume", action="store_true")
    runner.add_argument("--preflight-only", action="store_true")
    runner.add_argument("--stop-after-blocks", type=int, default=24,
                        help="bounded launch, not a change to the 24-block analysis requirement")
    runner.add_argument("--checkpoint-zip", type=Path,
                        help="atomic verified evidence ZIP refreshed after preflight and each complete block")
    runner.add_argument("--input-root", type=Path,
                        help="actual read-only OCT5K mount; original frozen CSV bytes and source hashes remain checked")
    analysis = commands.add_parser("analyze")
    analysis.add_argument("--output", type=Path, required=True)
    args = parser.parse_args(argv)
    if args.command == "audit-pilot":
        print(json.dumps(audit_pilot(args.pilot_output), indent=2))
    elif args.command == "freeze":
        result = freeze(args.config, args.pilot_output, args.output)
        print(f"Frozen H2 protocol: {digest(result)}")
    elif args.command == "run":
        run(args.frozen, args.output, args.scratch_root, resume=args.resume, preflight_only=args.preflight_only,
            stop_after_blocks=args.stop_after_blocks, checkpoint_zip=args.checkpoint_zip, input_root=args.input_root)
    else:
        analyze_output(args.output)


if __name__ == "__main__":
    main()
