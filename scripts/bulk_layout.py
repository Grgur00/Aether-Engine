"""Post-population layout checks, excluded from the V0 population endpoint."""
import os
import threading
import time

from aether_ml.identity import artifact_key
from hit_path_profile import require_hits, require_idle, require_no_activity, summarize
from profile_population import base, strict_drain, workload_args


class MemorySampler:
    def __init__(self, java_pid):
        self.processes = [os.getpid(), java_pid]
        self.available = [False, False]
        self.peaks = [0, 0]
        self.combined = 0
        self.done = threading.Event()
        self.thread = threading.Thread(target=self.run, daemon=True)

    def start(self):
        self.thread.start()

    def run(self):
        while not self.done.is_set():
            values = []
            for i, process in enumerate(self.processes):
                sample = base.snapshot(process)
                self.available[i] |= sample["available"]
                value = sample.get("rssBytes", 0)
                self.peaks[i] = max(self.peaks[i], value)
                values.append(value)
            self.combined = max(self.combined, sum(values))
            self.done.wait(.02)

    def stop(self):
        self.done.set()
        self.thread.join()
        return dict(pythonPeakRssBytes=self.peaks[0] if self.available[0] else None,
                    javaPeakRssBytes=self.peaks[1] if self.available[1] else None,
                    combinedPeakRssBytes=self.combined if all(self.available) else None, sampleIntervalMs=20,
                    scope="sampled RSS from bulk writer ready through exit, excludes restart/regressions; not an exact peak")


def regressions(request, sources, identity, daemon, store):
    keys = [artifact_key("monai-pilot-v1", item["source_identity"], identity, "1") for item in sources]
    batches = base.batches(len(keys), 16)
    with base.AetherTrainingCache(port=daemon["port"]) as client:
        def read(indices):
            started = time.perf_counter_ns()
            packed = client.get_many_values([keys[i] for i in indices])
            try:
                values = [packed.value(i) for i in range(len(indices))]
                require_hits(values, len(indices))
                size = sum(map(len, values))
            finally:
                packed.close()
            return dict(samples=len(indices), bytesReturned=size, durationNs=time.perf_counter_ns() - started)

        for _ in range(2):
            for batch in batches:
                read(batch)
        before = client.engine_info()["backgroundCompaction"]
        require_idle(before)
        records, epochs = [], []
        for epoch in range(5):
            started = time.perf_counter_ns()
            rows = [read(batch) for batch in batches]
            elapsed = time.perf_counter_ns() - started
            epochs.append(summarize(rows, elapsed))
            records.extend(rows)
        after = client.engine_info()["backgroundCompaction"]
        require_no_activity(before, after)
        warm = summarize(records, sum(e["elapsedNs"] for e in epochs))
        warm.update(epochs=epochs, warmupPasses=2, measuredPasses=5, requestSize=16,
                    scope="existing hit-only packed-byte lookup and invariant helpers; no decode/model/training/prefetch",
                    before=before, after=after)

    args = workload_args(request["updateManifest"], request["updateSamples"], request["imageSize"], trusted=True)
    updated = base.workload.load_sources(args)
    if [s["source_identity"] for s in updated[:len(sources)]] != [s["source_identity"] for s in sources]:
        raise ValueError("incremental regression must retain the exact V0 prefix")
    transform = base.CanonicalTransform(args)
    if transform.identity != identity:
        raise ValueError("incremental transform drift")
    before_usage, before_disk = base.snapshot(daemon["pid"]), base.disk_usage(store)
    started = time.perf_counter()
    ds = base.dataset("aether", updated, transform, store, daemon["port"])
    try:
        for batch in base.batches(len(updated), 16):
            base.fetch(ds, batch)
        admission_ms = (time.perf_counter() - started) * 1000
        started = time.perf_counter()
        drain = strict_drain(daemon["port"])
        drain_ms = (time.perf_counter() - started) * 1000
        usage = base.delta(before_usage, base.snapshot(daemon["pid"]))
        after_disk = base.disk_usage(store)
        expected_new = len(updated) - len(sources)
        if transform.calls != expected_new:
            raise RuntimeError("incremental update recomputed old artifacts")
        actual = base.tensor_digest(v for batch in base.batches(len(updated), 16) for v in base.fetch(ds, batch))
        if actual != request["updateReferenceHash"] or transform.calls != expected_new:
            raise RuntimeError("incremental readback mismatch")
        with base.AetherTrainingCache(port=daemon["port"]) as client:
            info = client.engine_info()
        if info["cacheEntries"] != len(updated):
            raise RuntimeError("incremental cardinality mismatch")
        update = dict(newSamples=expected_new, reusedSamples=len(sources), admissionMs=admission_ms,
                      drainMs=drain_ms, preprocessCalls=transform.calls, tensorSha256=actual,
                      processUsage=usage, diskBefore=before_disk, diskAfter=after_disk,
                      engineBefore=after, engineAfter=info["backgroundCompaction"], drain=drain,
                      writeAmplificationCaveat="process I/O and logical disk deltas, not device-level write amplification")
    finally:
        base.close(ds)
    return dict(warm=warm, incremental=update)
