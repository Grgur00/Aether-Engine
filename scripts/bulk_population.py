"""Offline bulk prototype driver. STAGED is volatile; only explicit finish is durable."""
import json
import os
from pathlib import Path
import queue
import re
import struct
import subprocess
import threading
import time

from paper_common import java_classpath, ROOT
from profile_population import PublicationClient


class BulkPipeWriter:
    def __init__(self, store, *, crash_point=None, timeout=120, target_bytes=32 * 1024 ** 2,
                 jfr_file=None, jfr_settings="profile"):
        self.store, self.timeout = Path(store), timeout
        self.crash_point = crash_point
        self.target_bytes = target_bytes
        self.jfr_file, self.jfr_settings = jfr_file, jfr_settings
        self.process = None
        self.committed = False
        self.closed = False
        self.lines = queue.Queue()

    def __enter__(self):
        self.store.parent.mkdir(parents=True, exist_ok=True)
        classpath = java_classpath()
        main = "io.aetherdb.training.cache.BulkArtifactWriter"
        options = []
        if self.jfr_file:
            recording = Path(self.jfr_file).resolve()
            recording.parent.mkdir(parents=True, exist_ok=True)
            if recording.exists():
                raise FileExistsError("JFR recording must be fresh")
            repository = recording.with_suffix(".repository")
            repository.mkdir(exist_ok=False)
            options += ["-Daether.bulk.jfr=true", f"-XX:FlightRecorderOptions=stackdepth=256,repository={repository}",
                        "-Xlog:jfr*=warning:stderr",
                        f"-XX:StartFlightRecording=filename={recording},settings={self.jfr_settings},disk=true,dumponexit=true"]
        if self.crash_point:
            classpath += os.pathsep + str(ROOT / "modules/aether-training-cache/build/classes/java/test")
            main = "io.aetherdb.training.cache.BulkArtifactCrashProbe"
            options += ["-Dbulk.test.crash=" + self.crash_point]
        self.errors = self.store.with_name(self.store.name + ".bulk.stderr.log").open("wb")
        try:
            self.process = subprocess.Popen(["java", *options, "--enable-preview", "-cp", classpath, main,
                str(self.store), str(self.target_bytes)],
                stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=self.errors,
                creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
            def reader():
                for line in self.process.stdout:
                    # StartFlightRecording can re-enable stdout startup logging despite -Xlog routing.
                    if self.jfr_file and re.match(rb"^\[[0-9.]+s\]\[info\]\[jfr,startup\]", line):
                        self.errors.write(line)
                        self.errors.flush()
                        continue
                    self.lines.put(line)
                self.lines.put(None)
            self.reader = threading.Thread(target=reader, daemon=True)
            self.reader.start()
            ready = self.line()
            if ready != b"READY":
                raise RuntimeError(f"bulk writer did not announce readiness: {ready!r}")
            return self
        except BaseException:
            self.__exit__(None, None, None)
            raise

    def line(self):
        try:
            value = self.lines.get(timeout=self.timeout)
        except queue.Empty as error:
            raise TimeoutError("bulk writer response timed out") from error
        if value is None:
            raise RuntimeError("bulk writer exited before acknowledgement; retain store and inspect bulk stderr")
        return value.strip()

    def exchange(self, body):
        if self.committed:
            raise RuntimeError("bulk writer already committed")
        timer = threading.Timer(self.timeout, self.process.kill)
        timer.start()
        try:
            self.process.stdin.write(struct.pack(">I", len(body)) + body)
            self.process.stdin.flush()
            return self.line()
        finally:
            timer.cancel()
            timer.join()

    def stage(self, body):
        expected = b"STAGED " + str(struct.unpack_from(">I", body, 2)[0]).encode("ascii")
        if self.exchange(body) != expected:
            raise RuntimeError("bulk staging acknowledgement mismatch")
        return b""

    def finish(self):
        report = json.loads(self.exchange(b""))
        if report.get("status") != "committed":
            raise RuntimeError("bulk commit not acknowledged")
        self.committed = True
        return report

    def __exit__(self, exc_type, exc, traceback):
        if self.closed:
            return
        self.closed = True
        if self.process is not None:
            try:
                self.process.stdin.close()  # EOF without finish aborts; never auto-commit.
            except OSError:
                pass
            try:
                code = self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                code = self.process.wait(timeout=15)
            self.reader.join(timeout=5)
            self.process.stdout.close()
            self.errors.close()
            if exc_type is None and self.committed and code != 0:
                raise RuntimeError("bulk writer failed during close")
        elif hasattr(self, "errors"):
            self.errors.close()


class BulkPublicationClient(PublicationClient):
    def __init__(self, writer, put_batch=16):
        self.writer = writer
        super().__init__(put_batch=put_batch, traced=False)

    def _exchange(self, body, *args, **kwargs):
        if body[1] != 6:
            raise ValueError("offline bulk pipe only stages PUT_MANY bodies")
        result = self.writer.stage(body)
        self.requests_sent += 1
        self.operation_counts[6] = self.operation_counts.get(6, 0) + 1
        return result


def run_bulk_case(request):
    from collections import Counter
    from profile_population import base, workload_args, TimedTransform, TimedCodec, DiskSampler, strict_drain
    from aether_ml.identity import artifact_key
    store = Path(request["store"])
    if store.exists():
        raise FileExistsError("bulk population requires a fresh empty cache")
    args = workload_args(request["manifest"], request["samples"], request["imageSize"], trusted=True)
    sources = base.workload.load_sources(args)
    transform, codec = TimedTransform(args), TimedCodec(base.TensorDictCodec())
    preprocessing = Counter()
    original = base.workload.preprocess_sample_with_timing
    def capture(*a, **kw):
        value, counters = original(*a, **kw)
        preprocessing.update(counters)
        return value, counters
    sampler = DiskSampler(store)
    sampler.start()
    before = base.snapshot()
    target_bytes = request["case"].get("targetSstableBytes", 32 * 1024 ** 2)
    manager, entered = BulkPipeWriter(store, target_bytes=target_bytes,
        jfr_file=request.get("jfrFile"), jfr_settings=request.get("jfrSettings", "profile")), False
    memory = None
    base.workload.preprocess_sample_with_timing = capture
    try:
        started = time.perf_counter()
        writer = manager.__enter__()
        entered = True
        startup_ms = (time.perf_counter() - started) * 1000
        if request.get("layoutRegression"):
            from bulk_layout import MemorySampler
            memory = MemorySampler(writer.process.pid)
            memory.start()
        client = BulkPublicationClient(writer, request["case"]["putBatch"])
        started = time.perf_counter()
        for batch in base.batches(len(sources), 16):
            client.put_many([(artifact_key("monai-pilot-v1", sources[i]["source_identity"], transform.identity, "1"),
                              codec.encode(transform(sources[i]))) for i in batch])
        commit = writer.finish()
        population_ms = (time.perf_counter() - started) * 1000
        population_preprocessing = dict(preprocessing)
        if (commit["artifacts"] != len(sources) or commit["storage"]["entries"] != len(sources)
                or commit["sha256Calls"] != len(sources) or transform.calls != len(sources)
                or commit["storage"]["walPayloadBytes"] != 0 or commit["storage"]["memtableInsertions"] != 0):
            raise RuntimeError("bulk commit cardinality mismatch")
        started = time.perf_counter()
        manager.__exit__(None, None, None)
        entered = False
        close_ms = (time.perf_counter() - started) * 1000
        if request.get("jfrFile") and (not Path(request["jfrFile"]).is_file() or Path(request["jfrFile"]).stat().st_size == 0):
            raise RuntimeError("bulk writer did not dump its JFR recording")
        memory_report = memory.stop() if memory else None
        population_disk = {**base.disk_usage(store), **sampler.stop()}
        # A genuinely new JVM validates every artifact using the ordinary cache reader.
        started = time.perf_counter()
        with base.java_daemon(store) as daemon:
            validation_transform = base.CanonicalTransform(args)
            ds = base.dataset("aether", sources, validation_transform, store, daemon["port"])
            try:
                actual = base.tensor_digest(v for batch in base.batches(len(sources), 16) for v in base.fetch(ds, batch))
                if actual != request["referenceHash"] or validation_transform.calls:
                    raise RuntimeError("bulk restart validation checksum/miss failure")
                with base.AetherTrainingCache(port=daemon["port"]) as observer:
                    info = observer.engine_info()
                drain = strict_drain(daemon["port"])
                if info["cacheEntries"] != len(sources):
                    raise RuntimeError("bulk restart artifact count mismatch")
                regression = None
                if request.get("layoutRegression"):
                    from bulk_layout import regressions
                    regression = regressions(request, sources, transform.identity, daemon, store)
            finally:
                base.close(ds)
        validation_ms = (time.perf_counter() - started) * 1000
        return {"case": request["case"], "samples": len(sources), "preprocessCalls": transform.calls,
            "uniqueArtifacts": info["cacheEntries"], "tensorSha256": actual, "trainingSampleRequests": 0, "model": None,
            "timingsMs": {"startup": startup_ms, "population": population_ms, "quiescence": 0., "close": close_ms},
            "totalMs": startup_ms + population_ms + close_ms, "validationMs": validation_ms,
            "sourceLoadAndPreprocessMs": transform.elapsed_ns / 1e6, "preprocessingBreakdownMs": population_preprocessing,
            "codec": {"encodeNs": codec.encode_ns, "decodeNs": 0, "encodedBytes": codec.bytes},
            "traceSummary": None, "bulkCommit": commit, "publicationRequests": client.publications,
            "layoutRegression": regression, "populationMemory": memory_report,
            "protocolMetrics": client.protocol_metrics(), "engineInfo": info,
            "restartValidation": {"passed": True, "expectedEntries": len(sources), "observedEntries": info["cacheEntries"],
                                  "tensorSha256": actual, "drain": drain},
            "disk": population_disk,
            "processUsage": {"python": base.delta(before, base.snapshot()), "java": None},
            "quiescenceScope": "synchronous offline writer: all table/WAL-header/manifest forces included in population; no queued work",
            "timingExclusions": "restart correctness JVM and readback; no inference about a future persistent-service lifecycle",
            "prototypeLimits": {"inlineOnly": True, "maxBufferedBytes": 512 * 1024 ** 2, "maxEntries": 100000,
                                "sortedTableTargetBytes": target_bytes}}
    finally:
        base.workload.preprocess_sample_with_timing = original
        sampler.stop()
        if memory:
            memory.stop()
        if entered:
            manager.__exit__(RuntimeError, None, None)
