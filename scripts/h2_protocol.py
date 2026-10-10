"""H2 protocol identities and fail-closed pilot/source audit. No engine tuning."""
import hashlib
import importlib.metadata
import itertools
import json
import math
import random
import platform
import subprocess
import zipfile
from pathlib import Path

from evidence import digest
from paper_common import ROOT, sha256, write_json
from longitudinal_manifests import COUNTS, verify
from longitudinal_state import BACKENDS, cumulative, load_receipt
from package_artifact import DIRECTORIES, EXCLUDED, ROOT_FILES
from persistent_service import validate_service_stages
from system_campaign import load_campaign
from h2_input_paths import POLICY as INPUT_BINDING_POLICY

HARNESS_ROOT = Path(__file__).resolve().parents[1]
HARNESS_FILES = ("scripts/h2_confirmatory.py", "scripts/h2_protocol.py", "scripts/h2_worker.py",
                 "scripts/h2_analysis.py", "scripts/h2_input_paths.py", "configs/paper/oct5k-h2-confirmatory.json",
                 "kaggle/H2-CONFIRMATORY.md")
STORAGE_COMMIT = "00760e5f31fa31a17e69522539a3b60318ca9bf0"
RUNTIME_PACKAGES = ("torch", "numpy", "Pillow", "monai", "lmdb", "scipy", "matplotlib")

PHASES = ("startup", "modelSetup", "scanAdmission", "training", "drain", "close")
BOUNDARY = "sum(startup, modelSetup, scanAdmission, training, drain, close) across V0-V4"
STORAGE = dict(placement="inline", targetSstableBytes=32 * 1024 ** 2, admissionBatch=16,
               initialPopulation="bulk-streaming-v1", verifier="streaming-v1",
               verificationPolicy="bulk-deferred-inventory-v2", durability="DURABLE",
               integrityPolicy="immutable-inline-admission-v1", backgroundCompaction=True,
               jfr=False, serverTrace=False)
TRAINING = dict(model="small", optimizer="AdamW", learningRate=.001, loss="BCEWithLogitsLoss",
                warmupSteps=12, epochsPerVersion=20, batchSize=16, prefetchDepth=0,
                state="fresh-model-and-optimizer-per-version", ordering="ordered manifest; no shuffle",
                preprocessingPasses=4, augmentation="existing augment_batch, matched per-version seed")
PRIMARY = dict(endpoint="cumulative V4", comparison="mmap/Aether", method="paired log-ratio t-test",
               alternative="greater", alpha=.05, confidence=.95, pairedBlocks=24)
FROZEN_FILES = {"protocol.json", "order_schedule.json", "source-manifest.json", "pilot-audit.json",
                "source.zip", "harness.zip", "pilot-evidence.zip"}


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def schedule(seed):
    if type(seed) is not int:
        raise ValueError("order seed must be an integer")
    orders = [list(order) for order in itertools.permutations(BACKENDS)]
    random.Random(seed).shuffle(orders)
    return [dict(block=i, seed=20260926 + i, backendOrder=order) for i, order in enumerate(orders)]


def validate_config(config):
    expected = dict(schema="aether-h2-config-v1", serviceLifecycle="persistent-per-block",
        versions=COUNTS, sourceSamples=1505, seed=20260926, pairedBlocks=24,
        epochs=20, trainV0=True, initialPopulation="bulk-streaming-v1",
        storageCommit=STORAGE_COMMIT,
        targetSstableBytes=33554432, admissionBatch=16, batchSize=16, imageSize=256,
        prefetchDepth=0, serverTrace=False, jfr=False, confirmatory=True)
    for key, value in expected.items():
        if type(config.get(key)) is not type(value) or config[key] != value:
            raise ValueError(f"H2 frozen configuration drift: {key}")
    schedule(config["orderSeed"])


def validate_bulk(commit, count):
    storage = commit["storage"]
    verification = storage["verification"]
    streaming = storage["streamingVerification"]
    if (commit["status"] != "committed" or commit["artifacts"] != count
            or commit["sha256Calls"] != count or storage["entries"] != count
            or storage["targetSstableBytes"] != STORAGE["targetSstableBytes"]
            or storage["walPayloadBytes"] != 0 or storage["memtableInsertions"] != 0
            or storage["verificationPolicy"] != STORAGE["verificationPolicy"]
            or verification != dict(inventoryCalls=1, tablesFullyVerified=storage["tables"],
                                    bytesFullyVerified=sum(t["bytes"] for t in storage["sstableFinishes"]))
            or streaming["implementation"] != "streaming-v1" or streaming["entries"] != count
            or streaming["tables"] != storage["tables"]):
        raise ValueError("bulk publication/single streaming verification mismatch")


def validate_stages(stages, config, reference, backend, block):
    if len(stages) != 5:
        raise ValueError("each backend must complete all five versions")
    for version, stage in enumerate(stages):
        count = config["versions"][version]
        previous = config["versions"][version - 1] if version else 0
        batches = math.ceil(count / 16)
        if (stage["preprocessCalls"] != count - previous or stage["reusedSamples"] != previous
                or stage["newSamples"] != count - previous or stage["uniqueArtifacts"] != count
                or stage["trainingCacheMisses"] != 0 or stage["trainingPreprocessCalls"] != 0
                or stage["trainingSampleRequests"] != count * 20 or len(stage["epochs"]) != 20
                or stage["modelSeed"] != config["seed"] + block + version
                or stage["tensorSha256"] != reference["referenceHashes"][version]
                or stage["transformIdentity"] != reference["transformIdentity"]
                or stage["sampleOrderSha256"] != reference["manifests"]["manifestSha256"][version]
                or set(stage["timingsMs"]) != set(PHASES)):
            raise ValueError(f"{backend} V{version} workload/hash/timing mismatch")
        for key in ("initialModelSha256", "modelSha256"):
            value = stage[key]
            if not isinstance(value, str) or len(value) != 64 or any(c not in "0123456789abcdef" for c in value):
                raise ValueError("training model identity missing")
        for number, epoch in enumerate(stage["epochs"], 1):
            if (epoch["epoch"] != number or epoch["sampleRequests"] != count
                    or len(epoch["losses"]) != batches or not all(math.isfinite(v) for v in epoch["losses"])
                    or not math.isfinite(epoch["wallMs"]) or epoch["wallMs"] <= 0):
                raise ValueError("incomplete epoch or nonfinite training")
        if stage["timingsMs"]["training"] <= 0 or stage["timingsMs"]["scanAdmission"] <= 0:
            raise ValueError("mandatory preparation/training not measured")
        if backend == "aether":
            info = stage["engineInfo"]
            if (info["durability"] != STORAGE["durability"]
                    or info["integrityPolicy"]["version"] != STORAGE["integrityPolicy"]
                    or not info["backgroundCompaction"]["enabled"] or info["cacheEntries"] != count):
                raise ValueError("Aether storage configuration drift")
            if not stage["backgroundDrains"] or any(
                    drain is None or not drain["drained"] or drain["backgroundCompaction"]["state"] != "IDLE"
                    or drain["backgroundCompaction"]["debtBytes"] != 0
                    or drain["backgroundCompaction"].get("failed", 0)
                    for drain in stage["backgroundDrains"]):
                raise ValueError("unreported or failed background storage work")
    cumulative(stages)
    if backend == "aether":
        validate_service_stages(stages)
        validate_bulk(stages[0]["bulkCommit"], config["versions"][0])
        if stages[0]["bulkCommit"]["servicePid"] != stages[0]["engineInfo"]["pid"]:
            raise ValueError("bulk publication and training must use the same JVM")


def audit_pilot(directory):
    """Accept archived raw receipts, never user-supplied mean timings alone."""
    directory = Path(directory)
    meta = load_campaign(directory)
    config = meta["protocol"]["config"]
    reference = read(directory / "preflight.json")
    if (config.get("pairedBlocks") != 5 or config.get("versions") != COUNTS
            or config.get("epochs") != 20 or config.get("batchSize") != 16
            or config.get("prefetchDepth") != 0 or config.get("serverTrace") is not False
            or config.get("serviceLifecycle") != "persistent-per-block"
            or config.get("trainV0") is not True
            or config.get("initialPopulation") != "bulk-streaming-v1"
            or config.get("targetSstableBytes") != STORAGE["targetSstableBytes"]
            or config.get("storageCommit") != STORAGE_COMMIT
            or meta["protocol"].get("confirmatory") is not False
            or meta["protocol"].get("cpuFixture") is not False):
        raise ValueError("requires the successful five-block persistent GPU pilot, not an earlier pilot")
    files = {}
    for index in range(5):
        path = directory / "blocks" / f"{index:02d}" / "paired.json"
        block = load_receipt(path, {"protocolHash": meta["protocolHash"], "blockIndex": index})
        for backend in BACKENDS:
            stages = block["backendResults"][backend]["stages"]
            validate_stages(stages, config, reference, backend, index)
            if block["backendResults"][backend]["cumulativeMs"] != cumulative(stages):
                raise ValueError("pilot endpoint differs from phase sums")
            for version, stage in enumerate(stages):
                stage_path = path.parent / f"v{version}-{backend}.json"
                if load_receipt(stage_path, {"protocolHash": meta["protocolHash"]}) != stage:
                    raise ValueError("pilot stage and paired receipts differ")
                files[stage_path.relative_to(directory).as_posix()] = sha256(stage_path)
        for version in range(5):
            for key in ("tensorSha256", "initialModelSha256", "modelSha256", "sampleOrderSha256", "modelSeed"):
                if len({block["backendResults"][b]["stages"][version][key] for b in BACKENDS}) != 1:
                    raise ValueError("pilot paired training/artifact identities differ")
        files[path.relative_to(directory).as_posix()] = sha256(path)
    for name in ("campaign.json", "environment.json", "preflight.json"):
        files[name] = sha256(directory / name)
    return dict(schema="aether-h2-pilot-audit-v1", passed=True, boundary=BOUNDARY,
                pilotProtocolHash=meta["protocolHash"], pilotSourceHash=digest(meta["sourceSha256"]),
                evidence=files, evidenceHash=digest(files), training=TRAINING, storage=STORAGE,
                caveat="old V0-without-training pilot is not accepted; pilot observations excluded from H2")


def pilot_runtime(report):
    packages = {}
    for line in report["commands"]["packages"]["stdout"].splitlines():
        name, separator, version = line.partition("==")
        if separator:
            packages[name.lower()] = version
    java = report["commands"]["java"]
    if java["returncode"] != 0:
        raise ValueError("pilot Java runtime was not recorded successfully")
    return dict(python=report["python"].split()[0], java=java,
                packages={name: packages[name.lower()] for name in RUNTIME_PACKAGES})


def validate_runtime(expected):
    java = subprocess.run(["java", "-version"], capture_output=True, text=True)
    current = dict(python=platform.python_version(),
        java=dict(returncode=java.returncode, stdout=java.stdout.strip(), stderr=java.stderr.strip()),
        packages={name: importlib.metadata.version(name) for name in RUNTIME_PACKAGES})
    if current != expected:
        raise RuntimeError("pilot Java/Python/training or analysis package versions changed; freeze a new protocol")


def source_files(root=None):
    root = Path(ROOT if root is None else root)
    paths = {root / name for name in ROOT_FILES if (root / name).is_file()}
    for directory in DIRECTORIES:
        for path in (root / directory).rglob("*"):
            relative = path.relative_to(root)
            if (not path.is_file() or path.is_symlink() or any(p in EXCLUDED for p in relative.parts)
                    or (relative.as_posix().startswith("clients/python/") and path.suffix not in {".py", ".toml", ".md"})):
                continue
            paths.add(path)
    return {p.relative_to(root).as_posix(): sha256(p) for p in sorted(paths)}


def source_identity(root=None):
    root = Path(ROOT if root is None else root)
    files = source_files(root)
    git = ["git", "-c", f"safe.directory={root.as_posix()}"]
    commit = subprocess.run(git + ["rev-parse", "HEAD"], cwd=root, capture_output=True, text=True)
    status = subprocess.run(git + ["status", "--porcelain"], cwd=root, capture_output=True, text=True)
    clean = status.returncode == 0 and not status.stdout.strip()
    origin = commit.stdout.strip() if commit.returncode == 0 else None
    if not clean:
        archive_path = root / "artifact-provenance.json"
        if not archive_path.is_file():
            raise ValueError("freeze requires committed clean source or a verified clean source archive")
        archive = read(archive_path)
        if archive.get("sourceClean") is not True or archive.get("files") != files:
            raise ValueError("source archive inventory changed or was not clean")
        recorded = archive.get("gitCommit", {})
        origin = recorded.get("stdout") if isinstance(recorded, dict) else recorded
    if not origin or len(origin) != 40 or any(c not in "0123456789abcdef" for c in origin):
        raise ValueError("originating exact commit is required")
    return dict(schema="aether-h2-source-v1", commit=origin, sourceHash=digest(files), files=files)


def harness_files():
    return {name: sha256(HARNESS_ROOT / name) for name in HARNESS_FILES}


def verify_frozen(directory, root=None, *, check_source=True):
    directory = Path(directory)
    receipt = read(directory / "freeze.json")
    if (set(receipt["files"]) != FROZEN_FILES
            or {p.name for p in directory.iterdir()} != FROZEN_FILES | {"freeze.json"}
            or receipt["files"] != {name: sha256(directory / name) for name in receipt["files"]}):
        raise ValueError("frozen protocol bundle changed")
    protocol = read(directory / "protocol.json")
    source = read(directory / "source-manifest.json")
    validate_config(protocol["config"])
    if (protocol["sourceHash"] != digest(source["files"]) or source["sourceHash"] != protocol["sourceHash"]
            or (check_source and source_files(root) != source["files"]) or receipt["protocolHash"] != digest(protocol)
            or protocol["schedule"] != schedule(protocol["config"]["orderSeed"])
            or read(directory / "order_schedule.json") != protocol["schedule"]
            or protocol["commit"] != source["commit"] or protocol["storageCommit"] != STORAGE_COMMIT
            or protocol["schema"] != "aether-h2-confirmatory-v1" or protocol["primary"] != PRIMARY
            or protocol["maxAttemptsPerBlock"] != 3 or read(directory / "pilot-audit.json") != protocol["pilotAudit"]
            or protocol["training"] != TRAINING or protocol["storage"] != STORAGE
            or protocol["boundary"] != BOUNDARY or protocol["pilotAudit"]["passed"] is not True):
        raise ValueError("source, protocol, schedule, training or storage identity drift")
    if protocol.get("inputBindingPolicy") != INPUT_BINDING_POLICY:
        raise ValueError("input path binding policy changed")
    if (digest(protocol["harnessFiles"]) != protocol["harnessHash"]
            or (check_source and protocol["harnessFiles"] != harness_files())):
        raise ValueError("confirmatory orchestration source changed")
    return protocol


def freeze(config_path, pilot, output):
    config = read(config_path)
    validate_config(config)
    audit = audit_pilot(pilot)
    source = source_identity()
    pilot_meta = load_campaign(pilot)
    pilot_environment = read(Path(pilot) / "environment.json")
    provenance = pilot_environment["archiveProvenance"]
    if (provenance.get("verified") is not True or provenance.get("sourceClean") is not True
            or provenance["originatingCommit"]["stdout"] != source["commit"]):
        raise ValueError("candidate commit differs from the verified pilot source archive")
    for name, expected in source["files"].items():
        if (name.startswith("modules/") and "/src/main/" in name and name.endswith(".java")
                or name.startswith("clients/python/") and name.endswith(".py")
                or name in {"scripts/longitudinal_worker.py", "scripts/monai_comparison.py",
                            "scripts/persistent_service.py", "scripts/h2_bootstrap.py", "scripts/paper_common.py"}):
            if pilot_meta["sourceSha256"].get(name.replace("/", "\\"), pilot_meta["sourceSha256"].get(name)) != expected:
                raise ValueError(f"candidate treatment differs from pilot source: {name}")
    for name, expected in pilot_meta["sourceSha256"].items():
        if source["files"].get(name.replace("\\", "/")) != expected:
            raise ValueError(f"candidate file differs from the pilot evidence: {name}")
    manifests, _ = verify(ROOT / config["manifestDirectory"])
    if (manifests["counts"] != COUNTS or manifests["seed"] != config["seed"]
            or manifests != read(Path(pilot) / "preflight.json")["manifests"]):
        raise ValueError("dataset membership drift")
    protocol = dict(schema="aether-h2-confirmatory-v1", experiment="H2-confirmatory", config=config,
        sourceHash=source["sourceHash"], commit=source["commit"], pilotAudit=audit, manifests=manifests,
        harnessFiles=harness_files(), harnessHash=digest(harness_files()),
        schedule=schedule(config["orderSeed"]), training=TRAINING, storage=STORAGE, boundary=BOUNDARY,
        inputBindingPolicy=INPUT_BINDING_POLICY,
        storageCommit=STORAGE_COMMIT, runtime=pilot_runtime(pilot_environment),
        referenceHashes=pilot_meta["protocol"]["referenceHashes"],
        transformIdentity=pilot_meta["protocol"]["transformIdentity"],
        runtimePolicy="exact pilot Java/Python and relevant package versions; same host for resume",
        exclusions=["input/hash preflight", "Python process imports/launch", "artifact/model validation",
                    "result archival", "instrumentation"],
        primary=PRIMARY,
        secondary="descriptive only; no formal MONAI superiority claim", maxAttemptsPerBlock=3,
        technicalFailures=["process crash", "corrupt/incomplete artifacts or output", "failed mandatory correctness",
                           "interrupted lifecycle", "lost worker output", "incomplete epoch"],
        failurePolicy="archive entire attempt; rerun all four backends in same order with same seed",
        stopPolicy="no early significance analysis or sample-size changes",
        pageCache="no OS cache clearing; exact source preflight shared; fresh isolated stores per backend/block",
        resourceLimitations="OS cache not perfectly controlled; sampled GPU/disk counters and Linux process counters")
    output = Path(output).resolve()
    if any(output.is_relative_to(ROOT / name) for name in DIRECTORIES):
        raise ValueError("freeze output must be outside inventoried source directories (use build/h2-freeze)")
    output.mkdir(parents=True, exist_ok=False)
    write_json(output / "protocol.json", protocol)
    write_json(output / "order_schedule.json", protocol["schedule"])
    write_json(output / "source-manifest.json", source)
    write_json(output / "pilot-audit.json", audit)
    with zipfile.ZipFile(output / "pilot-evidence.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, expected in audit["evidence"].items():
            if sha256(Path(pilot) / name) != expected:
                raise ValueError("pilot evidence changed during freeze")
            archive.write(Path(pilot) / name, name)
    with zipfile.ZipFile(output / "source.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, expected in source["files"].items():
            data = (ROOT / name).read_bytes()
            if hashlib.sha256(data).hexdigest() != expected:
                raise ValueError("source changed during freeze")
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            archive.writestr(entry, data)
        provenance = dict(schema="aether-source-archive-v1", sourceClean=True,
                          gitCommit={"stdout": source["commit"], "returncode": 0}, files=source["files"])
        archive.writestr(zipfile.ZipInfo("artifact-provenance.json"), json.dumps(provenance, sort_keys=True))
    with zipfile.ZipFile(output / "harness.zip", "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name in HARNESS_FILES:
            archive.write(HARNESS_ROOT / name, name)
    write_json(output / "freeze.json", dict(protocolHash=digest(protocol), files={
        path.name: sha256(path) for path in sorted(output.iterdir()) if path.is_file()}))
    verify_frozen(output)
    return protocol
