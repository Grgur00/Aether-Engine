"""One fresh backend lifecycle; caller is responsible for controlled process launch."""
import os
import shutil
import tempfile
import time
from contextlib import ExitStack
from pathlib import Path

from .common import write_json
from .inputs import sample_stream
from .memory_controller import snapshot
from .prepare_pcam_manifest import PAYLOAD_BYTES
from .retrieval import measure_pattern
from .storage_preflight import DiskPeak
from .validate_h4 import validate_retrieval


def run_backend(task, binding, *, group=None, stream_factory=sample_stream):
    backend_name = task["backend"]
    if backend_name not in ("aether", "mmap"):
        raise ValueError("unsupported primary backend")
    condition = binding["manifest"]["conditions"][task["condition"]]
    membership = set(condition["sequential_ids"])
    rows = [row for row in binding["rows"] if row["sample_id"] in membership]
    if [row["sample_id"] for row in rows] != condition["sequential_ids"]:
        raise ValueError("backend population ordering differs from manifest")
    scratch = Path(task["scratch"]).resolve(strict=True)
    if shutil.disk_usage(scratch).free < condition["payload_bytes"] * 3 + 2 * 1024 ** 3:
        raise ValueError("insufficient safe scratch capacity")
    result = dict(schema="aether-h4-backend-v1", backend=backend_name, passed=False,
                  primary_h4_eligible=False, worker_pid=os.getpid(), condition=task["condition"],
                  protocol_sha256=binding["protocol_sha256"], manifest_sha256=binding["manifest_sha256"],
                  dataset_sha256=binding["dataset_sha256"], memory_limit_bytes=binding["protocol"]["memory_limit_bytes"],
                  source_verified=binding["source_verified"], patterns={}, samples=len(rows),
                  logical_payload_bytes=len(rows) * PAYLOAD_BYTES)
    with tempfile.TemporaryDirectory(prefix="aether-h4-backend-", dir=scratch) as temporary:
        store = Path(temporary) / "store"
        peak = DiskPeak(store)
        peak.start()
        try:
            with ExitStack() as cleanup:
                if backend_name == "aether":
                    from .aether_backend import AetherBackend
                    backend = AetherBackend(store, binding["transform_sha256"])
                    cleanup.callback(backend.close)
                    backend.open()
                    populate = backend.populate
                else:
                    from .mmap_backend import MmapBackend
                    backend = MmapBackend(store)
                    cleanup.callback(lambda: backend.close())
                    populate = backend.append
                result["population_cgroup_before"] = snapshot(group) if group is not None else None
                started = time.perf_counter()
                result["population"] = populate(stream_factory(task["data"], rows))
                result["population_seconds_including_preprocessing"] = time.perf_counter() - started
                if task.get("progress"):
                    write_json(task["progress"], result)
                if backend_name == "aether":
                    result["preclose_quiescence"] = backend.wait_for_quiescence()
                result["population_cgroup_after_quiescence"] = snapshot(group) if group is not None else None
                result["preclose_validation"] = backend.validate(rows)
                backend.close()
                if backend_name == "aether":
                    previous_pid = backend.daemon["pid"]
                    backend.open(rows)
                    if previous_pid == backend.daemon["pid"]:
                        raise ValueError("persistence check did not use a fresh JVM")
                    result["restart"] = dict(first_pid=previous_pid, reopened_pid=backend.daemon["pid"])
                    result["reopen_quiescence"] = backend.wait_for_quiescence()
                else:
                    from .mmap_backend import MmapBackend
                    backend = MmapBackend(store)
                result["reopen_validation"] = backend.validate(rows)
                result["pre_read_metrics"] = backend.metrics()
                for pattern in binding["protocol"]["access_patterns"]:
                    record = measure_pattern(backend, rows, condition[pattern + "_ids"], pattern=pattern,
                                             group=group, limit=result["memory_limit_bytes"],
                                             java_pid=backend.daemon["pid"] if backend_name == "aether" else None)
                    validate_retrieval(record, rows, condition[pattern + "_ids"], pattern)
                    result["patterns"][pattern] = record
                    if task.get("progress"):
                        write_json(task["progress"], result)
                    print(f"H4 {backend_name} condition={task['condition']} {pattern} completed", flush=True)
                result["postread_validation"] = backend.validate(rows)
                if backend_name == "aether":
                    result["postread_quiescence"] = backend.wait_for_quiescence()
                result["final_metrics"] = backend.metrics()
                result["final_cgroup"] = snapshot(group) if group is not None else None
        except Exception as error:
            result.update(error_type=type(error).__name__, error=str(error))
            result["failure_cgroup"] = snapshot(group) if group is not None else None
            if task.get("diagnostics"):
                destination = Path(task["diagnostics"])
                destination.mkdir(parents=True, exist_ok=True)
                log = store.with_name(store.name + ".stderr.log")
                if log.exists():
                    shutil.copy2(log, destination / "java.stderr.log")
        finally:
            result["disk_peak"] = peak.stop()
    result.update(passed="error_type" not in result, scratch_cleanup_completed=True)
    return result
