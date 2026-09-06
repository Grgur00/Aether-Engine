"""Validate the saved protocol and raw evidence before resuming or analyzing a block."""
import hashlib
import json
import math
from pathlib import Path


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def file_digest(path):
    with Path(path).open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read(path):
    return json.loads(Path(path).read_text(encoding="utf-8"))


def validate_block(path):
    path = Path(path)
    block = read(path)
    root = path.parent.parent.parent
    protocol = read(root / "protocol.json")
    if block.get("schema") != "aether-paper-block-v1" or block.get("status") != "PASSED" or block.get("correctnessPassed") is not True:
        raise ValueError(f"failed or unsupported block: {path}")
    if digest(protocol) != block.get("protocolHash"):
        raise ValueError(f"altered frozen protocol: {path}")
    condition = block["condition"]
    if condition not in protocol["conditions"] or condition["conditionId"] != block["conditionId"]:
        raise ValueError(f"condition not in frozen protocol: {path}")
    index = block["blockIndex"]
    if type(index) is not int or not 0 <= index < protocol["repeats"] or block["seed"] != protocol["seedBase"] + index:
        raise ValueError(f"invalid block index or seed: {path}")
    env_id = block["environmentId"]
    if not isinstance(env_id, str) or len(env_id) != 64 or any(c not in "0123456789abcdef" for c in env_id):
        raise ValueError("invalid environment identifier")
    env_path = root / f"environment-{env_id}.json"
    env = read(env_path)
    if digest(env.get("measurementIdentity")) != env_id or env.get("sourceSha256") != protocol.get("sourceSha256"):
        raise ValueError(f"environment or source provenance mismatch: {path}")
    if protocol.get("confirmatory"):
        status = env["commands"]["gitStatus"]
        archive = env.get("archiveProvenance", {})
        if not ((status.get("returncode") == 0 and status.get("stdout") == "") or
                (archive.get("verified") is True and archive.get("sourceClean") is True)):
            raise ValueError("confirmatory evidence requires frozen clean source")
    for name, field in (("training.json", "trainingReportSha256"), ("v1.csv", "v1ManifestSha256"), ("v2.csv", "v2ManifestSha256")):
        if file_digest(path.parent / name) != block.get(field):
            raise ValueError(f"missing or altered underlying evidence: {name}: {path}")
    training = read(path.parent / "training.json")
    if training.get("storageEngine") != "java" or training.get("status") != "PASSED" or training.get("allPassed") is not True:
        raise ValueError(f"evidence must measure the Java engine successfully: {path}")
    accelerator = training.get("accelerator", {})
    if accelerator.get("deviceAvailable") is not True or accelerator.get("backend") != "cuda":
        raise ValueError("GPU performance evidence requires CUDA; CPU smoke is not a paper block")
    if training.get("correctness", {}).get("allChecksumsEqual") is not True or len(training["runs"]) != 1:
        raise ValueError("a paired block needs one fully validated training run")
    run = training["runs"][0]
    if run.get("modelParityPassed") is not True or run.get("engineInfo", {}).get("durability") != "DURABLE":
        raise ValueError("model parity or measured Java durability mode is missing")
    for report_key, block_key in (("cacheDynamics", "aetherDynamics"), ("mmapDynamics", "mmapDynamics")):
        if run[report_key]["invariants"]["passed"] is not True or run[report_key] != block[block_key]:
            raise ValueError("cache dynamics differ from underlying measured report")
    if run["cacheDynamics"]["prepopulatedEntries"] != block["initialReusableEntries"]:
        raise ValueError("initial reuse differs from underlying measured report")
    if run["backendOrder"] != block["backendOrder"] or sorted(run["backendOrder"]) != sorted(protocol["backends"].values()):
        raise ValueError("paired backend order does not match the protocol")
    actual = {name: run["backends"][backend]["steadyState"]["effectiveSamplesPerSecond"]
              for name, backend in protocol["backends"].items()}
    if not {"raw", "aether", "mmap"} <= set(actual) or actual != block["throughput"] or not all(
            isinstance(value, (int, float)) and math.isfinite(value) and value > 0 for value in actual.values()):
        raise ValueError("throughput is invalid or differs from the measured report")
    if "workflowExperiments" in protocol:
        population = block.get("population")
        if population is not None and (file_digest(path.parent / "population.json") != block.get("populationReportSha256") or
                                       read(path.parent / "population.json") != population):
            raise ValueError("population evidence differs from its saved report")
        expected_initial = {name: (population.get("populateOnly", {}).get("populationWallMs", 0) if name == "aether" else
                                  population.get("mmapPopulateOnly", {}).get("populationWallMs", 0) if name == "mmap" else 0)
                            if population else 0 for name in protocol["backends"]}
        if expected_initial != block["initialPopulationMs"]:
            raise ValueError("initial workflow costs differ from population evidence")
        workflow = block["workflow"]
        if len(workflow) != protocol["workflowExperiments"]:
            raise ValueError("incomplete cumulative workflow")
        cumulative = dict(expected_initial)
        for number, entry in enumerate(workflow, 1):
            expected_name = "training.json" if number == 1 else f"training-workflow-{number:03d}.json"
            if entry["experiment"] != number or entry["report"] != expected_name or file_digest(path.parent / expected_name) != entry["sha256"]:
                raise ValueError("missing, reordered or altered workflow experiment")
            report = read(path.parent / expected_name)
            run = report["runs"][0]
            if report.get("status") != "PASSED" or report.get("allPassed") is not True or report.get("storageEngine") != "java" or run.get("modelParityPassed") is not True:
                raise ValueError("failed workflow experiment")
            if not all(run[key]["invariants"]["passed"] is True for key in ("cacheDynamics", "mmapDynamics")):
                raise ValueError("workflow cache invariant failed")
            costs = {name: run["backends"][backend]["lifecycle"]["totalMs"] for name, backend in protocol["backends"].items()}
            if costs != entry["lifecycleMs"] or run["cacheDynamics"]["prepopulatedEntries"] != entry["initialReusableEntries"]:
                raise ValueError("workflow summary differs from underlying experiment")
            cumulative = {name: cumulative[name] + costs[name] for name in cumulative}
            if cumulative != entry["cumulativeWorkflowMs"]:
                raise ValueError("incorrect cumulative workflow cost")
        if cumulative != block["workflowCostMs"]:
            raise ValueError("incorrect final workflow cost")
    return block
