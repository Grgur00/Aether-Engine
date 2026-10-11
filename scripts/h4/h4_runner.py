"""Exploratory H4 paired blocks with immutable checkpoints and controlled workers."""
import argparse
import importlib.metadata
import json
import math
import os
import platform
import sys
import time
import uuid
from pathlib import Path

from .common import digest, read_evidence, write_json
from .controlled import launch
from .inputs import bind_inputs
from .kaggle_preflight import command
from .prepare_pcam_manifest import canonical_hash
from .storage_preflight import require_previous_sizes
from .validate_h4 import validate_retrieval


def source_identity(root=None):
    from paper_common import java_build_sources
    root = Path(root) if root is not None else Path(__file__).resolve().parents[2]
    paths = set((root / "scripts/h4").rglob("*.py")) | set((root / "scripts/h4").rglob("*.java"))
    paths |= set((root / "modules").glob("*/src/main/java/**/*.java"))
    paths |= set((root / "clients/python").rglob("*.py"))
    paths |= {root / "scripts/paper_common.py"}
    paths |= {root / name for name in java_build_sources(root)}
    paths |= {root / "scripts/h4/requirements.lock", root / "scripts/h4/protocol.json"}
    paths |= {path for path in (root / "modules").glob("*/src/main/resources/**/*") if path.is_file()}
    inventory = {path.relative_to(root).as_posix(): digest(path) for path in sorted(paths)}
    return dict(sha256=canonical_hash(inventory), files=inventory)


def host_identity(scratch, limit):
    packages = {}
    for name in ("numpy", "h5py", "torch", "scipy", "matplotlib"):
        try:
            packages[name] = importlib.metadata.version(name)
        except importlib.metadata.PackageNotFoundError:
            packages[name] = None
    facts = dict(platform=platform.system(), python=sys.version, packages=packages,
                 cpu=command(["lscpu", "-J"]), java=command(["java", "-version"]),
                 cpu_affinity=sorted(os.sched_getaffinity(0)) if hasattr(os, "sched_getaffinity") else None,
                 storage=command(["findmnt", "-J", "-T", str(Path(scratch).resolve()), "-o", "FSTYPE,OPTIONS"]),
                 memory_limit_bytes=limit)
    for name in ("cpu", "java", "storage"):
        if facts[name].get("returncode") != 0:
            raise ValueError("required host comparability evidence unavailable: " + name)
    return dict(sha256=host_class_hash(facts), facts=facts,
                host_id=read_evidence("/etc/machine-id"), boot_id=read_evidence("/proc/sys/kernel/random/boot_id"))


def host_class_hash(facts):
    cpu = {item["field"].rstrip(":"): item["data"] for item in json.loads(facts["cpu"]["stdout"])["lscpu"]}
    required = ("Architecture", "CPU(s)", "Model name")
    if any(key not in cpu for key in required) or not facts["cpu_affinity"]:
        raise ValueError("stable CPU comparability evidence unavailable")
    storage = json.loads(facts["storage"]["stdout"])["filesystems"]
    if len(storage) != 1:
        raise ValueError("ambiguous artifact storage filesystem")
    options = sorted(option for option in storage[0]["options"].split(",")
                     if option.partition("=")[0] not in ("lowerdir", "upperdir", "workdir"))
    comparable = dict(platform=facts["platform"], python=facts["python"], packages=facts["packages"],
                      cpu={key: cpu[key] for key in required}, available_core_count=len(facts["cpu_affinity"]),
                      java={key: value for key, value in facts["java"].items() if key != "command"},
                      storage=dict(fstype=storage[0]["fstype"], options=options),
                      memory_limit_bytes=facts["memory_limit_bytes"])
    return canonical_hash(comparable)


def backend_order(block_id, seed):
    if block_id not in range(5):
        raise ValueError("exploratory pilot block IDs are 0 through 4")
    reverse = bool(block_id % 2) if block_id < 4 else bool(seed % 2)
    return ["mmap", "aether"] if reverse else ["aether", "mmap"]


def archive_checksums(directory):
    directory = Path(directory)
    inventory = {path.relative_to(directory).as_posix(): digest(path) for path in sorted(directory.rglob("*"))
                 if path.is_file() and path != directory / "sha256.json"}
    write_json(directory / "sha256.json", inventory)


def run(args):
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    binding = bind_inputs(args.data, args.prepared, args.protocol)
    protocol = binding["protocol"]
    if protocol["confirmatory_frozen"] or protocol["phase"] != "feasibility":
        raise ValueError("this runner is exploratory only; confirmatory execution needs a separately frozen design")
    design = None
    if args.design is not None:
        from .confirmatory import validate_design
        design = validate_design(json.loads(args.design.read_text()))
        if args.conditions != [design["condition"]]:
            raise ValueError("confirmatory execution requires only the frozen primary condition")
    allowed_blocks = design["block_ids"] if design is not None else range(5)
    if sorted(set(args.blocks)) != sorted(args.blocks) or any(block not in allowed_blocks for block in args.blocks):
        raise ValueError("unique block IDs from the frozen assignment required")
    if len(set(args.conditions)) != len(args.conditions) or any(index not in range(5) for index in args.conditions):
        raise ValueError("unique condition IDs 0 through 4 required")
    import paper_common
    paper_common.java_classpath()
    java_sources = paper_common.java_build_sources(paper_common.ROOT)
    for index in args.conditions:
        payload = binding["manifest"]["conditions"][index]["payload_bytes"]
        rounded = math.ceil(payload / 1024 ** 3) * 1024 ** 3
        require_previous_sizes(max(2 * 1024 ** 3, rounded), args.prior_preflight,
                               expected_java_sources=java_sources)
    source = source_identity()
    host = host_identity(args.scratch, protocol["memory_limit_bytes"])
    if design is not None:
        current = dict(protocol_sha256=binding["protocol_sha256"], manifest_sha256=binding["manifest_sha256"],
                       dataset_sha256=binding["dataset_sha256"], source_sha256=source["sha256"], host_class_sha256=host["sha256"])
        if any(design[key] != value for key, value in current.items()):
            raise ValueError("confirmatory implementation/data/protocol/host differs from frozen design")
    plan = dict(schema="aether-h4-pilot-plan-v1", protocol_sha256=binding["protocol_sha256"],
                manifest_sha256=binding["manifest_sha256"], dataset_sha256=binding["dataset_sha256"],
                source_sha256=source["sha256"], host_class_sha256=host["sha256"],
                dataset_version=args.dataset_version, phase="confirmatory" if design is not None else "exploratory", conditions=args.conditions,
                design_sha256=design["sha256"] if design is not None else None,
                blocks=args.blocks, ordering={str(block): design["ordering"][str(block)] if design is not None
                                             else backend_order(block, protocol["lookup_seed"]) for block in args.blocks})
    plan_path = output / "plan.json"
    if plan_path.exists():
        if json.loads(plan_path.read_text()) != plan:
            raise ValueError("resume identity, assignment or host compatibility differs from immutable plan")
    else:
        if any(output.iterdir()):
            raise ValueError("new campaign output must be empty")
        write_json(plan_path, plan)
        write_json(output / "source.json", source)
        if design is not None:
            write_json(output / "confirmatory-design.json", design)
    session_id = uuid.uuid4().hex
    write_json(output / ("session-" + session_id + ".json"), host)
    started = time.monotonic()
    estimate = args.estimated_block_seconds
    for condition_id in args.conditions:
        condition = binding["manifest"]["conditions"][condition_id]
        selected_ids = set(condition["sequential_ids"])
        rows = [row for row in binding["rows"] if row["sample_id"] in selected_ids]
        for block_id in args.blocks:
            directory = output / f"condition-{condition_id}-block-{block_id}"
            if directory.exists():
                validate_checkpoint(directory, plan, condition_id, block_id, rows, condition)
                continue
            if time.monotonic() - started + estimate + 600 >= protocol["soft_deadline_seconds"]:
                write_json(output / ("deadline-" + session_id + ".json"), dict(next_condition=condition_id,
                           next_block=block_id, reason="insufficient conservative time for a complete paired block"))
                archive_checksums(output)
                return 2
            directory.mkdir()
            block = dict(schema="aether-h4-paired-block-v1", **{key: plan[key] for key in
                         ("protocol_sha256", "manifest_sha256", "dataset_sha256", "source_sha256", "host_class_sha256")},
                         session_id=session_id, block_id=block_id, condition=condition_id,
                         phase=plan["phase"], design_sha256=plan["design_sha256"],
                         backend_order=plan["ordering"][str(block_id)], results={}, passed=False,
                         primary_h4_eligible=False, requested_ratio=condition["requested_ratio"])
            block_start = time.monotonic()
            try:
                for backend in block["backend_order"]:
                    print(f"H4 pilot condition={condition_id} block={block_id} starting {backend}", flush=True)
                    task = dict(backend=backend, condition=condition_id, data=str(args.data.resolve()),
                                prepared=str(args.prepared.resolve()), protocol=str(args.protocol.resolve()),
                                scratch=str(args.scratch.resolve()), memory_limit_bytes=protocol["memory_limit_bytes"],
                                prior_preflights=[str(path.resolve()) for path in args.prior_preflight],
                                progress=str(directory / (backend + "-progress.json")),
                                diagnostics=str(directory / (backend + "-diagnostics")))
                    task_path = directory / (backend + "-task.json")
                    write_json(task_path, task)
                    controller = launch(task_path, directory / backend, limit=protocol["memory_limit_bytes"],
                                        timeout_seconds=max(1, min(estimate, protocol["soft_deadline_seconds"] - (time.monotonic() - started) - 600)))
                    if not controller["completed"] or not controller["cleanup_completed"]:
                        raise ValueError("controlled worker failed or cleanup incomplete: " + backend)
                    record = json.loads((directory / backend / "backend.json").read_text())
                    if not record["passed"] or not record["scratch_cleanup_completed"] or not record["source_verified"]:
                        raise ValueError("backend lifecycle/correctness gate failed: " + backend)
                    if any(record[key] != binding[key] for key in ("protocol_sha256", "manifest_sha256", "dataset_sha256")):
                        raise ValueError("worker inputs changed since campaign binding")
                    for pattern in protocol["access_patterns"]:
                        validate_retrieval(record["patterns"][pattern], rows, condition[pattern + "_ids"], pattern)
                    block["results"][backend] = record
                for pattern in protocol["access_patterns"]:
                    aether = block["results"]["aether"]["patterns"][pattern]["verified_reference"]
                    mmap = block["results"]["mmap"]["patterns"][pattern]["verified_reference"]
                    if aether != mmap:
                        raise ValueError("paired backends consumed different artifact bytes")
                if source_identity()["sha256"] != plan["source_sha256"]:
                    raise ValueError("source implementation changed during paired block")
                block["passed"] = True
                block["out_of_core_verified"] = all(record["patterns"]["shuffled"]["out_of_core_resource_evidence_verified"]
                                                     for record in block["results"].values())
            except (OSError, ValueError, RuntimeError) as error:
                block.update(error_type=type(error).__name__, error=str(error))
            block["lifecycle_seconds"] = time.monotonic() - block_start
            write_json(directory / "block.json", block)
            archive_checksums(directory)
            archive_checksums(output)
            if not block["passed"]:
                return 2
            estimate = max(estimate, block["lifecycle_seconds"] * 1.5)
    return 0


def validate_checkpoint(directory, plan, condition_id, block_id, rows, condition):
    inventory = json.loads((directory / "sha256.json").read_text())
    files = {path.relative_to(directory).as_posix() for path in directory.rglob("*")
             if path.is_file() and path != directory / "sha256.json"}
    if set(inventory) != files or any(digest(directory / name) != inventory[name] for name in files):
        raise ValueError("checkpoint inventory differs; failed/incomplete blocks are never silently retried")
    block = json.loads((directory / "block.json").read_text())
    if (block["passed"] is not True or block["condition"] != condition_id or block["block_id"] != block_id
            or set(block["results"]) != {"aether", "mmap"}
            or block["backend_order"] != plan["ordering"][str(block_id)]
            or any(block[key] != plan[key] for key in ("protocol_sha256", "manifest_sha256", "dataset_sha256",
                                                     "source_sha256", "host_class_sha256"))):
        raise ValueError("checkpoint is invalid or belongs to another immutable campaign")
    if block.get("phase", "exploratory") != plan["phase"] or block.get("design_sha256") != plan.get("design_sha256"):
        raise ValueError("checkpoint phase/design differs from frozen campaign")
    for record in block["results"].values():
        if (record["passed"] is not True or record["source_verified"] is not True
                or record["scratch_cleanup_completed"] is not True):
            raise ValueError("checkpoint backend lifecycle is not valid")
        if any(record[key] != plan[key] for key in ("protocol_sha256", "manifest_sha256", "dataset_sha256")):
            raise ValueError("checkpoint backend input binding differs from immutable plan")
        for pattern in ("sequential", "shuffled"):
            validate_retrieval(record["patterns"][pattern], rows, condition[pattern + "_ids"], pattern)
    for pattern in ("sequential", "shuffled"):
        if (block["results"]["aether"]["patterns"][pattern]["verified_reference"]
                != block["results"]["mmap"]["patterns"][pattern]["verified_reference"]):
            raise ValueError("checkpoint paired content differs")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("data", "prepared", "scratch", "output"):
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--protocol", type=Path, default=Path(__file__).with_name("protocol.json"))
    parser.add_argument("--design", type=Path, help="separately frozen confirmatory-design.json; otherwise exploratory only")
    parser.add_argument("--dataset-version", required=True, help="versioned Kaggle dataset identity; recorded, not inferred")
    parser.add_argument("--conditions", type=int, nargs="+", required=True)
    parser.add_argument("--blocks", type=int, nargs="+", required=True)
    parser.add_argument("--prior-preflight", type=Path, action="append", default=[])
    parser.add_argument("--estimated-block-seconds", type=float, required=True,
                        help="conservative complete-pair duration from diagnostic evidence")
    args = parser.parse_args()
    if not math.isfinite(args.estimated_block_seconds) or args.estimated_block_seconds <= 0:
        parser.error("positive finite complete-pair estimate required")
    try:
        return run(args)
    except (OSError, ValueError, RuntimeError, KeyError) as error:
        args.output.mkdir(parents=True, exist_ok=True)
        write_json(args.output / ("failure-" + uuid.uuid4().hex + ".json"),
                   dict(passed=False, primary_h4_eligible=False, error_type=type(error).__name__, error=str(error)))
        print("H4 pilot did not start/complete: " + str(error), flush=True)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
