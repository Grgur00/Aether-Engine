"""Backend-independent H4 consumption, validation and read-window measurements."""
import hashlib
import math
import os
import time
from pathlib import Path

from .common import read_evidence
from .memory_controller import assess_read_window, snapshot, verify_enforcement
from .prepare_pcam_manifest import PAYLOAD_BYTES


def process_snapshot(pids):
    clock_ticks = os.sysconf("SC_CLK_TCK") if hasattr(os, "sysconf") else None
    page_size = os.sysconf("SC_PAGE_SIZE") if hasattr(os, "sysconf") else None
    return {str(pid): dict({name: read_evidence(Path("/proc") / str(pid) / name)
                           for name in ("stat", "status", "io", "cgroup")},
                          clock_ticks_per_second=clock_ticks, page_size_bytes=page_size) for pid in pids}


def validate_order(rows, order):
    if not rows or len({row["sample_id"] for row in rows}) != len(rows):
        raise ValueError("nonempty unique manifest membership required")
    if len(order) != len(rows) or len(set(order)) != len(order) or set(order) != {row["sample_id"] for row in rows}:
        raise ValueError("read order must contain every manifest sample exactly once")


def consume(backend, order, *, batch_size=16, expected_rows=None):
    consumer = hashlib.sha256()
    consumed = 0
    for start in range(0, len(order), batch_size):
        ids = order[start:start + batch_size]
        with backend.get_many(ids) as views:
            if len(views) != len(ids):
                raise ValueError("backend returned a different lookup cardinality")
            for sample_id, view in zip(ids, views):
                if len(view) != PAYLOAD_BYTES:
                    raise ValueError("retrieved artifact has incorrect payload width")
                if expected_rows is not None and hashlib.sha256(view).hexdigest() != expected_rows[sample_id]["artifact_sha256"]:
                    raise ValueError("artifact hash differs from the expected sample")
                consumer.update(view)
                consumed += len(view)
    return dict(consumer_sha256=consumer.hexdigest(), logical_bytes_consumed=consumed,
                artifacts_consumed=len(order))


def quantile(values, fraction):
    ordered = sorted(values)
    if not ordered:
        return None
    position = (len(ordered) - 1) * fraction
    lower, upper = math.floor(position), math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def latency_samples(backend, order, *, count=128, expected_rows=None):
    count = min(count, len(order))
    positions = [index * len(order) // count for index in range(count)]
    durations = []
    consumer = hashlib.sha256()
    for position in positions:
        started = time.perf_counter_ns()
        with backend.get_many([order[position]]) as views:
            elapsed = time.perf_counter_ns() - started
            if len(views) != 1 or len(views[0]) != PAYLOAD_BYTES:
                raise ValueError("invalid latency lookup response")
            if expected_rows is not None and hashlib.sha256(views[0]).hexdigest() != expected_rows[order[position]]["artifact_sha256"]:
                raise ValueError("latency lookup artifact differs from expected sample")
            consumer.update(views[0])
        durations.append(elapsed / 1e9)
    return dict(strategy="evenly spaced positions; separate single-artifact lookup pass",
                includes="lookup/context entry; excludes consumer hashing and release",
                unit="seconds", sample_ids=[order[index] for index in positions],
                sample_seconds=durations, p50=quantile(durations, .5),
                p95=quantile(durations, .95), p99=quantile(durations, .99),
                consumer_sha256=consumer.hexdigest())


def measure_pattern(backend, rows, order, *, pattern, group=None, limit=2 * 1024 ** 3,
                    java_pid=None, latency_count=128):
    rows, order = list(rows), list(order)
    validate_order(rows, order)
    if pattern not in ("sequential", "shuffled") or not 0 < latency_count <= 128:
        raise ValueError("invalid workload or latency sampling policy")
    expected_rows = {row["sample_id"]: row for row in rows}
    pids = [os.getpid()] + ([java_pid] if java_pid is not None else [])
    payload_bytes = len(rows) * PAYLOAD_BYTES
    # Reference digest is trusted only after every returned payload matches its manifest hash.
    reference = consume(backend, order, expected_rows=expected_rows)
    warmup = consume(backend, order)
    if warmup != reference:
        raise ValueError("warm-up content differs from verified reference")
    passes = []
    for index in range(3):
        before = snapshot(group) if group is not None else None
        if group is not None:
            verify_enforcement(before, limit=limit, pids=pids)
        processes_before = process_snapshot(pids)
        cpu_start = time.process_time()
        started = time.perf_counter_ns()
        result = consume(backend, order)
        seconds = (time.perf_counter_ns() - started) / 1e9
        cpu_seconds = time.process_time() - cpu_start
        processes_after = process_snapshot(pids)
        after = snapshot(group) if group is not None else None
        if group is not None:
            verify_enforcement(after, limit=limit, pids=pids)
        if seconds <= 0 or result != reference:
            raise ValueError("measured pass differs from verified content or has invalid timing")
        resource = (assess_read_window(before, after, limit=limit, payload_bytes=payload_bytes,
                    consumed_bytes=result["logical_bytes_consumed"], pids=pids)
                    if group is not None else dict(out_of_core_resource_evidence_verified=False,
                    primary_h4_eligible=False, physical_read_bytes=None,
                    reason="no controlled cgroup; storage-size sensitivity only"))
        passes.append(dict(index=index, seconds=seconds, artifacts_per_second=len(rows) / seconds,
                           logical_mib_per_second=payload_bytes / seconds / 1024 ** 2,
                           python_cpu_seconds=cpu_seconds, python_cpu_wall_ratio=cpu_seconds / seconds,
                           **result, resource=resource, cgroup_before=before, cgroup_after=after,
                           processes_before=processes_before, processes_after=processes_after))
    latency = latency_samples(backend, order, count=latency_count, expected_rows=expected_rows)
    post = consume(backend, order, expected_rows=expected_rows)
    if post != reference:
        raise ValueError("post-measurement validation differs from reference")
    return dict(schema="aether-h4-retrieval-v1", access_pattern=pattern, samples=len(rows),
                payload_bytes=payload_bytes, lookup_batch_size=16, warmup_passes=1,
                workload_pids=pids, memory_limit_bytes=limit,
                measured_passes=passes, warmup=warmup, verified_reference=reference,
                postread_validation=post, latency=latency, correctness_passed=True,
                block_mean_artifacts_per_second=sum(item["artifacts_per_second"] for item in passes) / 3,
                out_of_core_resource_evidence_verified=all(item["resource"]["out_of_core_resource_evidence_verified"]
                                                          for item in passes),
                primary_h4_eligible=False)
