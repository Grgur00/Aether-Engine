"""Online-admission scalability diagnostic, not the PCam H4 read experiment."""
import argparse
import csv
import hashlib
import json
from functools import partial
import math
import shutil
import tempfile
import threading
import time
from contextlib import ExitStack
from pathlib import Path

import numpy as np

from .aether_backend import AetherBackend, daemon_lifecycle
from .common import digest, write_json
from .admission_evidence import read_verified_online
from .prepare_pcam_manifest import PAYLOAD_BYTES, canonical_hash


def synthetic_sample(index):
    payload = np.random.Generator(np.random.PCG64(index)).random((3, 96, 96), dtype=np.float32).astype("<f4").tobytes()
    row = {"sample_id": index, "artifact_id": canonical_hash({"diagnostic": "H4-online-size-v1", "index": index}),
           "artifact_sha256": hashlib.sha256(payload).hexdigest()}
    return row, payload


class DiskPeak:
    def __init__(self, store):
        self.store = Path(store)
        self.stop_event = threading.Event()
        self.peak = 0
        self.samples = 0
        self.thread = threading.Thread(target=self._run, daemon=True)

    def _run(self):
        while not self.stop_event.is_set():
            total = 0
            for path in self.store.rglob("*"):
                try:
                    if path.is_file():
                        total += path.stat().st_size
                except FileNotFoundError:
                    pass
            self.peak = max(self.peak, total)
            self.samples += 1
            self.stop_event.wait(.1)

    def start(self):
        self.thread.start()

    def stop(self):
        self.stop_event.set()
        self.thread.join()
        return {"sampled_peak_file_bytes": self.peak, "samples": self.samples,
                "interval_seconds": .1, "limitation": "sampled lower bound; brief peaks may be missed"}


def exception_evidence(path):
    evidence = {"java_heap_exhaustion_observed": False, "sstable_verification_in_exception_log": False}
    if path.exists():
        with path.open(encoding="utf-8", errors="replace") as stream:
            for line in stream:
                if "OutOfMemoryError" in line and "Java heap space" in line:
                    evidence["java_heap_exhaustion_observed"] = True
                if "'verify'" in line and "SSTableReader" in line:
                    evidence["sstable_verification_in_exception_log"] = True
    return evidence


def heap_evidence(paths):
    observations, invalid = [], 0
    for path in paths:
        with Path(path).open(newline="", encoding="utf-8") as stream:
            for row in csv.DictReader(stream):
                try:
                    used, committed, maximum = (int(row[name]) for name in
                            ("heap_used_bytes", "heap_committed_bytes", "heap_max_bytes"))
                    if not 0 <= used <= committed <= maximum or maximum <= 0:
                        raise ValueError("invalid heap observation")
                    observations.append((used, committed))
                except (ValueError, TypeError, KeyError):
                    invalid += 1
    return {"observations": len(observations), "invalid_observations": invalid, "interval_seconds": .1,
            "maximum_observed_used_bytes": max((row[0] for row in observations), default=None),
            "maximum_observed_committed_bytes": max((row[1] for row in observations), default=None),
            "limitation": "sampled lower bound on peak; not a whole-process or page-cache limit"}


def require_previous_sizes(payload_bytes, reports, *, expected_java_sources=None):
    completed = set()
    for path in reports:
        receipt = json.loads(Path(path).read_text(encoding="utf-8"))
        if receipt.get("passed") is True:
            receipt = read_verified_online(path)
            if (expected_java_sources is not None
                    and receipt.get("java_build_sources") != expected_java_sources):
                raise ValueError("online preflight Java sources differ from the retrieval build or are absent")
            for gib in (1, 2, 4):
                if receipt.get("logical_payload_bytes", 0) >= gib * 1024 ** 3:
                    completed.add(gib)
    missing = [gib for gib in (1, 2, 4) if payload_bytes >= 2 * gib * 1024 ** 3 and gib not in completed]
    if missing:
        raise ValueError("successful earlier online preflights required: " + ", ".join(f"{gib} GiB" for gib in missing))


def run(output, scratch, samples, *, exception_log=False, prior_preflights=(), diagnostic_jfr=False,
        heap_samples=False):
    output, scratch = Path(output).resolve(), Path(scratch).resolve(strict=True)
    if type(samples) is not int or samples <= 0 or not scratch.is_dir():
        raise ValueError("positive sample count and existing scratch directory required")
    output.mkdir(parents=True, exist_ok=True)
    if any(output.iterdir()):
        raise ValueError("preflight output must be empty")
    payload_bytes = samples * PAYLOAD_BYTES
    required = payload_bytes * 3 + 2 * 1024 ** 3
    free = shutil.disk_usage(scratch).free
    if free < required:
        write_json(output / "preflight.json", {"passed": False, "reason": "insufficient safe disk capacity",
                   "required_free_bytes": required, "actual_free_bytes": free, "primary_h4_eligible": False})
        return False
    report = {"schema": "aether-h4-online-size-preflight-v1", "passed": False,
              "dataset": "seeded synthetic unique float32 tensors; not PCam",
              "samples": samples, "logical_payload_bytes": payload_bytes,
              "java_heap_bytes": 512 * 1024 ** 2, "put_batch": 16, "lookup_batch": 16,
              "population": "online-durable-put-many-v1", "primary_h4_eligible": False,
              "out_of_core_verified": False, "memory_enforcement": "not tested by this diagnostic",
              "diagnostic_exception_logging": exception_log,
              "diagnostic_jfr": diagnostic_jfr,
              "diagnostic_heap_sampling": heap_samples,
              "timing_role": "scalability diagnostic; population/source generation separate from shuffled reads"}
    rows, backend, peak = [], None, None
    try:
        require_previous_sizes(payload_bytes, prior_preflights)
        import paper_common
        paper_common.java_classpath()
        report["java_build_sources"] = paper_common.java_build_sources(paper_common.ROOT)
        build = paper_common.ROOT / "modules/aether-training-cache/build/paper-runtime-build.json"
        shutil.copy2(build, output / "java-runtime-build.json")
        with tempfile.TemporaryDirectory(prefix="aether-h4-online-", dir=scratch) as temporary, ExitStack() as cleanup:
            store = Path(temporary) / "store"

            def finish_resources():
                if backend is not None:
                    try:
                        if backend.client is not None:
                            report["last_engine_info"] = backend.client.engine_info()
                    except Exception as error:
                        report["last_engine_info_error"] = str(error)
                    try:
                        backend.close()
                    except Exception as error:
                        report["cleanup_error"] = str(error)
                        report["passed"] = False
                if peak is not None:
                    report["disk_peak"] = peak.stop()
                log = store.with_name(store.name + ".stderr.log")
                if log.exists():
                    shutil.copy2(log, output / "java.stderr.log")
                exceptions = store.parent / "daemon-exceptions.log"
                if exceptions.exists():
                    shutil.copy2(exceptions, output / "java-exceptions.log")
                    report["diagnostic_exception_evidence"] = exception_evidence(exceptions)

            cleanup.callback(finish_resources)
            peak = DiskPeak(store)
            peak.start()
            backend = AetherBackend(store, canonical_hash({"diagnostic_transform": "PCG64-float32-v1"}),
                                    daemon_factory=partial(daemon_lifecycle, exception_log=exception_log,
                                                           diagnostic_jfr=output if diagnostic_jfr else None,
                                                           heap_samples=output if heap_samples else None))
            backend.open()
            first_pid = backend.daemon["pid"]

            def samples_stream():
                for index in range(samples):
                    row, payload = synthetic_sample(index)
                    rows.append(row)
                    yield row, payload
                    if (index + 1) % 1024 == 0:
                        print(f"Online admitted {index + 1}/{samples} artifacts", flush=True)

            started = time.perf_counter()
            report["admission"] = backend.populate(samples_stream())
            report["population_seconds_including_synthetic_generation"] = time.perf_counter() - started
            report["preclose_quiescence"] = backend.wait_for_quiescence()
            report["preclose_validation"] = backend.validate(rows)
            report["preclose_metrics"] = backend.metrics()
            started = time.perf_counter()
            backend.close()
            report["graceful_close_seconds"] = time.perf_counter() - started
            backend.open(rows)
            if first_pid == backend.daemon["pid"]:
                raise RuntimeError("restart verification did not use a new JVM")
            report["restart"] = {"first_pid": first_pid, "reopened_pid": backend.daemon["pid"]}
            report["reopen_quiescence"] = backend.wait_for_quiescence()
            report["reopen_validation"] = backend.validate(rows)
            order = np.random.Generator(np.random.PCG64(20261012)).permutation(samples)
            consumer = hashlib.sha256()
            started = time.perf_counter()
            for start in range(0, samples, 16):
                with backend.get_many(int(index) for index in order[start:start + 16]) as views:
                    for view in views:
                        consumer.update(view)
            seconds = time.perf_counter() - started
            report["shuffled_read"] = {"seconds": seconds, "logical_bytes_consumed": payload_bytes,
                                       "artifacts_per_second": samples / seconds,
                                       "consumer_sha256": consumer.hexdigest(), "order_seed": 20261012}
            report["postread_validation"] = backend.validate(rows)
            report["postread_quiescence"] = backend.wait_for_quiescence()
            report["final_metrics"] = backend.metrics()
            backend.close()
            backend = None
            report["disk_peak"] = peak.stop()
            peak = None
            log = store.with_name(store.name + ".stderr.log")
            if log.exists():
                shutil.copy2(log, output / "java.stderr.log")
            report["passed"] = True
        report["scratch_cleanup_completed"] = True
    except (OSError, ValueError, RuntimeError, TimeoutError, EOFError) as error:
        report.update(error_type=type(error).__name__, error=str(error))
    finally:
        if heap_samples:
            report["heap_sampling"] = heap_evidence(output.glob("heap-*.csv"))
            if report["heap_sampling"]["observations"] == 0:
                report.update(passed=False, heap_measurement_error="no valid JVM heap observations")
        write_json(output / "preflight.json", report)
        write_json(output / "sample_hashes.json", rows)
        write_json(output / "sha256.json", {path.name: digest(path) for path in output.iterdir()
                                            if path.is_file() and path.name != "sha256.json"})
    return report["passed"]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scratch", type=Path, required=True)
    parser.add_argument("--gib", type=int, choices=(1, 2, 4, 8), default=1)
    parser.add_argument("--samples", type=int, help="small harness smoke only; not a GiB scalability result")
    parser.add_argument("--exception-log", action="store_true", help="diagnostic only; never primary timed runs")
    parser.add_argument("--diagnostic-jfr", action="store_true", help="profile admission; cannot qualify a capacity gate")
    parser.add_argument("--heap-samples", action="store_true", help="diagnostic JVM heap observations at 100 ms intervals")
    parser.add_argument("--prior-preflight", type=Path, action="append", default=[],
                        help="successful smaller-size preflight.json; required before 2/4/8 GiB")
    args = parser.parse_args()
    samples = args.samples if args.samples is not None else math.ceil(args.gib * 1024 ** 3 / PAYLOAD_BYTES)
    passed = run(args.output, args.scratch, samples, exception_log=args.exception_log,
                 prior_preflights=args.prior_preflight, diagnostic_jfr=args.diagnostic_jfr,
                 heap_samples=args.heap_samples)
    print("H4 online scalability preflight passed=" + str(passed), flush=True)
    return 0 if passed else 2


if __name__ == "__main__":
    raise SystemExit(main())
