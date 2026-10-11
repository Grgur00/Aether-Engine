"""Validate completed online-admission diagnostics before advancing store sizes."""
import hashlib
import json
from pathlib import Path, PurePosixPath

PAYLOAD_BYTES = 110592
HEAP_BYTES = 512 * 1024 ** 2


def validate_successful_online(report):
    samples = report.get("samples")
    if (report.get("schema") != "aether-h4-online-size-preflight-v1"
            or report.get("passed") is not True or type(samples) is not int or samples <= 0
            or report.get("logical_payload_bytes") != samples * PAYLOAD_BYTES
            or report.get("java_heap_bytes") != HEAP_BYTES
            or report.get("put_batch") != 16 or report.get("lookup_batch") != 16
            or report.get("population") != "online-durable-put-many-v1"
            or report.get("primary_h4_eligible") is not False
            or report.get("out_of_core_verified") is not False
            or report.get("scratch_cleanup_completed") is not True
            or report.get("diagnostic_jfr", False) is not False
            or any(report.get(key) for key in ("error", "error_type", "cleanup_error"))):
        raise ValueError("successful admission lacks bounded workload or clean completion evidence")
    admission = report.get("admission", {})
    if (admission.get("samples") != samples or admission.get("batch_size") != 16
            or admission.get("logical_bytes_written") != samples * PAYLOAD_BYTES):
        raise ValueError("successful admission lacks complete durable population")
    restart = report.get("restart", {})
    first, reopened = restart.get("first_pid"), restart.get("reopened_pid")
    if type(first) is not int or first <= 0 or type(reopened) is not int or reopened <= 0 or first == reopened:
        raise ValueError("successful admission lacks a fresh JVM reopen")
    for field, pid in (("preclose_validation", first), ("reopen_validation", reopened),
                       ("postread_validation", reopened)):
        validation = report.get(field, {})
        if (validation.get("correctness_passed") is not True
                or validation.get("validated_samples") != samples or validation.get("daemon_pid") != pid):
            raise ValueError("successful admission lacks full artifact validation")
    for field in ("preclose_quiescence", "reopen_quiescence", "postread_quiescence"):
        quiescence = report.get(field, {})
        compaction = quiescence.get("backgroundCompaction", {})
        started, completed = compaction.get("flushesStarted"), compaction.get("flushesCompleted")
        if (quiescence.get("drained") is not True or compaction.get("failed") != 0
                or compaction.get("state") != "IDLE"
                or type(started) is not int or started < 0
                or type(completed) is not int or completed < 0 or started != completed):
            raise ValueError("successful admission lacks successful quiescence")
    for field, pid in (("preclose_metrics", first), ("final_metrics", reopened)):
        runtime = report.get(field, {}).get("runtime", {})
        if (runtime.get("pid") != pid or runtime.get("java_major") != 21
                or type(runtime.get("heap_max_bytes")) is not int
                or not 0 < runtime["heap_max_bytes"] <= HEAP_BYTES
                or runtime.get("active_jfr_recording") is not False):
            raise ValueError("successful admission lacks bounded runtime evidence")
    shuffled = report.get("shuffled_read", {})
    consumer = shuffled.get("consumer_sha256", "")
    if (shuffled.get("logical_bytes_consumed") != samples * PAYLOAD_BYTES
            or not isinstance(consumer, str) or len(consumer) != 64
            or any(letter not in "0123456789abcdef" for letter in consumer)):
        raise ValueError("successful admission lacks full shuffled-byte consumption")
    return samples * PAYLOAD_BYTES


def read_verified_online(path):
    path = Path(path).resolve(strict=True)
    report = json.loads(path.read_text())
    validate_successful_online(report)
    directory = path.parent
    inventory = json.loads((directory / "sha256.json").read_text())
    actual = {item.relative_to(directory).as_posix() for item in directory.rglob("*")
              if item.is_file() and item != directory / "sha256.json"}
    if set(inventory) != actual or path.name not in inventory or "sample_hashes.json" not in inventory:
        raise ValueError("admission inventory must cover exactly the preserved evidence")
    for name, expected in inventory.items():
        relative = PurePosixPath(name)
        target = (directory / name).resolve(strict=True)
        if (relative.is_absolute() or ".." in relative.parts or "\\" in name
                or not target.is_relative_to(directory)):
            raise ValueError("unsafe admission evidence path")
        with target.open("rb") as stream:
            if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                raise ValueError("admission evidence checksum mismatch: " + name)
    rows = json.loads((directory / "sample_hashes.json").read_text())
    if (len(rows) != report["samples"] or {row["sample_id"] for row in rows} != set(range(report["samples"]))
            or len({row["artifact_id"] for row in rows}) != len(rows)):
        raise ValueError("admission sample evidence is incomplete or duplicated")
    return report
