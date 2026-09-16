"""Read-only V2 diagnostics. Reports deliberately do not use the paper block schema."""
import hashlib
import json
import math
import os
import random
import struct
import subprocess
import time
from pathlib import Path
from types import SimpleNamespace

from cache_workspace import cache_workspace, payload_floor
from experiment_output import exclusive_output
from paper_common import ROOT, environment, java_classpath, java_daemon, sha256, write_json

ROLE = "steady-state-hit-path-diagnostic"
ACTIVITY = ("flushesCompleted", "backgroundCompactionsStarted", "completed", "failed")


class InvalidHitRun(RuntimeError):
    pass


def require_hits(values, expected):
    if len(values) != expected or any(value is None for value in values):
        raise InvalidHitRun("hit-only lookup returned missing entries; publication is forbidden")


def require_idle(info):
    if info.get("state") != "IDLE" or info.get("debtBytes") != 0 or info.get("failed") != 0:
        raise InvalidHitRun(f"storage must be idle with zero debt and failures: {info}")
    if any(type(info.get(key)) is not int for key in ACTIVITY):
        raise InvalidHitRun("daemon lacks required monotonic storage activity counters; rebuild Java")


def require_no_activity(before, after, *, misses=0, publishes=0, traces=()):
    require_idle(before)
    require_idle(after)
    if misses or publishes:
        raise InvalidHitRun("hit-only measurement contains misses or publishes")
    if any(after[key] != before[key] for key in ACTIVITY):
        raise InvalidHitRun("storage activity occurred during the hit-only measurement")
    for trace in traces:
        server = next((event["server"] for event in trace.get("events", []) if event["stage"] == "server_trace"), {})
        if server.get("flushes") or server.get("compactions") or server.get("compactionScheduled"):
            raise InvalidHitRun("request trace contains flush or compaction activity")
        if trace.get("outcome") != "complete":
            raise InvalidHitRun("a traced request failed")


def distribution(values):
    ordered = sorted(values)
    if not ordered:
        return None
    def percentile(fraction):
        rank = (len(ordered) - 1) * fraction
        lo, hi = math.floor(rank), math.ceil(rank)
        return ordered[lo] + (ordered[hi] - ordered[lo]) * (rank - lo)
    return {"mean": sum(ordered) / len(ordered), "median": percentile(.5),
            "p95": percentile(.95), "p99": percentile(.99), "count": len(ordered)}


def summarize(batches, elapsed_ns):
    samples = sum(batch["samples"] for batch in batches)
    byte_count = sum(batch["bytesReturned"] for batch in batches)
    return {"durationNs": distribution([batch["durationNs"] for batch in batches]),
            "msPerSample": distribution([batch["durationNs"] / batch["samples"] / 1e6 for batch in batches]),
            "bytesPerRequest": distribution([batch["bytesReturned"] for batch in batches]),
            "samples": samples, "bytesReturned": byte_count, "elapsedNs": elapsed_ns,
            "samplesPerSecond": samples * 1e9 / elapsed_ns if elapsed_ns else None,
            "bytesPerSecond": byte_count * 1e9 / elapsed_ns if elapsed_ns else None}


def numbers(value, minimum):
    parsed = [int(item) for item in value.split(",")]
    if not parsed or len(parsed) != len(set(parsed)) or any(item < minimum for item in parsed):
        raise ValueError("sweep values must be unique and within their supported range")
    return parsed


def build_plan(args):
    if args.confirmatory or args.resume:
        raise ValueError("hit diagnostics require a fresh output and cannot be confirmatory")
    if args.workers != "0" or args.gpu_counts != "1":
        raise ValueError("hit diagnostics use one client and one optional producer, with CPU input tensors")
    if args.reuse_ratios or args.sizes or args.measured_steps or args.workflow_experiments != 1:
        raise ValueError("hit diagnostics use the complete V2 manifest, without lifecycle/reuse sweeps")
    if min(args.repeats, args.epochs, args.batch_size) < 1 or args.hit_warmup_epochs < 0:
        raise ValueError("positive repeats, epochs and batch size required; warmup must be non-negative")
    if args.batch_size > 64:
        raise ValueError("hit diagnostic training batch size cannot exceed the existing 64-key RPC chunk")
    if set(args.backends.split(",")) not in ({"aether", "mmap"}, {"raw", "aether", "mmap", "ram"}):
        raise ValueError("hit diagnostics compare aether,mmap only (the default backend list is also accepted)")
    sizes = numbers(args.request_sizes, 1)
    if max(sizes) > 64:
        raise ValueError("request sizes must be within 1..64")
    depths = numbers(args.prefetch_depths, 0)
    if max(depths) > 64:
        raise ValueError("prefetch depth is bounded to 64; use the default 0,1,2,4,8 sweep")
    # The training-sized bytes baseline is always present for the three-layer comparison.
    sizes = sorted(set(sizes + [args.batch_size]))
    passes = numbers(args.preprocess_passes, 1)
    if len(passes) != 1:
        raise ValueError("freeze preprocessing; choose one --preprocess-passes value")
    specs = json.loads(args.config.read_text(encoding="utf-8"))
    datasets = args.datasets.split(",")
    if len(set(datasets)) != len(datasets) or any(name not in {"oct5k", "coco", "imagenet"} for name in datasets):
        raise ValueError("choose unique real manifest datasets: oct5k,coco,imagenet")
    return {"schema": "aether-hit-path-protocol-v1", "measurementRole": ROLE,
            "allArtifactsPrepopulated": True, "publishesAllowed": False, "confirmatory": False,
            "datasets": {name: specs[name] for name in datasets}, "repeats": args.repeats,
            "epochs": args.epochs, "warmupEpochs": args.hit_warmup_epochs,
            "trainingBatchSize": args.batch_size, "requestSizes": sizes, "prefetchDepths": depths,
            "preprocessPasses": passes[0], "seedBase": args.seed_base, "serverTrace": args.server_trace,
            "maxReferenceBytes": args.max_reference_bytes,
            "order": "one seeded permutation per repetition, identical across layers and epochs; seeded trial order",
            "pageCache": "uncontrolled, warmed by complete verification and untimed read epochs",
            "layerScope": "Java database.get loop; Python RPC bytes; CPU decode/stack/tensor input only",
            "prefetchScope": "full input only, fixed training batch, no GPU compute or simulated delay",
            "timingScope": "sum of active epoch walls; excludes population, verification, warmup, process startup and trace export",
            "comparisonScope": "cross-layer differences are descriptive estimates, not causal overhead isolation",
            "sourceClean": False}


def workload_args(plan, dataset, spec, manifest):
    import benchmark_gpu_segmentation as training
    return training.parse_args([
        "--dataset-kind", dataset, "--dataset-manifest", str(manifest),
        "--dataset-split", spec.get("split", "train"), "--samples", str(spec["samplesV2"]),
        "--resize", str(spec.get("imageSize", 256)), "--num-classes", str(spec.get("numClasses", 80 if dataset == "coco" else 1000)),
        "--batch-size", str(plan["trainingBatchSize"]), "--epochs", str(plan["epochs"]),
        "--preprocess-passes", str(plan["preprocessPasses"]), "--backends", "raw,aether,mmap",
        "--max-reference-bytes", str(plan["maxReferenceBytes"]),
        "--prefetch-batches", str(max(plan["prefetchDepths"])), "--augmentation-mode", "none"])


def write_workload(path, keys):
    with path.open("wb") as stream:
        stream.write(struct.pack(">I", len(keys)))
        for key in keys:
            for text in (key.namespace, key.sample_id):
                data = text.encode("utf-8")
                stream.write(struct.pack(">I", len(data)))
                stream.write(data)
            stream.write(key.transform.digest)


def prepare(plan, dataset, spec, directory, stores, seed):
    import numpy as np
    import benchmark_gpu_segmentation as training
    from aether_training_cache.java_store import JavaArtifactStore
    from aether_training_cache.persistent_mmap import PersistentMmapStore
    from run_matrix import subset_manifest

    manifest = directory / "v2.csv"
    subset_manifest(spec["manifestV2"], manifest, spec["samplesV2"], spec.get("split", "train"))
    args = workload_args(plan, dataset, spec, manifest)
    sources = training.load_sources(args)
    context = SimpleNamespace(args=args, sources=sources)
    keys = [training.BackendContext.cache_key(context, index) for index in range(len(sources))]
    if len(keys) != spec["samplesV2"] or len(set(keys)) != len(keys):
        raise InvalidHitRun("V2 keys must be unique and match the complete expected cardinality")
    entries = []
    started = time.perf_counter_ns()
    with java_daemon(stores / "java") as daemon:
        cache = JavaArtifactStore(port=daemon["port"], namespace="hit-path-v2")
        mmap = PersistentMmapStore(stores / "mmap", durable=True)
        try:
            pending = []
            for source, key in zip(sources, keys):
                sample = training.preprocess_sample(source, args, np)
                # Identical production encoding in both stores, with no alternative preprocessing.
                data = training.pack_payload(training.artifact_to_tensor_sample(sample, np))
                pending.append((key, data))
                entries.append({"key": key, "sampleId": source["sample_id"], "bytes": len(data),
                                "sha256": hashlib.sha256(data).hexdigest()})
                if len(pending) == 16 or len(entries) == len(keys):
                    cache.commit_bytes_many([{"cache_key": k, "data": value} for k, value in pending])
                    mmap.put_many(pending)
                    pending.clear()
            drain = cache.drain_background_compaction()
            require_idle(drain["backgroundCompaction"])
            if (len(cache.cached_artifact_ids(keys)) != len(keys) or set(mmap.index) != set(keys)
                    or cache.engine_info().get("cacheEntries") != len(keys)):
                raise InvalidHitRun("prepopulation cardinality mismatch")
            # Full byte equality and hashes checked outside all measured intervals.
            for offset in range(0, len(keys), 64):
                selected = keys[offset:offset + 64]
                found = cache.load_cached_views_many(selected)
                require_hits([found.get(key) for key in selected], len(selected))
                for key, entry in zip(selected, entries[offset:offset + 64]):
                    record = mmap.get(key)
                    require_hits([record], 1)
                    if (hashlib.sha256(found[key]).hexdigest() != entry["sha256"]
                            or found[key] != memoryview(record)[4:]):
                        raise InvalidHitRun("Aether and mmap prepared artifacts differ")
            order = list(range(len(keys)))
            random.Random(seed).shuffle(order)
            ordered = [entries[index] for index in order]
            write_workload(directory / "keys.bin", [cache._key(entry["key"]) for entry in ordered])
        finally:
            cache.close()
            mmap.close()
    write_json(directory / "population.json", {"measurementRole": "untimed-hit-path-preparation",
        "wallNs": time.perf_counter_ns() - started, "expectedEntries": len(keys), "cacheEntries": len(keys),
        "missingEntries": 0, "allArtifactHashesVerified": True, "drain": drain,
        "manifestSha256": sha256(manifest), "orderedArtifacts": ordered})
    return ordered


def java_trial(stores, directory, job, plan, *, prepare_only=False):
    target = directory / ("seal.json" if prepare_only else job["id"] + ".json")
    command = ["java", "--enable-preview", "-cp", java_classpath(),
               "io.aetherdb.training.cache.HitPathBenchmark", str(stores / "java"),
               str(directory / "keys.bin"), str(target), str(job["requestSize"]),
               "0" if prepare_only else str(plan["epochs"]), str(plan["warmupEpochs"])]
    with target.with_suffix(".log").open("w", encoding="utf-8") as log:
        subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True,
                       creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
    report = json.loads(target.read_text(encoding="utf-8"))
    if not prepare_only:
        if report.get("status") != "PASSED":
            raise InvalidHitRun("Java-only measurement failed")
        require_no_activity(report["before"], report["after"])
        elapsed = report.get("elapsedNs", sum(batch["durationNs"] for batch in report["batches"]))
        report.update(job=job, measurementRole=ROLE, summary=summarize(report["batches"], elapsed))
        write_json(target, report)
    return report


def python_trial(stores, directory, job, plan, entries):
    import numpy as np
    import benchmark_gpu_segmentation as training
    from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint
    from aether_training_cache.persistent_mmap import PersistentMmapStore
    from aether_training_cache.prefetch import BoundedPrefetchIterator, PreparedBatch, merge_prefetch_metrics

    full = job["layer"] == "full-input"
    if full:
        import torch
    transform = TransformationFingerprint.from_descriptor("aether-derived-artifact-v1")
    keys = [CacheKey("hit-path-v2", entry["key"], transform) for entry in entries]
    size = job["requestSize"]
    schedule = [tuple(range(start, min(start + size, len(keys)))) for start in range(0, len(keys), size)]
    records, epoch_metrics, traces, completed = [], [], [], []
    elapsed = 0
    with java_daemon(stores / "java") as daemon:
        # Exactly one persistent workload connection, used by only one producer at a time.
        client = AetherTrainingCache(port=daemon["port"], trace_sink=traces.append,
                                     server_trace=plan["serverTrace"])
        mmap = PersistentMmapStore(stores / "mmap", durable=True)
        try:
            drain = client.wait_for_background_compaction()
            require_idle(drain["backgroundCompaction"])
            # No fallback preprocessing or publishing API exists on this preparation path.
            def prepare_batch(indices):
                started = time.perf_counter_ns()
                trace_offset = len(traces)
                if job["backend"] == "aether":
                    packed = client.get_many_values([keys[index] for index in indices])
                    try:
                        values = [packed.value(index) for index in range(len(indices))]
                    finally:
                        packed.close()
                else:
                    values = []
                    for index in indices:
                        record = mmap.get(entries[index]["key"])
                        values.append(None if record is None else record[4:])
                lookup_end = time.perf_counter_ns()
                require_hits(values, len(indices))
                if any(len(value) != entries[index]["bytes"] for index, value in zip(indices, values)):
                    raise InvalidHitRun("artifact size changed after complete preflight verification")
                decode_ns = tensor_ns = 0
                tensors = None
                if full:
                    decode_start = time.perf_counter_ns()
                    decoded = [training.unpack_payload(value, np) for value in values]
                    if [value["sample_id"] for value in decoded] != [entries[index]["sampleId"] for index in indices]:
                        raise InvalidHitRun("input order/sample identity changed")
                    arrays = training.stack_values(decoded, np)
                    decode_ns = time.perf_counter_ns() - decode_start
                    tensor_start = time.perf_counter_ns()
                    tensors, _ = training.normalize_cpu_batch(torch, arrays)
                    tensor_ns = time.perf_counter_ns() - tensor_start
                duration = time.perf_counter_ns() - started
                record = {"samples": len(indices), "bytesReturned": sum(map(len, values)),
                          "durationNs": duration, "lookupNs": lookup_end - started,
                          "artifactDecodeNs": decode_ns, "tensorMaterializationNs": tensor_ns,
                          "traceIds": [trace["traceId"] for trace in traces[trace_offset:] if trace["operation"] == 5]}
                return PreparedBatch((record, tensors), lookup_ns=record["lookupNs"],
                                     decode_ns=decode_ns + tensor_ns)

            def export_completed():
                if not plan["serverTrace"]:
                    return []
                exported = client.drain_completed_server_traces()
                if exported.get("dropped") != 0:
                    raise InvalidHitRun("completed server trace buffer overflowed; reduce epoch request count")
                return exported["records"]

            # Bound trace retention/export by epoch, including untimed warmup.
            for _ in range(plan["warmupEpochs"]):
                for indices in schedule:
                    prepare_batch(indices)
                export_completed()
                traces.clear()
            initial_info = client.engine_info()
            if initial_info.get("cacheEntries") != len(keys) or set(mmap.index) != {entry["key"] for entry in entries}:
                raise InvalidHitRun("store cardinality changed after prepopulation")
            before = initial_info["backgroundCompaction"]
            require_idle(before)
            export_completed()
            traces.clear()
            protocol_before = client.protocol_metrics()
            mmap_before = dict(mmap.metrics)
            for epoch in range(plan["epochs"]):
                epoch_start = time.perf_counter_ns()
                iterator = BoundedPrefetchIterator(schedule, prepare_batch, job["prefetchDepth"],
                    cancel_callback=client.cancel_pending_requests if job["backend"] == "aether" else None)
                try:
                    for expected, item in enumerate(iterator):
                        if item.batchIndex != expected or item.task != schedule[expected]:
                            raise InvalidHitRun("prefetch changed batch order")
                        record, tensors = item.value
                        record.update(epoch=epoch, batchIndex=expected, consumerWaitNs=item.consumerWaitNs)
                        records.append(record)
                        del tensors
                finally:
                    iterator.close()
                elapsed += time.perf_counter_ns() - epoch_start
                epoch_metrics.append(iterator.metrics)
                # No next epoch worker exists while these control requests run.
                completed.extend(export_completed())
            after = client.engine_info()["backgroundCompaction"]
            protocol_after = client.protocol_metrics()
            publish_count = sum(protocol_after["operationCounts"].get(op, 0) - protocol_before["operationCounts"].get(op, 0)
                                for op in (2, 6))
            publishes = publish_count + mmap.metrics["entriesAppended"] - mmap_before["entriesAppended"]
            get_traces = [trace for trace in traces if trace["operation"] == 5]
            if (len(records) != len(schedule) * plan["epochs"]
                    or sum(record["samples"] for record in records) != len(keys) * plan["epochs"]):
                raise InvalidHitRun("input iteration did not consume the complete epoch schedule")
            require_no_activity(before, after, publishes=publishes, traces=get_traces)
            if client.trace_errors:
                raise InvalidHitRun("client trace sink lost evidence")
            if protocol_after["connectionsOpened"] != protocol_before["connectionsOpened"]:
                raise InvalidHitRun("connection reopened during measurement")
            if job["backend"] == "aether" and len(get_traces) != len(records):
                raise InvalidHitRun("each batch must have exactly one correlated getMany request")
            if plan["serverTrace"]:
                by_id = {item["traceId"]: item for item in completed}
                if len(by_id) != len(completed) or set(by_id) != {trace["traceId"] for trace in get_traces}:
                    raise InvalidHitRun("completed server trace IDs differ from client request IDs")
                for trace in get_traces:
                    trace["serverCompleted"] = by_id[trace["traceId"]]
            report = {"schema": "aether-hit-path-trial-v1", "measurementRole": ROLE, "status": "PASSED",
                      "job": job, "integrityPolicy": initial_info.get("integrityPolicy"),
                      "before": before, "after": after, "batches": records,
                      "hits": sum(record["samples"] for record in records), "misses": 0, "publishes": publishes,
                      "flushes": after["flushesCompleted"] - before["flushesCompleted"],
                      "backgroundCompactionsStarted": after["backgroundCompactionsStarted"] - before["backgroundCompactionsStarted"],
                      "protocolBefore": protocol_before, "protocolAfter": protocol_after,
                      "summary": summarize(records, elapsed),
                      "prefetch": merge_prefetch_metrics(epoch_metrics, job["prefetchDepth"]),
                      "pythonStagesNs": {name: distribution([trace[name] for trace in get_traces]) for name in (
                          "requestEncodeNs", "socketSendNs", "socketWaitReceiveNs", "responseDecodeNs")},
                      "artifactDecodeNs": distribution([record["artifactDecodeNs"] for record in records]),
                      "tensorMaterializationNs": distribution([record["tensorMaterializationNs"] for record in records]),
                      "requestTraces": get_traces}
            stage_names = {name for item in completed for name in item["stagesNs"]}
            report["serverStagesNs"] = {name: distribution([item["stagesNs"].get(name, 0) for item in completed])
                                        for name in sorted(stage_names)}
            read_names = {name for item in completed for name in item.get("readDiagnostics", {}).get("stagesNs", {})}
            report["storageStagesNs"] = {name: distribution([item.get("readDiagnostics", {}).get("stagesNs", {}).get(name, 0)
                                                              for item in completed]) for name in sorted(read_names)}
            report["stageScope"] = "nested Java/storage stages are inclusive; do not sum them as disjoint wall time"
        except BaseException as error:
            write_json(directory / (job["id"] + ".json"), {"schema": "aether-hit-path-trial-v1",
                "measurementRole": ROLE, "status": "INVALID", "job": job, "error": str(error),
                "batches": records, "requestTraces": traces, "completedServerTraces": completed})
            raise
        finally:
            client.close()
            mmap.close()
    write_json(directory / (job["id"] + ".json"), report)
    return report


def jobs(plan, seed):
    result = []
    for size in plan["requestSizes"]:
        for backend, layer in (("aether", "java-database"), ("aether", "rpc-bytes"), ("mmap", "local-bytes")):
            result.append({"backend": backend, "layer": layer, "requestSize": size, "prefetchDepth": 0})
    for depth in plan["prefetchDepths"]:
        for backend in ("aether", "mmap"):
            result.append({"backend": backend, "layer": "full-input", "requestSize": plan["trainingBatchSize"],
                           "prefetchDepth": depth})
    for job in result:
        job["id"] = f"{job['backend']}-{job['layer']}-b{job['requestSize']}-d{job['prefetchDepth']}"
    random.Random(seed ^ 0xAE7).shuffle(result)
    return result


def comparisons(reports, training_size):
    by_key = {(report["job"]["backend"], report["job"]["layer"], report["job"]["requestSize"], report["job"]["prefetchDepth"]): report
              for report in reports}
    def latency(backend, layer):
        found = by_key.get((backend, layer, training_size, 0))
        return found["summary"]["durationNs"]["mean"] if found else None
    java, rpc, full = latency("aether", "java-database"), latency("aether", "rpc-bytes"), latency("aether", "full-input")
    java_report = by_key.get(("aether", "java-database", training_size, 0), {})
    comparable = java_report.get("segmentBackedEntries", 0) == 0
    return {"trainingBatchSize": training_size, "javaDatabaseInlinePayloadComparison": comparable,
            "rpcMinusJavaMeanNs": rpc - java if comparable and rpc is not None and java is not None else None,
            "fullMinusRpcMeanNs": full - rpc if full is not None and rpc is not None else None,
            "scope": "descriptive differences across separately timed trials; include cache validation/packing and cache/JIT state differences; negative estimates are retained, not clamped"}


def run(args):
    plan = build_plan(args)
    root = args.output.resolve()
    if args.plan_only:
        root.mkdir(parents=True, exist_ok=True)
        write_json(root / "hit-path-plan.json", plan)
        return
    with exclusive_output(root):
        if (root / "protocol.json").exists() or any(root.glob("*/repeat-*")):
            raise FileExistsError("choose a fresh output directory for this diagnostic campaign")
        provenance = environment()
        clean = provenance["commands"]["gitStatus"]
        plan["sourceClean"] = clean.get("returncode") == 0 and not clean.get("stdout")
        plan["sourceSha256"] = provenance["sourceSha256"]
        plan["manifestSha256"] = {spec["manifestV2"]: sha256(spec["manifestV2"]) for spec in plan["datasets"].values()}
        write_json(root / "protocol.json", plan)
        write_json(root / "environment.json", provenance)
        campaign = []
        try:
            for dataset, spec in plan["datasets"].items():
                for repeat in range(plan["repeats"]):
                    directory = root / dataset / f"repeat-{repeat:04d}"
                    directory.mkdir(parents=True, exist_ok=False)
                    seed = plan["seedBase"] + repeat
                    order = jobs(plan, seed)
                    write_json(directory / "trial-order.json", order)
                    floor = payload_floor(dataset, spec["samplesV2"], spec.get("imageSize", 256), spec.get("numClasses", 1000))
                    with cache_workspace(directory, scratch_root=args.scratch_root, retain=args.retain_stores, payload_bytes=floor) as stores:
                        print(f"Preparing {dataset} hit diagnostic {repeat + 1}/{plan['repeats']}", flush=True)
                        entries = prepare(plan, dataset, spec, directory, stores, seed)
                        # A normal graceful close seals the final memtable before any layer is timed.
                        java_trial(stores, directory, {"requestSize": plan["trainingBatchSize"]}, plan, prepare_only=True)
                        reports = []
                        for job in order:
                            print(f"Hit diagnostic {dataset} repeat {repeat + 1}: {job['id']}", flush=True)
                            report = (java_trial(stores, directory, job, plan) if job["layer"] == "java-database"
                                      else python_trial(stores, directory, job, plan, entries))
                            reports.append(report)
                        summary = {"dataset": dataset, "repeat": repeat, "status": "PASSED", "measurementRole": ROLE,
                                   "comparisons": comparisons(reports, plan["trainingBatchSize"]),
                                   "trials": [{"job": report["job"], "summary": report["summary"],
                                               "file": report["job"]["id"] + ".json"} for report in reports]}
                        write_json(directory / "hit-summary.json", summary)
                        campaign.append(summary)
            write_json(root / "hit-path-summary.json", {"status": "PASSED", "measurementRole": ROLE,
                       "confirmatory": False, "repetitions": campaign})
        except BaseException as error:
            write_json(root / "hit-path-summary.json", {"status": "INVALID", "measurementRole": ROLE,
                       "confirmatory": False, "error": str(error), "completedRepetitions": campaign})
            raise
