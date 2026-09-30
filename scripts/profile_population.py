"""Exploratory V0-only diagnosis. No model, training, evolution, or storage tuning."""
import argparse
from collections import Counter
import contextlib
import json
import math
from pathlib import Path
import random
import shutil
import subprocess
import sys
import time

from paper_common import ROOT, sha256, write_json
from system_campaign import campaign, save_result
from longitudinal_manifests import verify
from longitudinal_state import check_capacity
from longitudinal_worker import DiskSampler, strict_drain
import monai_comparison as base

SIZES = (1, 4, 8, 16, 32, 64)


def layout_cases():
    return [dict(name=f"aether-bulk-{size}mib", backend="aether_bulk", lookupBatch=16,
                 putBatch=16, trace=False, targetSstableBytes=size * 1024 ** 2) for size in (32, 64, 128)]


def cases(include_bulk=False):
    sweep = [dict(name=f"aether-put-{size:02d}", backend="aether", lookupBatch=64,
                  putBatch=size, trace=True) for size in SIZES]
    controls = [dict(name="aether-pilot16-" + name, backend="aether", lookupBatch=16,
                     putBatch=16, trace=traced) for name, traced in (("traced", True), ("untraced", False))]
    result = sweep + controls + [dict(name=name, backend=name, lookupBatch=16, putBatch=None, trace=False)
                                for name in base.BACKENDS[1:]]
    if include_bulk:
        result.append(dict(name="aether-bulk16", backend="aether_bulk", lookupBatch=16, putBatch=16, trace=False))
    return result


def expected_puts(count, lookup_batch, put_batch):
    return sum(math.ceil(len(batch) / put_batch) for batch in base.batches(count, lookup_batch))


class TimedTransform(base.CanonicalTransform):
    def __init__(self, args):
        super().__init__(args)
        self.elapsed_ns = 0

    def __call__(self, item):
        start = time.perf_counter_ns()
        try:
            return super().__call__(item)
        finally:
            self.elapsed_ns += time.perf_counter_ns() - start


class TimedCodec:
    def __init__(self, codec):
        self.codec = codec
        self.encode_ns = self.decode_ns = self.bytes = 0

    def encode(self, value):
        start = time.perf_counter_ns()
        try:
            payload = self.codec.encode(value)
            self.bytes += len(payload)
            return payload
        finally:
            self.encode_ns += time.perf_counter_ns() - start

    def decode(self, value):
        start = time.perf_counter_ns()
        try:
            return self.codec.decode(value)
        finally:
            self.decode_ns += time.perf_counter_ns() - start


class PublicationClient(base.AetherTrainingCache):
    """Use the production encoder/exchange unchanged; split only publication batches."""
    def __init__(self, *, put_batch, traced, **kwargs):
        self.put_batch, self.records, self.publications = put_batch, [], []
        self.active = None
        super().__init__(trace_sink=self.records.append if traced else None, server_trace=traced, **kwargs)

    def put_many(self, values):
        values = list(values)
        for start in range(0, len(values), self.put_batch):
            chunk = values[start:start + self.put_batch]
            self.active = dict(artifacts=len(chunk), payloadBytes=sum(len(v) for _, v in chunk),
                               startedNs=time.perf_counter_ns())
            try:
                super().put_many(chunk)
                self.active["totalNs"] = time.perf_counter_ns() - self.active["startedNs"]
                self.publications.append(self.active)
            finally:
                self.active = None

    def _round_trip(self, body, **kwargs):
        if self.active is not None and body[1] == 6:
            self.active["bodyConstructionNs"] = time.perf_counter_ns() - self.active["startedNs"]
            self.active["bodyBytes"] = len(body)
        return super()._round_trip(body, **kwargs)


def summarize_trace(records, publications, completed):
    stages, writes, counters, flush_stages, flush_causes = (Counter() for _ in range(5))
    client_phases = Counter()
    for record in records:
        if record["outcome"] != "complete" or any(e["stage"] == "attempt_failed" for e in record["events"]):
            raise RuntimeError("diagnostic requires successful requests without hidden retries")
        marks = {e["stage"]: e["monotonicNs"] for e in record["events"]}
        if record["operation"] != 6:
            continue
        for name, begin, end in (("frameConstruction", "connection_ready", "frame_ready"),
                                 ("socketSend", "frame_ready", "send_complete"),
                                 ("responseWaitAndRead", "send_complete", "payload_received")):
            client_phases[name] += marks[end] - marks[begin]
        server = next(e["server"] for e in record["events"] if e["stage"] == "server_trace")
        stages.update(server["stagesNs"])
        writes.update(server["writeDiagnostics"]["stagesNs"])
        counters.update(server["writeDiagnostics"]["counters"])
        for flush in server["flushes"]:
            if not flush["completed"]:
                raise RuntimeError("incomplete foreground flush")
            flush_causes[flush["cause"]] += 1
            flush_stages.update(flush["stagesNs"])
    if completed["dropped"] or {r["traceId"] for r in records} != {r["traceId"] for r in completed["records"]}:
        raise RuntimeError("server/client trace reconciliation failed")
    publication_ids = {r["traceId"] for r in records if r["operation"] == 6}
    complete_stages = Counter()
    for record in completed["records"]:
        if record["traceId"] in publication_ids:
            complete_stages.update(record["stagesNs"])
    return {"publicationRequests": len(publications), "publishedArtifacts": sum(p["artifacts"] for p in publications),
            "bodyConstructionNs": sum(p["bodyConstructionNs"] for p in publications),
            "publicationWallNs": sum(p["totalNs"] for p in publications),
            "clientPublicationPhasesNs": dict(client_phases), "serverPublicationStagesNs": dict(stages),
            "serverWriteStagesNs": dict(writes), "serverWriteCounters": dict(counters),
            "flushCauses": dict(flush_causes), "flushStagesNs": dict(flush_stages),
            "completedServerPublicationStagesNs": dict(complete_stages),
            "timingScope": "nested/inclusive stages; do not sum client and server time; socket wait includes storage work",
            "transferLimit": "socketSend is local sendall duration, not isolated network transfer or bandwidth",
            "forceAttribution": "one shared group force attributed to first request; this workload has one serial writer"}


def workload_args(manifest, count, size, trusted=False):
    args = ["--dataset-kind", "oct5k", "--dataset-manifest", str(manifest), "--samples", str(count),
            "--resize", str(size), "--preprocess-passes", "4", "--batch-size", "16", "--prefetch-batches", "0"]
    return base.workload.parse_args(args + (["--trust-manifest-hashes"] if trusted else []))


def run_case(request):
    if request["case"]["backend"] == "aether_bulk":
        from bulk_population import run_bulk_case
        return run_bulk_case(request)
    case, store = request["case"], Path(request["store"])
    if store.exists():
        raise FileExistsError("population requires a fresh empty cache")
    args = workload_args(request["manifest"], request["samples"], request["imageSize"], trusted=True)
    sources = base.workload.load_sources(args)
    transform = TimedTransform(args)
    preprocessing = Counter()
    original_preprocess = base.workload.preprocess_sample_with_timing
    def capture_preprocessing(*a, **kw):
        value, counters = original_preprocess(*a, **kw)
        preprocessing.update(counters)
        return value, counters
    base.workload.preprocess_sample_with_timing = capture_preprocessing
    manager = base.java_daemon(store) if case["backend"] == "aether" else contextlib.nullcontext(None)
    ds = client = codec = None
    sampler = DiskSampler(store)
    sampler.start()
    usage_before = base.snapshot()
    startup = time.perf_counter()
    entered = False
    try:
        daemon = manager.__enter__()
        entered = True
        startup_ms = (time.perf_counter() - startup) * 1000
        port = daemon["port"] if daemon else None
        java_before = base.snapshot(daemon["pid"]) if daemon else None
        initial_info = None
        if daemon:
            with base.AetherTrainingCache(port=port) as observer:
                initial_info = observer.engine_info()
            if (initial_info["cacheEntries"] != 0 or initial_info["durability"] != "DURABLE"
                    or not initial_info["backgroundCompaction"]["enabled"]
                    or initial_info["integrityPolicy"]["version"] != "immutable-inline-admission-v1"):
                raise RuntimeError("empty-cache engine protocol mismatch")
        started = time.perf_counter()
        if daemon:
            client = PublicationClient(port=port, put_batch=case["putBatch"], traced=case["trace"])
            ds = base.AetherPersistentDataset(sources, transform, namespace="monai-pilot-v1", client=client,
                identity_fn=lambda item, index: item["source_identity"], transform_identity=transform.identity)
            codec = ds.cache.codec = TimedCodec(ds.cache.codec)
        else:
            ds = base.dataset(case["backend"], sources, transform, store)
            if case["backend"] == "mmap":
                codec = ds.codec = TimedCodec(ds.codec)
        for batch in base.batches(len(sources), case["lookupBatch"]):
            base.fetch(ds, batch)
        population_ms = (time.perf_counter() - started) * 1000
        preprocessing_ns, calls = transform.elapsed_ns, transform.calls
        codec_report = {"encodeNs": codec.encode_ns, "decodeNs": codec.decode_ns,
                        "encodedBytes": codec.bytes} if codec else None
        protocol = client.protocol_metrics() if client else None
        cache_stats = ds.stats() if client else None
        if calls != request["samples"]:
            raise RuntimeError("population preprocessing cardinality mismatch")
        if client and (len(client.publications) != expected_puts(len(sources), case["lookupBatch"], case["putBatch"])
                       or client.operation_counts.get(6) != len(client.publications)
                       or client.operation_counts.get(5) != math.ceil(len(sources) / case["lookupBatch"])):
            raise RuntimeError("unexpected RPC count or automatic retry")
        started = time.perf_counter()
        drained = strict_drain(port)
        drain_ms = (time.perf_counter() - started) * 1000
        # Export/validate outside the population timer; preserve the admitted store until readback passes.
        started = time.perf_counter()
        info, completed, traces = None, None, None
        if client:
            # Same-connection barrier: the server must finish recording the last write before export.
            client._trace_sink, client._server_trace = None, False
            client.engine_info()
            with base.AetherTrainingCache(port=port) as observer:
                info = observer.engine_info()
                completed = observer.drain_completed_server_traces()
            if client.trace_errors:
                raise RuntimeError("client trace sink failed")
            if case["trace"]:
                traces = summarize_trace(client.records, client.publications, completed)
                if traces["serverWriteCounters"].get("operations") != len(sources):
                    raise RuntimeError("server write operation count mismatch")
            unique = info["cacheEntries"]
        elif case["backend"] == "mmap":
            unique = len(ds.store.index)
        elif case["backend"] == "monai_persistent":
            unique = len(list(store.glob("*.pt")))
        else:
            unique = ds._read_env.stat()["entries"]
        actual = base.tensor_digest(value for batch in base.batches(len(sources), 16) for value in base.fetch(ds, batch))
        if actual != request["referenceHash"] or transform.calls != calls or unique != len(sources):
            raise RuntimeError("population readback/hash/artifact count mismatch")
        validation_ms = (time.perf_counter() - started) * 1000
        java_usage = base.delta(java_before, base.snapshot(daemon["pid"])) if daemon else None
        start = time.perf_counter()
        base.close(ds)
        ds = None
        manager.__exit__(None, None, None)
        entered = False
        close_ms = (time.perf_counter() - start) * 1000
        disk = {**base.disk_usage(store), **sampler.stop()}
        storage_files = Counter()
        for path in store.rglob("*"):
            if path.is_file():
                storage_files[path.suffix or "no-extension"] += path.stat().st_size
        return {"case": case, "samples": len(sources), "preprocessCalls": calls, "uniqueArtifacts": unique,
            "trainingSampleRequests": 0, "model": None, "tensorSha256": actual,
            "timingsMs": {"startup": startup_ms, "population": population_ms, "quiescence": drain_ms, "close": close_ms},
            "totalMs": startup_ms + population_ms + drain_ms + close_ms, "validationMs": validation_ms,
            "sourceLoadAndPreprocessMs": preprocessing_ns / 1e6, "codec": codec_report,
            "preprocessingBreakdownMs": dict(preprocessing),
            "protocolMetrics": protocol, "adapterStats": cache_stats, "traceSummary": traces,
            "publicationRequests": client.publications if client else [], "clientTraces": client.records if client else [],
            "completedServerTraces": completed, "initialEngineInfo": initial_info, "engineInfo": info,
            "quiescence": drained, "disk": disk, "storageBytesByExtension": dict(storage_files),
            "processUsage": {"python": base.delta(usage_before, base.snapshot()), "java": java_usage},
            "instrumentationScope": "process/disk counters include readback; detailed timings overlap and are diagnostic only",
            "unavailable": ["native MONAI serialization-only time", "hardware copy bandwidth", "device-level physical write amplification"]}
    finally:
        base.workload.preprocess_sample_with_timing = original_preprocess
        sampler.stop()
        if ds is not None:
            base.close(ds)
        if entered:
            manager.__exit__(None, None, None)


def summarize(reports):
    import statistics
    result = {}
    for case in cases(include_bulk=True) + layout_cases():
        rows = [r for r in reports if r["case"]["name"] == case["name"]]
        if rows:
            result[case["name"]] = {"n": len(rows), "populationMs": [r["timingsMs"]["population"] for r in rows],
                "medianPopulationMs": statistics.median(r["timingsMs"]["population"] for r in rows),
                "medianTotalMs": statistics.median(r["totalMs"] for r in rows),
                "medianPreprocessingMs": statistics.median(r["sourceLoadAndPreprocessMs"] for r in rows)}
            if case["trace"]:
                for group in ("serverPublicationStagesNs", "serverWriteStagesNs", "flushStagesNs", "clientPublicationPhasesNs"):
                    keys = set().union(*(r["traceSummary"][group] for r in rows))
                    result[case["name"]]["median_" + group] = {
                        key: statistics.median(r["traceSummary"][group].get(key, 0) for r in rows) for key in sorted(keys)}
                result[case["name"]]["medianBodyConstructionMs"] = statistics.median(
                    r["traceSummary"]["bodyConstructionNs"] / 1e6 for r in rows)
            if case["backend"] == "aether_bulk":
                keys = set().union(*(r["bulkCommit"]["storage"]["timingsNs"] for r in rows))
                result[case["name"]]["medianStorageStagesNs"] = {
                    key: statistics.median(r["bulkCommit"]["storage"]["timingsNs"].get(key, 0) for r in rows)
                    for key in sorted(keys)}
                result[case["name"]]["medianBodyConstructionMs"] = statistics.median(
                    sum(p["bodyConstructionNs"] for p in r["publicationRequests"]) / 1e6 for r in rows)
            if rows[0].get("layoutRegression"):
                result[case["name"]].update(
                    medianWarmSamplesPerSecond=statistics.median(r["layoutRegression"]["warm"]["samplesPerSecond"] for r in rows),
                    medianIncrementalMs=statistics.median(r["layoutRegression"]["incremental"]["admissionMs"] +
                        r["layoutRegression"]["incremental"]["drainMs"] for r in rows),
                    tableCounts=[r["bulkCommit"]["storage"]["tables"] for r in rows],
                    sampledPeakRssBytes=[r["populationMemory"]["combinedPeakRssBytes"] for r in rows])
    if "aether-bulk-32mib" in result:
        baseline = {r["blockIndex"]: r for r in reports if r["case"]["name"] == "aether-bulk-32mib"}
        for case in layout_cases():
            paired = [r["layoutRegression"]["warm"]["samplesPerSecond"] /
                      baseline[r["blockIndex"]]["layoutRegression"]["warm"]["samplesPerSecond"]
                      for r in reports if r["case"]["name"] == case["name"] and r["blockIndex"] in baseline]
            if paired:
                result[case["name"]]["pairedWarmRatioVs32"] = paired
                result[case["name"]]["medianWarmRatioVs32"] = statistics.median(paired)
    return {"measurementRole": "population-only diagnostic; no confirmatory claim", "cases": result}


def plot(reports, output):
    import csv
    import statistics
    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    with (output / "population.csv").open("w", newline="", encoding="utf-8") as stream:
        writer = csv.writer(stream)
        writer.writerow(["case", "populationMs", "startupMs", "quiescenceMs", "closeMs", "sourceLoadAndPreprocessMs"])
        for report in reports:
            writer.writerow([report["case"]["name"], *(report["timingsMs"][key] for key in
                             ("population", "startup", "quiescence", "close")), report["sourceLoadAndPreprocessMs"]])
    fig, axes = plt.subplots(1, 2, figsize=(13, 5))
    names = [c["name"] for c in cases(include_bulk=True) + layout_cases() if any(r["case"]["name"] == c["name"] for r in reports)]
    axes[0].barh(names, [statistics.median(r["timingsMs"]["population"] / 1000 for r in reports
                                       if r["case"]["name"] == name) for name in names])
    axes[0].set_xlabel("Population seconds (median); diagnostics only")
    axes[0].tick_params(axis="y", labelsize=8)
    for label, getter in (() if reports[0].get("layoutRegression") else (
        ("Python body construction", lambda r: r["traceSummary"]["bodyConstructionNs"]),
        ("Server DB write + sync (inclusive)", lambda r: r["traceSummary"]["serverPublicationStagesNs"]["databaseWriteAndSync"]),
        ("WAL force (nested in DB write)", lambda r: r["traceSummary"]["serverWriteStagesNs"].get("walForce", 0)))):
        axes[1].plot([str(s) for s in SIZES], [statistics.median(getter(r) / 1e9 for r in reports
                         if r["case"]["name"] == f"aether-put-{size:02d}") for size in SIZES], marker="o", label=label)
    axes[1].set_xlabel("Artifacts per putMany; lookup group fixed at 64")
    axes[1].set_ylabel("Seconds (median); overlapping stages")
    if reports[0].get("layoutRegression"):
        axes[1].barh(names, [statistics.median(r["layoutRegression"]["warm"]["samplesPerSecond"]
                         for r in reports if r["case"]["name"] == name) for name in names])
        axes[1].set_xlabel("Warm byte lookup samples/s; median of paired trials")
        axes[1].set_ylabel("")
    else:
        axes[1].legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(output / "population.png", dpi=160)
    plt.close(fig)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--request", type=Path)
    parser.add_argument("--scratch-root", type=Path)
    parser.add_argument("--smoke", action="store_true")
    parser.add_argument("--include-bulk", action="store_true")
    parser.add_argument("--bulk-layout", action="store_true")
    opts = parser.parse_args(argv)
    if opts.include_bulk and opts.bulk_layout:
        parser.error("choose the original bulk comparison or the isolated layout sweep")
    if opts.request:
        write_json(opts.output, run_case(json.loads(opts.request.read_text())))
        return
    manifest_dir = ROOT / "configs/paper/oct5k-longitudinal"
    manifests, paths = verify(manifest_dir)
    if manifests["counts"] != [1200, 1260, 1323, 1389, 1458] or manifests["seed"] != 20260926:
        raise ValueError("frozen longitudinal manifest drift")
    count, repeats = (65, 1) if opts.smoke else (1200, 3)
    import importlib.metadata
    for package, version in (("monai", "1.6.0"), ("lmdb", "2.1.1")):
        if importlib.metadata.version(package) != version:
            raise RuntimeError(f"requires {package}=={version}")
    started = time.perf_counter()
    args = workload_args(paths[0], count, 256)
    sources = base.workload.load_sources(args)
    reference = base.tensor_digest(base.CanonicalTransform(args)(item) for item in sources)
    update_count = 68 if opts.smoke else 1260
    update_reference = None
    if opts.bulk_layout:
        update_args = workload_args(paths[1], update_count, 256)
        update_sources = base.workload.load_sources(update_args)
        update_reference = base.tensor_digest(base.CanonicalTransform(update_args)(item) for item in update_sources)
    preflight_ms = (time.perf_counter() - started) * 1000
    order = []
    for block in range(repeats):
        row = cases()
        random.Random(20260929 + block).shuffle(row)
        if opts.include_bulk:
            row.insert((block * 5) % 12, cases(include_bulk=True)[-1])
        if opts.bulk_layout:
            row = layout_cases()
            row = row[block % 3:] + row[:block % 3]
        order.append(row)
    source_manifest = ROOT / "artifact-provenance.json"
    protocol = {"schema": "aether-population-diagnostic-v1", "confirmatory": False, "samples": count,
        "measurementRole": "smoke; excluded" if opts.smoke else "population-only diagnostic",
        "pairedRepetitions": repeats, "order": order, "seed": 20260929,
        "manifestSha256": manifests["manifestSha256"][0], "referenceHash": reference,
        "sourceManifestSha256": sha256(source_manifest) if source_manifest.exists() else None,
        "training": False, "model": None, "evolution": False, "transform": base.CanonicalTransform(args).identity,
        "durability": "unchanged: Aether DURABLE, mmap fsync, native MONAI durability; no forced equivalence",
        "sweep": "fixed 64-sample admission/lookup groups; only putMany chunks vary; pilot path separately uses 16",
        "scope": "startup + adapter open/scan/populate + quiescence + close; excludes source integrity/reference/readback",
        "traceCaveat": "traced arms are diagnostics, not performance claims; untraced pilot16 control estimates instrumentation impact",
        "pageCache": "uncontrolled; common integrity/reference preflight warms source data; fresh stores/processes each arm"}
    if opts.include_bulk:
        protocol.update(schema="aether-population-bulk-diagnostic-v1", bulkPrototype=True,
            bulkScope="offline empty-store, inline-only, bounded sort; commit is durable, stage acknowledgements are volatile",
            bulkOrder="original eleven-arm relative order retained; bulk inserted at (block*5)%12",
            bulkValidation="new ordinary daemon after offline writer stops, full tensor checksum, excluded from population timer")
    if opts.bulk_layout:
        protocol.update(schema="aether-bulk-layout-diagnostic-v1", evolution=True,
            sweep="only bulk SSTable target varies; publication batch remains 16",
            traceCaveat="bulk stage instrumentation enabled equally for all sizes; online request tracing off",
            evolutionScope="post-population regression only; never included in V0 timing",
            targetSstableBytes=[32 * 1024 ** 2, 64 * 1024 ** 2, 128 * 1024 ** 2],
            partitioning="unchanged v1 threshold, no balancing yet", verification="bulk-deferred-inventory-v2; one full inventory verification before manifest append",
            admission="unchanged v1", framing="unchanged v1 batch 16",
            orderDesign="cyclic balanced order, one position per size in three repetitions",
            updateManifestSha256=manifests["manifestSha256"][1], updateReferenceHash=update_reference,
            updateSamples=update_count, warmupPasses=2, measuredHitPasses=5,
            selection="descriptive paired warm ratio vs 32; investigate >=2% loss or elevated update/compaction cost; no automatic winner",
            nextGate="inspect layout results before balancing, verification pipeline, admission, framing, or longitudinal pilot")
    output = opts.output.resolve()
    scratch_base = (opts.scratch_root or ROOT / "build/population-stores").resolve()
    scratch_base.mkdir(parents=True, exist_ok=True)
    reports = []
    with campaign(output, protocol) as meta:
        write_json(output / "preflight.json", {"commonPreflightMs": preflight_ms, "referenceHash": reference})
        import tempfile
        for block, row in enumerate(order):
            for case in row:
                check_capacity(scratch_base, 1024 ** 3)
                location = output / f"block-{block:02d}" / case["name"]
                location.mkdir(parents=True, exist_ok=False)
                scratch = Path(tempfile.mkdtemp(prefix="population-", dir=scratch_base))
                request = {"case": case, "store": str(scratch / "store"), "manifest": str(paths[0]),
                           "samples": count, "imageSize": 256, "referenceHash": reference}
                if opts.bulk_layout:
                    request.update(layoutRegression=True, updateManifest=str(paths[1]), updateSamples=update_count,
                                   updateReferenceHash=update_reference)
                write_json(location / "request.json", request)
                print(f"Population {block + 1}/{repeats}: {case['name']}", flush=True)
                try:
                    with (location / "worker.log").open("w", encoding="utf-8") as log:
                        subprocess.run([sys.executable, str(Path(__file__).resolve()), "--request", str(location / "request.json"),
                            "--output", str(location / "worker.json")], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
                    report = json.loads((location / "worker.json").read_text())
                    report["blockIndex"] = block
                    save_result(location / "result.json", report, meta)
                    reports.append(report)
                    write_json(output / "summary.json", summarize(reports))
                finally:
                    for path in scratch.glob("store.*"):
                        if path.is_file():
                            shutil.copy2(path, location / path.name)
                # Only successful validated stores are removed. Failure retains diagnostics and store.
                if scratch.parent != scratch_base or not scratch.name.startswith("population-"):
                    raise ValueError("unsafe scratch cleanup path")
                shutil.rmtree(scratch)
        plot(reports, output)
        write_json(output / "completion.json", {"status": "passed", "cases": len(reports), "noTraining": True})


if __name__ == "__main__":
    main()
