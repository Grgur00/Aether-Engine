"""H4 adapter for unchanged production online durable PUT_MANY admission."""
import hashlib
import json
import os
import queue
import subprocess
import threading
from contextlib import contextmanager
from pathlib import Path

from .prepare_pcam_manifest import PAYLOAD_BYTES


@contextmanager
def daemon_lifecycle(directory, *, exception_log=False, diagnostic_jfr=None, heap_samples=None):
    from paper_common import java_classpath
    directory = Path(directory).resolve()
    directory.mkdir(parents=True, exist_ok=True)
    source = Path(__file__).with_name("java") / "H4DaemonLifecycle.java"
    classpath = java_classpath()
    classes = directory.parent / "h4-launcher-classes"
    classes.mkdir(exist_ok=True)
    compilation = subprocess.run(["javac", "-J-Xmx256m", "--enable-preview", "--release", "21", "-cp", classpath,
                                  "-d", str(classes), str(source)], capture_output=True, text=True, timeout=90)
    if compilation.returncode:
        raise RuntimeError("H4 lifecycle harness compilation failed: " + compilation.stderr)
    command = ["java", "-Xmx512m", "--enable-preview", "-cp", str(classes) + os.pathsep + classpath,
               "H4DaemonLifecycle", str(directory), str(1 << 40)]
    if diagnostic_jfr is not None:
        destination = Path(diagnostic_jfr).resolve()
        destination.mkdir(parents=True, exist_ok=True)
        command.append(str(destination))
        command.insert(1, "-XX:FlightRecorderOptions=preserve-repository=true,repository=" +
                       str(destination / "jfr-repository"))
    if heap_samples is not None:
        destination = Path(heap_samples).resolve()
        destination.mkdir(parents=True, exist_ok=True)
        command.insert(1, "-Dh4.heap.samples=" + str(destination))
    if exception_log:
        command.insert(1, "-Xlog:exceptions=info:file=daemon-exceptions.log")
    log_path = directory.with_name(directory.name + ".stderr.log")
    with log_path.open("wb") as errors:
        process = subprocess.Popen(command, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                   stderr=errors, cwd=directory.parent,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        lines = queue.Queue()

        def reader():
            for line in process.stdout:
                lines.put(line.strip())
            lines.put(None)

        thread = threading.Thread(target=reader, daemon=True)
        thread.start()
        try:
            line = lines.get(timeout=90)
            if line is None or not line.isdigit() or not 0 < int(line) < 65536:
                raise RuntimeError("H4 daemon startup failed; inspect " + str(log_path))
            runtime = json.loads(directory.with_name(directory.name + ".runtime.json").read_text())
            if (runtime["pid"] != process.pid or runtime["java_major"] != 21
                    or not 0 < runtime["heap_max_bytes"] <= 512 * 1024 ** 2
                    or runtime["active_jfr_recording"] is not (diagnostic_jfr is not None)):
                raise ValueError("actual JVM heap/version/profiling differs from H4 policy")
            yield {"port": int(line), "pid": process.pid, "command": command, "runtime": runtime}
        finally:
            process.stdin.close()
            try:
                code = process.wait(timeout=180)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
                raise RuntimeError("H4 daemon graceful close timed out; run invalid")
            finally:
                thread.join(timeout=5)
                process.stdout.close()
            if code != 0:
                raise RuntimeError("H4 daemon failed during close; inspect " + str(log_path))


class AetherBackend:
    def __init__(self, directory, transform_sha256, *, daemon_factory=daemon_lifecycle, client_factory=None):
        from aether_training_cache.client import AetherTrainingCache, TransformationFingerprint
        self.directory = Path(directory).resolve()
        self.transform = TransformationFingerprint(bytes.fromhex(transform_sha256))
        self.daemon_factory = daemon_factory
        self.client_factory = client_factory or AetherTrainingCache
        self.manager = None
        self.client = None
        self.rows = {}

    def open(self, rows=()):
        if self.manager is not None:
            raise RuntimeError("H4 daemon already open")
        rows = list(rows)
        if len({row["sample_id"] for row in rows}) != len(rows):
            raise ValueError("duplicate Aether manifest sample ID")
        self.manager = self.daemon_factory(self.directory)
        try:
            self.daemon = self.manager.__enter__()
            self.client = self.client_factory(port=self.daemon["port"], server_trace=False)
            info = self.client.engine_info()
            if info["durability"] != "DURABLE" or info["pid"] != self.daemon["pid"]:
                raise ValueError("daemon durability/process identity mismatch")
            self.rows = {row["sample_id"]: row for row in rows}
            return self
        except BaseException:
            self.close()
            raise

    def _key(self, row):
        from aether_training_cache.client import CacheKey
        return CacheKey("h4", row["artifact_id"], self.transform)

    def populate(self, samples):
        if self.client.engine_info()["cacheEntries"] != 0:
            raise ValueError("H4 paired repetitions require a fresh empty Aether store")
        batch = []
        for row, payload in samples:
            if row["sample_id"] in self.rows:
                raise ValueError("duplicate H4 artifact sample ID")
            if len(payload) != PAYLOAD_BYTES or hashlib.sha256(payload).hexdigest() != row["artifact_sha256"]:
                raise ValueError("Aether input payload size/checksum mismatch")
            self.rows[row["sample_id"]] = dict(row)
            batch.append((self._key(row), payload))
            if len(batch) == 16:
                self.client.put_many(batch)
                batch = []
                if len(self.rows) % 256 == 0:
                    self.wait_for_quiescence()
        if batch:
            self.client.put_many(batch)
        if self.client.engine_info()["cacheEntries"] != len(self.rows):
            raise ValueError("Aether admitted cardinality mismatch or eviction")
        return {"samples": len(self.rows), "population": "online-durable-put-many-v1",
                "batch_size": 16, "drain_interval_artifacts": 256,
                "logical_bytes_written": len(self.rows) * PAYLOAD_BYTES}

    def wait_for_quiescence(self):
        report = self.client.wait_for_background_compaction(timeout=180)
        diagnostics = report["backgroundCompaction"]
        if not report["drained"] or diagnostics.get("failed", 0):
            raise RuntimeError("Aether background compaction did not reach healthy quiescence")
        return report

    @contextmanager
    def get_many(self, sample_ids):
        sample_ids = list(sample_ids)
        if not 0 < len(sample_ids) <= 16:
            raise ValueError("H4 lookup batch must contain 1 to 16 IDs")
        packed = self.client.get_many_values([self._key(self.rows[index]) for index in sample_ids])
        views = []
        try:
            for index in range(len(sample_ids)):
                view = packed.value(index)
                if view is None or len(view) != PAYLOAD_BYTES:
                    if view is not None:
                        view.release()
                    raise ValueError("Aether missing or malformed artifact")
                views.append(view)
            yield views
        finally:
            for view in views:
                view.release()
            packed.close()

    def validate(self, expected_rows):
        rows = list(expected_rows)
        if len({row["sample_id"] for row in rows}) != len(rows) or set(self.rows) != {row["sample_id"] for row in rows}:
            raise ValueError("Aether requested membership differs from manifest")
        if self.client.engine_info()["cacheEntries"] != len(rows):
            raise ValueError("Aether complete persisted membership count mismatch")
        for row in rows:
            if any(self.rows[row["sample_id"]][key] != row[key]
                   for key in ("artifact_id", "artifact_sha256")):
                raise ValueError("Aether committed identity differs from manifest")
        for start in range(0, len(rows), 16):
            batch = rows[start:start + 16]
            with self.get_many(row["sample_id"] for row in batch) as views:
                for row, view in zip(batch, views):
                    if hashlib.sha256(view).hexdigest() != row["artifact_sha256"]:
                        raise ValueError("Aether stored artifact checksum mismatch")
        return {"correctness_passed": True, "validated_samples": len(rows), "daemon_pid": self.daemon["pid"]}

    def metrics(self):
        files = [path for path in self.directory.rglob("*") if path.is_file()]
        tables = [path for path in files if path.suffix == ".aess"]
        return {"engine_info": self.client.engine_info(), "daemon_pid": self.daemon["pid"],
                "runtime": self.daemon.get("runtime"),
                "store_file_bytes": sum(path.stat().st_size for path in files),
                "store_allocated_bytes": (sum(path.stat().st_blocks * 512 for path in files)
                                          if all(hasattr(path.stat(), "st_blocks") for path in files) else None),
                "sstable_count": len(tables), "sstable_file_sizes": [path.stat().st_size for path in tables],
                "population": "online-durable-put-many-v1", "configured_bulk_target_sstable_bytes": None}

    def close(self):
        try:
            if self.client is not None:
                client, self.client = self.client, None
                client.close()
        finally:
            if self.manager is not None:
                manager, self.manager = self.manager, None
                manager.__exit__(None, None, None)
