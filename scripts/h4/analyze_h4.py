"""Paired-block H4 analysis, with explicit exclusions and metric availability."""
import argparse
import csv
import json
import math
from pathlib import Path

import numpy as np
from scipy import stats

from .common import counters, digest, write_json
from .h4_runner import archive_checksums, host_class_hash, validate_checkpoint
from .inputs import bind_inputs
from .memory_controller import io_counters
from .prepare_pcam_manifest import canonical_hash


IDENTITY_KEYS = ("protocol_sha256", "manifest_sha256", "dataset_sha256", "source_sha256", "host_class_sha256")


def verify_inventory(directory):
    directory = Path(directory)
    inventory = json.loads((directory / "sha256.json").read_text())
    files = {path.relative_to(directory).as_posix() for path in directory.rglob("*")
             if path.is_file() and path != directory / "sha256.json"}
    if set(inventory) != files or any(digest(directory / name) != inventory[name] for name in files):
        raise ValueError("archived inventory differs: " + str(directory))
    return digest(directory / "sha256.json")


def load_campaigns(campaigns, prepared, protocol_path):
    # Analysis checks immutable manifests; live source validation belongs to archived workers.
    binding = bind_inputs(prepared, prepared, protocol_path, verify_source=False)
    blocks, excluded, seen, archives = [], [], set(), []
    identity, design = None, None
    for directory in map(Path, campaigns):
        archive_hash = verify_inventory(directory)
        plan = json.loads((directory / "plan.json").read_text())
        expected_identity = {key: plan[key] for key in IDENTITY_KEYS}
        if any(plan[key] != binding[key] for key in IDENTITY_KEYS[:3]):
            raise ValueError("campaign manifest/dataset/protocol differs from analysis inputs")
        if identity is not None and identity != expected_identity:
            raise ValueError("cannot pool incompatible source/data/protocol/host classes")
        identity = expected_identity
        if plan["phase"] not in ("exploratory", "confirmatory"):
            raise ValueError("unknown campaign phase")
        source = json.loads((directory / "source.json").read_text())
        if source["sha256"] != plan["source_sha256"] or canonical_hash(source["files"]) != source["sha256"]:
            raise ValueError("campaign source inventory does not support source identity")
        if plan["phase"] == "confirmatory":
            from .confirmatory import validate_design
            current_design = validate_design(json.loads((directory / "confirmatory-design.json").read_text()))
            if (current_design["sha256"] != plan["design_sha256"]
                    or any(current_design[key] != plan[key] for key in IDENTITY_KEYS)
                    or design is not None and current_design != design):
                raise ValueError("confirmatory archives differ from frozen design")
            design = current_design
        archives.append(dict(directory=str(directory.resolve()), inventory_sha256=archive_hash,
                             plan_sha256=digest(directory / "plan.json"), phase=plan["phase"]))
        for block_directory in sorted(directory.glob("condition-*-block-*")):
            if not block_directory.is_dir():
                continue
            verify_inventory(block_directory)
            block = json.loads((block_directory / "block.json").read_text())
            key = (block["condition"], block["block_id"])
            if key in seen:
                raise ValueError("duplicate paired block identity across archives")
            seen.add(key)
            if block_directory.name != f"condition-{key[0]}-block-{key[1]}" or key[0] not in plan["conditions"] or key[1] not in plan["blocks"]:
                raise ValueError("block not assigned by immutable campaign plan")
            if block["passed"] is not True:
                excluded.append(dict(condition=key[0], block_id=key[1], directory=str(block_directory),
                                     reason=block.get("error", "backend/pair failed")))
                continue
            condition = binding["manifest"]["conditions"][key[0]]
            membership = set(condition["sequential_ids"])
            rows = [row for row in binding["rows"] if row["sample_id"] in membership]
            validate_checkpoint(block_directory, plan, *key, rows, condition)
            if design is not None:
                if key[0] != design["condition"] or key[1] not in design["block_ids"] or block["backend_order"] != design["ordering"][str(key[1])]:
                    raise ValueError("confirmatory block does not follow predeclared condition/order")
                if digest(block_directory / "block.json") in design["pilot_record_sha256"]:
                    raise ValueError("pilot observations cannot be reused in confirmation")
            session = json.loads((directory / ("session-" + block["session_id"] + ".json")).read_text())
            if session["sha256"] != plan["host_class_sha256"] or host_class_hash(session["facts"]) != session["sha256"]:
                raise ValueError("block session host evidence differs from frozen host class")
            block = dict(block, phase=plan["phase"], archive=str(directory.resolve()),
                         block_record_sha256=digest(block_directory / "block.json"))
            blocks.append(block)
    if not blocks:
        raise ValueError("no complete validated paired blocks; no performance summary can be generated")
    if len({item["phase"] for item in archives}) != 1:
        raise ValueError("pilot and confirmatory records must be analyzed separately")
    return dict(binding=binding, blocks=blocks, excluded=excluded, archives=archives, identity=identity,
                phase=archives[0]["phase"], design=design)


def paired_statistics(ratios, *, confidence=.95):
    values = np.asarray(ratios, dtype=float)
    if values.ndim != 1 or not len(values) or not np.isfinite(values).all() or (values <= 0).any():
        raise ValueError("finite positive paired throughput ratios required")
    if not 0 < confidence < 1:
        raise ValueError("confidence level must be between zero and one")
    logs = np.log(values)
    result = dict(paired_blocks=len(values), log_ratios=logs.tolist(),
                  geometric_mean_ratio=float(np.exp(logs.mean())), confidence_level=confidence,
                  ci_lower=None, ci_upper=None, inference_available=False,
                  method="Student t interval on independent paired-block log throughput ratios",
                  assumptions="independent blocks; approximately normal block log ratios; passes are not replicates")
    if len(logs) >= 2:
        error = float(stats.sem(logs))
        delta = float(stats.t.ppf((1 + confidence) / 2, len(logs) - 1)) * error
        try:
            lower, upper = math.exp(float(logs.mean()) - delta), math.exp(float(logs.mean()) + delta)
            if lower <= 0:
                raise OverflowError("interval underflow")
            result.update(ci_lower=lower, ci_upper=upper, inference_available=True)
        except OverflowError:
            result["interval_unavailable_reason"] = "interval exceeds representable positive floating-point range"
    return result


def metric_delta(before, after, filename, key):
    try:
        if before is None or after is None or not before[filename]["available"] or not after[filename]["available"]:
            return None
        start, end = counters(before[filename]["text"]), counters(after[filename]["text"])
        delta = end[key] - start[key]
        return delta if delta >= 0 else None
    except (KeyError, TypeError, ValueError):
        return None


def io_delta(before, after, key):
    try:
        if before is None or after is None or not before["io.stat"]["available"] or not after["io.stat"]["available"]:
            return None
        start, end = io_counters(before["io.stat"]["text"]), io_counters(after["io.stat"]["text"])
        if not end or not set(start).issubset(end):
            return None
        deltas = [values[key] - start.get(device, {}).get(key, 0) for device, values in end.items()]
        return sum(deltas) if all(value >= 0 for value in deltas) else None
    except (KeyError, TypeError, ValueError):
        return None


def available_mean(values):
    available = [value for value in values if value is not None]
    return dict(mean=sum(available) / len(available) if available else None,
                available=len(available), total=len(values))


def process_window_metrics(item):
    cpu_seconds, faults, rss = [], [], []
    for pid in item.get("workload_pids", item["processes_before"].keys()):
        try:
            before, after = item["processes_before"][str(pid)], item["processes_after"][str(pid)]
            if not before["stat"]["available"] or not after["stat"]["available"]:
                raise ValueError("process stat unavailable")
            ticks, pages = before["clock_ticks_per_second"], before["page_size_bytes"]
            if (ticks is None or pages is None or ticks <= 0 or pages <= 0
                    or after["clock_ticks_per_second"] != ticks or after["page_size_bytes"] != pages):
                raise ValueError("process counter units unavailable or changed")
            # Linux stat's parenthesized comm may itself contain spaces or parentheses.
            start, end = [state["stat"]["text"].rsplit(")", 1)[1].split() for state in (before, after)]
            cpu_delta = (int(end[11]) + int(end[12]) - int(start[11]) - int(start[12])) / ticks
            fault_delta = int(end[9]) - int(start[9])
            resident = max(int(start[21]), int(end[21])) * pages
            if min(cpu_delta, fault_delta, resident) < 0:
                raise ValueError("process counters regressed")
            cpu_seconds.append(cpu_delta)
            faults.append(fault_delta)
            rss.append(resident)
        except (KeyError, TypeError, ValueError, IndexError):
            cpu_seconds.append(None)
            faults.append(None)
            rss.append(None)
    complete = bool(cpu_seconds) and all(value is not None for value in cpu_seconds)
    return dict(cpu_seconds=sum(cpu_seconds) if complete else None,
                process_major_page_faults=sum(faults) if complete else None,
                sampled_process_rss_bytes=sum(rss) if complete else None,
                available_processes=sum(value is not None for value in cpu_seconds), total_processes=len(cpu_seconds),
                rss_policy="sum of endpoint RSS maxima; not exact peak or unique physical memory")


def observations(loaded):
    records = []
    for block in loaded["blocks"]:
        condition = loaded["binding"]["manifest"]["conditions"][block["condition"]]
        for pattern in ("sequential", "shuffled"):
            for backend in ("aether", "mmap"):
                lifecycle = block["results"][backend]
                workload = lifecycle["patterns"][pattern]
                passes = workload["measured_passes"]
                physical = [io_delta(item["cgroup_before"], item["cgroup_after"], "rbytes") for item in passes]
                writes = [io_delta(item["cgroup_before"], item["cgroup_after"], "wbytes") for item in passes]
                refaults = [metric_delta(item["cgroup_before"], item["cgroup_after"], "memory.stat", "workingset_refault_file") for item in passes]
                faults = [metric_delta(item["cgroup_before"], item["cgroup_after"], "memory.stat", "pgmajfault") for item in passes]
                peaks = []
                for item in passes:
                    state = item["cgroup_after"]
                    try:
                        peaks.append(int(state["memory.peak"]["text"]) if state["memory.peak"]["available"] else None)
                    except (KeyError, TypeError, ValueError):
                        peaks.append(None)
                resources = dict(physical_read_bytes=available_mean(physical), physical_write_bytes=available_mean(writes),
                                 file_refaults=available_mean(refaults), major_page_faults=available_mean(faults),
                                 memory_peak_bytes=dict(mean=max((value for value in peaks if value is not None), default=None),
                                                        available=sum(value is not None for value in peaks), total=3))
                processes = [process_window_metrics(dict(item, workload_pids=workload["workload_pids"])) for item in passes]
                records.append(dict(condition=block["condition"], block_id=block["block_id"], session_id=block["session_id"],
                                    backend=backend, access_pattern=pattern, requested_ratio=condition["requested_ratio"],
                                    actual_payload_memory_ratio=condition["actual_payload_memory_ratio"],
                                    payload_bytes=condition["payload_bytes"], samples=condition["samples"],
                                    throughput=workload["block_mean_artifacts_per_second"],
                                    pass_seconds=[item["seconds"] for item in passes],
                                    latency={key: workload["latency"][key] for key in ("p50", "p95", "p99")},
                                    resources=resources, python_cpu_seconds=available_mean([item["python_cpu_seconds"] for item in passes]),
                                    total_process_cpu_seconds=available_mean([item["cpu_seconds"] for item in processes]),
                                    sampled_total_process_rss_bytes=available_mean([item["sampled_process_rss_bytes"] for item in processes]),
                                    process_metrics=processes,
                                    store_file_bytes=lifecycle["final_metrics"]["store_file_bytes"],
                                    store_allocated_bytes=lifecycle["final_metrics"].get("store_allocated_bytes"),
                                    sampled_disk_peak_bytes=lifecycle["disk_peak"]["sampled_peak_file_bytes"],
                                    population_seconds=lifecycle["population_seconds_including_preprocessing"],
                                    population_physical_write_bytes=io_delta(lifecycle.get("population_cgroup_before"),
                                                                            lifecycle.get("population_cgroup_after_quiescence"), "wbytes"),
                                    out_of_core_resource_evidence_verified=workload["out_of_core_resource_evidence_verified"],
                                    raw_block_sha256=block["block_record_sha256"]))
    return records


def summarize(loaded):
    raw = observations(loaded)
    cells = []
    for condition_id in sorted({item["condition"] for item in raw}):
        for pattern in ("sequential", "shuffled"):
            records = [item for item in raw if item["condition"] == condition_id and item["access_pattern"] == pattern]
            pairs = {}
            for item in records:
                pairs.setdefault(item["block_id"], {})[item["backend"]] = item
            ratios = [pair["aether"]["throughput"] / pair["mmap"]["throughput"] for _, pair in sorted(pairs.items())]
            sessions = {}
            for block_id, pair in sorted(pairs.items()):
                sessions.setdefault(pair["aether"]["session_id"], []).append(pair["aether"]["throughput"] / pair["mmap"]["throughput"])
            cells.append(dict(condition=condition_id, requested_ratio=records[0]["requested_ratio"], access_pattern=pattern,
                              paired_statistics=paired_statistics(ratios),
                              out_of_core_qualified_pairs=sum(all(item["out_of_core_resource_evidence_verified"] for item in pair.values()) for pair in pairs.values()),
                              session_statistics={name: paired_statistics(values) for name, values in sessions.items()},
                              backends={backend: dict(throughput=available_mean([item["throughput"] for item in records if item["backend"] == backend]),
                                                       latency={key: available_mean([item["latency"][key] for item in records if item["backend"] == backend]) for key in ("p50", "p95", "p99")},
                                                       resources={key: dict(available_mean([item["resources"][key]["mean"] for item in records if item["backend"] == backend]),
                                                                           available_passes=sum(item["resources"][key]["available"] for item in records if item["backend"] == backend),
                                                                           total_passes=sum(item["resources"][key]["total"] for item in records if item["backend"] == backend))
                                                                  for key in records[0]["resources"]},
                                                       store_allocated_bytes=available_mean([item["store_allocated_bytes"] for item in records if item["backend"] == backend]),
                                                       store_file_bytes=available_mean([item["store_file_bytes"] for item in records if item["backend"] == backend])) for backend in ("aether", "mmap")}))
    summary = dict(schema="aether-h4-analysis-v1", phase=loaded["phase"], identity=loaded["identity"],
                input_archives=loaded["archives"], excluded_blocks=loaded["excluded"], observations=raw, conditions=cells,
                primary_h4_eligible=False, confirmatory_test=None,
                limitations=["Exploratory intervals are descriptive, not confirmatory evidence of superiority.",
                             "Three passes belong to one paired block and are not independent replicates.",
                             "Logical payload/memory ratio is not a measurement of usable page-cache capacity.",
                             "Missing resource measurements remain unavailable; no zero imputation.",
                             "Memory peak is the cgroup lifetime high-water mark, not necessarily a read-window peak.",
                             "Summed process RSS endpoints are sampled and may double-count shared physical pages; not exact peak memory.",
                             "Sampled disk peak is a lower bound; short-lived peaks may be missed.",
                             "Population includes deterministic preprocessing and is secondary to retrieval.",
                             "Float32 CHW PCam materialization is one ML representation, not the most compact image encoding."])
    if loaded["phase"] == "confirmatory":
        from .confirmatory import confirmatory_test
        design = loaded["design"]
        selected = [block for block in loaded["blocks"] if block["condition"] == design["condition"]]
        ids = {block["block_id"] for block in selected}
        qualified = all(all(record["patterns"]["shuffled"]["out_of_core_resource_evidence_verified"]
                            for record in block["results"].values()) for block in selected)
        if ids != set(design["block_ids"]) or loaded["excluded"] or not qualified:
            summary["confirmatory_test"] = dict(executed=False, reason="complete predeclared qualified paired-block set not reached")
        else:
            ratios = [block["results"]["aether"]["patterns"]["shuffled"]["block_mean_artifacts_per_second"] /
                      block["results"]["mmap"]["patterns"]["shuffled"]["block_mean_artifacts_per_second"]
                      for block in sorted(selected, key=lambda block: block["block_id"])]
            summary["confirmatory_test"] = confirmatory_test(ratios, design)
            summary["primary_h4_eligible"] = summary["confirmatory_test"]["executed"]
        summary["design_sha256"] = design["sha256"]
        summary["limitations"][0] = "Only the frozen shuffled 4x endpoint is confirmatory; other curves are descriptive."
    return summary


def run(campaigns, prepared, protocol, output):
    output = Path(output).resolve()
    for campaign in map(lambda path: Path(path).resolve(), campaigns):
        if output == campaign or output.is_relative_to(campaign):
            raise ValueError("analysis output must not modify raw campaign archives")
    loaded = load_campaigns(campaigns, prepared, protocol)
    summary = summarize(loaded)
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("analysis output must be empty")
    write_json(output / "analysis.json", summary)
    fields = ("condition", "block_id", "session_id", "backend", "access_pattern", "requested_ratio",
              "samples", "payload_bytes", "throughput", "population_seconds", "store_file_bytes", "store_allocated_bytes",
              "p50_seconds", "p95_seconds", "p99_seconds", "physical_read_bytes", "physical_write_bytes",
              "file_refaults", "major_page_faults", "memory_peak_bytes")
    with (output / "paired-observations.csv").open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields, extrasaction="ignore")
        writer.writeheader()
        for item in summary["observations"]:
            flat = dict(item, **{name + "_seconds": value for name, value in item["latency"].items()},
                        **{name: value["mean"] for name, value in item["resources"].items()})
            writer.writerow(flat)
    (output / "limitations.md").write_text("# H4 Analysis Limitations\n\n" + "\n".join("- " + item for item in summary["limitations"]) + "\n", encoding="utf-8")
    archive_checksums(output)
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--campaign", type=Path, action="append", required=True)
    parser.add_argument("--prepared", type=Path, required=True)
    parser.add_argument("--protocol", type=Path, default=Path(__file__).with_name("protocol.json"))
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    run(args.campaign, args.prepared, args.protocol, args.output)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
