"""Same-JVM bulk bootstrap for the frozen-storage H2 pilot."""
import contextlib
import json
import os
import queue
import socket
import struct
import subprocess
import threading
from pathlib import Path

from paper_common import java_classpath, write_json


class BootstrapWriter:
    def __init__(self, service):
        self.service = service

    def __enter__(self):
        self.socket = socket.create_connection(("127.0.0.1", self.service["bulkPort"]), timeout=120)
        self.stream = self.socket.makefile("rb")
        return self

    def exchange(self, body):
        self.socket.sendall(struct.pack(">I", len(body)) + body)
        line = self.stream.readline(1024 * 1024)
        if not line or not line.endswith(b"\n"):
            raise RuntimeError("incomplete bootstrap acknowledgement")
        return line.strip()

    def stage(self, body):
        expected = b"STAGED " + str(struct.unpack_from(">I", body, 2)[0]).encode()
        if self.exchange(body) != expected:
            raise RuntimeError("bootstrap staging acknowledgement mismatch")

    def finish(self):
        report = json.loads(self.exchange(b""))
        storage = report["storage"]
        if (report["status"] != "committed" or report["servicePid"] != self.service["pid"]
                or storage["verification"]["inventoryCalls"] != 1
                or storage["streamingVerification"]["implementation"] != "streaming-v1"
                or storage["walPayloadBytes"] != 0 or storage["memtableInsertions"] != 0):
            raise RuntimeError("frozen bootstrap invariant failed")
        return report

    def __exit__(self, *args):
        self.stream.close()
        self.socket.close()


@contextlib.contextmanager
def h2_daemon(directory):
    directory = Path(directory).resolve()
    directory.parent.mkdir(parents=True, exist_ok=True)
    command = ["java", "--enable-preview", "-cp", java_classpath(),
               "io.aetherdb.training.cache.H2BulkTrainingDaemon", str(directory)]
    service = None
    with directory.with_name(directory.name + ".stderr.log").open("w", encoding="utf-8") as errors:
        process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=errors, text=True,
            creationflags=subprocess.CREATE_NO_WINDOW if os.name == "nt" else 0)
        lines = queue.Queue()
        def read():
            for line in process.stdout:
                lines.put(line.strip())
            lines.put(None)
        reader = threading.Thread(target=read, daemon=True)
        reader.start()
        try:
            port = lines.get(timeout=60)
            if port is None or not port.isdecimal() or not 0 < int(port) < 65536:
                raise RuntimeError("H2 bootstrap failed to announce a port")
            service = {"pid": process.pid, "bulkPort": int(port), "command": command}
            yield service
        finally:
            try:
                if service and "port" in service and process.poll() is None:
                    from aether_training_cache.client import AetherTrainingCache
                    with AetherTrainingCache(port=service["port"]) as client:
                        drain = client.wait_for_background_compaction()
                    write_json(directory.with_name(directory.name + ".compaction.json"), drain)
                    if not drain["drained"] or drain["backgroundCompaction"].get("failed", 0):
                        raise RuntimeError("H2 final drain failed")
            finally:
                if process.poll() is None:
                    process.terminate()
                    try:
                        process.wait(timeout=15)
                    except subprocess.TimeoutExpired:
                        process.kill()
                        process.wait(timeout=15)
                reader.join(timeout=5)
                process.stdout.close()
