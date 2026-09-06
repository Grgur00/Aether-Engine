"""Run isolated, paired Java/mmap GPU blocks on Kaggle or a controlled CUDA host."""
import argparse
import csv
import hashlib
import itertools
import json
import subprocess
import shutil
import sys
import time
from pathlib import Path

from evidence import validate_block
from paper_common import ROOT, PYTHON_CLIENT, capture, environment, java_daemon, sha256, write_json

BACKEND_IDS = {"raw": "RAW_RECOMPUTE", "aether": "AETHER_CACHE",
               "mmap": "STATIC_PREPROCESSED_MMAP", "ram": "RAM_READY"}


def digest(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, separators=(",", ":")).encode()).hexdigest()


def numeric_list(value, cast=int):
    return [cast(item) for item in value.split(",")]


def parser():
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("--config", type=Path, default=ROOT / "configs/paper/datasets.json")
    result.add_argument("--datasets", default="oct5k")
    result.add_argument("--backends", default="raw,aether,mmap,ram")
    result.add_argument("--repeats", type=int, default=24)
    result.add_argument("--reuse-ratios", help="Percentages; omitted uses the supplied V1/V2 manifests")
    result.add_argument("--sizes", help="Requested V2 cardinalities, never synthetic duplication")
    result.add_argument("--workers", default="0")
    result.add_argument("--preprocess-passes", default="4")
    result.add_argument("--gpu-counts", default="1")
    result.add_argument("--epochs", type=int, default=5)
    result.add_argument("--batch-size", type=int, default=16)
    result.add_argument("--max-reference-bytes", type=int, default=16 * 1024 ** 3)
    result.add_argument("--measured-steps", type=int, default=0)
    result.add_argument("--workflow-experiments", type=int, default=1, help="Successive experiments reusing the same V2 stores within each paired block")
    result.add_argument("--seed-base", type=int, default=20260904)
    result.add_argument("--randomize-backend-order", action="store_true", help="Always enabled")
    result.add_argument("--paired-blocks", action="store_true", help="Always enabled")
    result.add_argument("--confirmatory", action="store_true", help="Require clean source and >=24 paired primary blocks")
    result.add_argument("--plan-only", action="store_true")
    result.add_argument("--resume", action="store_true", help="Skip only complete blocks with matching protocol and environment")
    result.add_argument("--retain-stores", action="store_true", help="Retain per-block generated caches (can require substantial disk space)")
    result.add_argument("--output", type=Path, default=ROOT / "results/raw")
    return result


def build_plan(args):
    selected = args.backends.split(",")
    if not {"raw", "aether", "mmap"} <= set(selected) or not set(selected) <= set(BACKEND_IDS):
        raise ValueError("paired blocks require raw,aether,mmap and optionally ram")
    if args.confirmatory and set(selected) != set(BACKEND_IDS):
        raise ValueError("confirmatory primary blocks require all four backends")
    if args.repeats < 1 or args.epochs < 1 or args.batch_size < 1 or args.measured_steps < 0 or args.workflow_experiments < 1:
        raise ValueError("positive repeats/epochs/batch size and nonnegative measured steps required")
    config = json.loads(args.config.read_text(encoding="utf-8"))
    conditions = []
    for dataset in args.datasets.split(","):
        spec = config[dataset]
        sizes = numeric_list(args.sizes) if args.sizes else [spec["samplesV2"]]
        reuses = numeric_list(args.reuse_ratios, float) if args.reuse_ratios else [None]
        for size, reuse, workers, passes, gpus in itertools.product(sizes, reuses,
                numeric_list(args.workers), numeric_list(args.preprocess_passes), numeric_list(args.gpu_counts)):
            if size < 1 or workers < 0 or passes < 1 or gpus not in (1, 2) or (reuse is not None and not 0 <= reuse <= 100):
                raise ValueError("invalid matrix point")
            point = dict(dataset=dataset, samples=size, reusePercent=reuse, workers=workers,
                         preprocessPasses=passes, gpuCount=gpus, specification=spec)
            point["conditionId"] = digest(point)[:16]
            conditions.append(point)
    plan = {"schema": "aether-paper-protocol-v1", "conditions": conditions, "repeats": args.repeats,
            "epochs": args.epochs, "batchSize": args.batch_size, "seedBase": args.seed_base,
            "measuredSteps": args.measured_steps, "workflowExperiments": args.workflow_experiments, "backendOrder": "seeded random permutation within each paired block",
            "measurementRole": "fixed-window" if args.measured_steps else "lifecycle-training",
            "pageCache": "uncontrolled; source/hash/reference/presence preflight warms files",
            "engine": "Java TrainingCache over loopback", "durability": "DURABLE",
            "backends": {name: BACKEND_IDS[name] for name in selected}, "maxReferenceBytes": args.max_reference_bytes,
            "alpha": .05, "equivalenceMargin": .03,
            "primaryFamily": "paired Aether/RAW two-sided t-test and Aether/mmap TOST; Holm",
            "bootstrapResamples": 10000, "confirmatory": args.confirmatory}
    if args.confirmatory and (args.repeats < 24 or args.datasets != "oct5k" or len(conditions) != 1
                             or args.reuse_ratios is not None or args.measured_steps or args.workflow_experiments != 1):
        raise ValueError("confirmatory mode requires >=24 OCT5K V1/V2 primary blocks with no sweeps")
    return plan


def manifest_rows(path, split):
    with Path(path).open(newline="", encoding="utf-8") as stream:
        reader = csv.DictReader(stream)
        fields = reader.fieldnames
        rows = [row for row in reader if not row.get("split") or row["split"] == split]
    return fields, rows


def subset_manifest(source, target, count, split):
    source, target = Path(source).resolve(), Path(target).resolve()
    fields, rows = manifest_rows(source, split)
    if len(rows) < count:
        raise ValueError(f"{source} has only {len(rows)} real rows, requested {count}")
    with target.open("w", newline="", encoding="utf-8") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        for row in rows[:count]:
            for field in ("image_path", "mask_path"):
                if row.get(field):
                    path = Path(row[field])
                    row[field] = str(path if path.is_absolute() else (source.parent / path).resolve())
            writer.writerow(row)


def invoke(command, output):
    started = time.perf_counter()
    with Path(output).with_suffix(".log").open("w", encoding="utf-8") as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    report = json.loads(Path(output).read_text(encoding="utf-8"))
    if report.get("status") != "PASSED" or not report.get("allPassed"):
        raise RuntimeError(f"benchmark did not pass: {output}: {report.get('skipReason')}")
    return report, time.perf_counter() - started


def run_block(plan, point, index, directory, environment_id, protocol_hash):
    directory.mkdir(parents=True, exist_ok=False)
    spec = point["specification"]
    split = spec.get("split", "train")
    v2 = directory / "v2.csv"
    subset_manifest(spec["manifestV2"], v2, point["samples"], split)
    count_v1 = spec["samplesV1"] if point["reusePercent"] is None else round(point["samples"] * point["reusePercent"] / 100)
    v1 = directory / "v1.csv"
    source_v1 = spec["manifestV1"] if point["reusePercent"] is None else spec["manifestV2"]
    subset_manifest(source_v1, v1, count_v1, split)
    seed = plan["seedBase"] + index
    common = [sys.executable, str(PYTHON_CLIENT / "benchmark_gpu_segmentation.py"),
        "--dataset-kind", point["dataset"], "--dataset-split", split,
        "--resize", str(spec.get("imageSize", 256)), "--num-classes", str(spec.get("numClasses", 1000)),
        "--preprocess-passes", str(point["preprocessPasses"]), "--batch-size", str(plan["batchSize"]),
        "--backends", ",".join(plan["backends"]), "--max-reference-bytes", str(plan["maxReferenceBytes"]),
        "--seed", str(seed), "--aether-engine", "java", "--cache-durability", "durable", "--aether-namespace", "paired-main",
        "--mmap-cache-dir", str(directory / "mmap"), "--warmup-steps", "0"]
    population, population_wall = None, 0.0
    # A separate process lifetime gives V1 -> restart -> V2 persistence evidence.
    if count_v1:
        with java_daemon(directory / "java") as daemon:
            output = directory / "population.json"
            population, population_wall = invoke(common + ["--aether-port", str(daemon["port"]),
                "--dataset-manifest", str(v1), "--samples", str(count_v1), "--epochs", "1",
                "--aether-populate-only", "--mmap-populate-only", "--output", str(output)], output)
    workflow = []
    for experiment in range(plan["workflowExperiments"]):
        with java_daemon(directory / "java") as daemon:
            output = directory / ("training.json" if experiment == 0 else f"training-workflow-{experiment + 1:03d}.json")
            report, command_wall = invoke(common + ["--aether-port", str(daemon["port"]),
                "--dataset-manifest", str(v2), "--samples", str(point["samples"]),
                "--aether-cache-mode", "reuse", "--mmap-cache-mode", "reuse",
                "--workers", str(point["workers"]), "--gpu-count", str(point["gpuCount"]),
                "--epochs", str(plan["epochs"]), "--measured-steps", str(plan["measuredSteps"]),
                "--prefetch-batches", "1", "--accelerator-backend", "cuda", "--output", str(output)], output)
        run_report = report["runs"][0]
        if experiment == 0:
            training, training_wall = report, command_wall
        elif plan["measuredSteps"] == 0 and run_report["cacheDynamics"]["prepopulatedEntries"] != point["samples"]:
            raise RuntimeError("successive full V2 experiment failed to reuse every previous artifact")
        workflow.append({"experiment": experiment + 1, "report": output.name, "sha256": sha256(output),
            "commandWallSeconds": command_wall, "initialReusableEntries": run_report["cacheDynamics"]["prepopulatedEntries"],
            "lifecycleMs": {name: run_report["backends"][backend]["lifecycle"]["totalMs"] for name, backend in plan["backends"].items()}})
    output = directory / "training.json"
    run = training["runs"][0]
    throughput = {name: run["backends"][backend]["steadyState"]["effectiveSamplesPerSecond"] for name, backend in plan["backends"].items()}
    initial = run["cacheDynamics"]["prepopulatedEntries"]
    expected = spec.get("expectedReusable") if point["reusePercent"] is None else count_v1
    if expected is not None and initial != expected:
        raise RuntimeError(f"initial reuse mismatch: measured {initial}, expected {expected}")
    block = {"schema": "aether-paper-block-v1", "status": "PASSED", "protocolHash": protocol_hash,
             "environmentId": environment_id, "conditionId": point["conditionId"], "blockIndex": index,
             "seed": seed, "backendOrder": run["backendOrder"], "condition": point,
             "throughput": throughput, "correctnessPassed": training["correctness"]["allChecksumsEqual"] and run["modelParityPassed"],
             "initialReusableEntries": initial, "mmapDynamics": run["mmapDynamics"],
             "aetherDynamics": run["cacheDynamics"], "populationCommandWallSeconds": population_wall,
             "trainingCommandWallSeconds": training_wall, "population": population,
             "populationReportSha256": sha256(directory / "population.json") if population else None,
             "trainingReportSha256": sha256(output), "v1ManifestSha256": sha256(v1), "v2ManifestSha256": sha256(v2)}
    initial_cost = {name: (population.get("populateOnly", {}).get("populationWallMs", 0) if name == "aether" else
                         population.get("mmapPopulateOnly", {}).get("populationWallMs", 0) if name == "mmap" else 0)
                    if population else 0 for name in plan["backends"]}
    cumulative = dict(initial_cost)
    for experiment in workflow:
        cumulative = {name: cumulative[name] + experiment["lifecycleMs"][name] for name in cumulative}
        experiment["cumulativeWorkflowMs"] = dict(cumulative)
    block["workflow"] = workflow
    block["initialPopulationMs"] = initial_cost
    block["workflowCostMs"] = cumulative
    block["workflowCostScope"] = "V1 population plus successive fresh-model V2 experiments over shared persistent stores; reference/checksum validation and daemon startup excluded, command walls reported separately"
    write_json(directory / "block.json", block)
    validate_block(directory / "block.json")
    return block


def main(argv=None):
    args = parser().parse_args(argv)
    plan = build_plan(args)
    args.output = args.output.resolve()
    args.output.mkdir(parents=True, exist_ok=True)
    if args.plan_only:
        write_json(args.output / "plan.json", plan)
        print(f"Planned {len(plan['conditions']) * args.repeats} paired blocks; no experiments executed")
        return
    provenance = environment()
    status = provenance["commands"]["gitStatus"]
    clean_git = status.get("returncode") == 0 and not status.get("stdout")
    archive = provenance.get("archiveProvenance", {})
    clean_archive = archive.get("verified") and archive.get("sourceClean")
    if args.confirmatory and not (clean_git or clean_archive):
        raise RuntimeError("confirmatory runs require a clean, frozen Git checkout")
    manifest_hashes = {}
    for point in plan["conditions"]:
        spec = point["specification"]
        for key in ("manifestV1", "manifestV2"):
            manifest_hashes[spec[key]] = sha256(spec[key])
    plan["manifestSha256"] = manifest_hashes
    plan["sourceSha256"] = provenance["sourceSha256"]
    protocol_hash = digest(plan)
    stable_gpu = capture(["nvidia-smi", "--query-gpu=name,uuid,driver_version,memory.total,pci.bus_id", "--format=csv,noheader"])
    stable_cpu = capture(["lscpu", "-J", "-e=CPU,CORE,SOCKET,NODE"])
    provenance["measurementIdentity"] = {key: provenance["commands"][key] for key in ("java", "packages")} | {"platform": provenance["platform"], "gpu": stable_gpu, "cpu": stable_cpu}
    environment_id = digest(provenance["measurementIdentity"])
    protocol_path = args.output / "protocol.json"
    if protocol_path.exists() and json.loads(protocol_path.read_text()) != plan:
        raise RuntimeError("output contains a different frozen protocol; choose a new directory")
    write_json(protocol_path, plan)
    write_json(args.output / f"environment-{environment_id}.json", provenance)
    for point in plan["conditions"]:
        for index in range(args.repeats):
            directory = args.output / point["conditionId"] / f"block-{index:04d}"
            if args.resume and (directory / "block.json").exists():
                existing = validate_block(directory / "block.json")
                if existing["protocolHash"] != protocol_hash or existing["environmentId"] != environment_id or existing["status"] != "PASSED":
                    raise RuntimeError("resume would mix protocols/environments or reuse a failed block")
                continue
            if args.resume and directory.exists():
                # Preserve interrupted evidence and restart the entire paired block.
                archive = directory.with_name(directory.name + f"-interrupted-{time.time_ns()}")
                if not directory.resolve().is_relative_to(args.output) or not archive.resolve().is_relative_to(args.output):
                    raise ValueError("resume paths escape the experiment output")
                directory.rename(archive)
            directory.parent.mkdir(parents=True, exist_ok=True)
            print(f"{point['dataset']} {point['conditionId']} block {index + 1}/{args.repeats}", flush=True)
            run_block(plan, point, index, directory, environment_id, protocol_hash)
            if not args.retain_stores:
                for name in ("java", "mmap"):
                    generated = directory / name
                    if generated.exists():
                        if generated.is_symlink() or not generated.resolve().is_relative_to(directory.resolve()):
                            raise ValueError("generated store path escapes its owned block directory")
                        shutil.rmtree(generated)


if __name__ == "__main__":
    main()
