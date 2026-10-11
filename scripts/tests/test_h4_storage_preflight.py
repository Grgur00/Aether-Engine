import json
import math
import hashlib

import pytest

from h4.storage_preflight import exception_evidence, require_previous_sizes, synthetic_sample
from h4.prepare_pcam_manifest import PAYLOAD_BYTES


def test_synthetic_artifacts_are_unique_and_repeatable():
    assert synthetic_sample(1) == synthetic_sample(1)
    first, payload = synthetic_sample(1)
    second, other = synthetic_sample(2)
    assert first["artifact_id"] != second["artifact_id"]
    assert first["artifact_sha256"] != second["artifact_sha256"]
    assert len(payload) == len(other) == PAYLOAD_BYTES


def test_one_gib_does_not_need_prior_run_but_larger_sizes_do():
    require_previous_sizes(math.ceil(1024 ** 3 / PAYLOAD_BYTES) * PAYLOAD_BYTES, [])
    for gib in (2, 4, 8):
        with pytest.raises(ValueError, match="earlier online preflights"):
            require_previous_sizes(gib * 1024 ** 3, [])


def test_failed_or_small_preflights_do_not_unlock_larger_run(tmp_path):
    path = tmp_path / "preflight.json"
    receipt = dict(schema="aether-h4-online-size-preflight-v1", passed=True,
                   java_heap_bytes=512 * 1024 ** 2, population="online-durable-put-many-v1",
                   scratch_cleanup_completed=True, logical_payload_bytes=32 * PAYLOAD_BYTES)
    path.write_text(json.dumps(receipt))
    with pytest.raises(ValueError):
        require_previous_sizes(2 * 1024 ** 3, [path])
    receipt.update(passed=False, logical_payload_bytes=1024 ** 3)
    path.write_text(json.dumps(receipt))
    with pytest.raises(ValueError):
        require_previous_sizes(2 * 1024 ** 3, [path])
    receipt["passed"] = True
    path.write_text(json.dumps(receipt))
    with pytest.raises(ValueError, match="bounded workload"):
        require_previous_sizes(2 * 1024 ** 3, [path])
    receipt = successful_receipt(math.ceil(1024 ** 3 / PAYLOAD_BYTES))
    preserve_receipt(path, receipt)
    require_previous_sizes(2 * 1024 ** 3, [path])
    with pytest.raises(ValueError, match="2 GiB"):
        require_previous_sizes(4 * 1024 ** 3, [path])


def successful_receipt(samples):
    receipt = dict(schema="aether-h4-online-size-preflight-v1", passed=True, samples=samples,
                   logical_payload_bytes=samples * PAYLOAD_BYTES, java_heap_bytes=512 * 1024 ** 2,
                   put_batch=16, lookup_batch=16, population="online-durable-put-many-v1",
                   primary_h4_eligible=False, out_of_core_verified=False, scratch_cleanup_completed=True,
                   admission=dict(samples=samples, batch_size=16, logical_bytes_written=samples * PAYLOAD_BYTES),
                   restart=dict(first_pid=101, reopened_pid=102),
                   shuffled_read=dict(logical_bytes_consumed=samples * PAYLOAD_BYTES, consumer_sha256="a" * 64))
    for field, pid in (("preclose_validation", 101), ("reopen_validation", 102), ("postread_validation", 102)):
        receipt[field] = dict(correctness_passed=True, validated_samples=samples, daemon_pid=pid)
    for field in ("preclose_quiescence", "reopen_quiescence", "postread_quiescence"):
        receipt[field] = dict(drained=True, backgroundCompaction=dict(state="IDLE", failed=0,
                                                                    flushesStarted=2, flushesCompleted=2))
    for field, pid in (("preclose_metrics", 101), ("final_metrics", 102)):
        receipt[field] = dict(runtime=dict(pid=pid, java_major=21, heap_max_bytes=512 * 1024 ** 2,
                                          active_jfr_recording=False))
    return receipt


def test_primary_gate_requires_matching_java_sources(tmp_path):
    path = tmp_path / "preflight.json"
    receipt = successful_receipt(math.ceil(1024 ** 3 / PAYLOAD_BYTES))
    preserve_receipt(path, receipt)
    sources = {"modules/example/src/main/Example.java": "a" * 64}
    with pytest.raises(ValueError, match="Java sources"):
        require_previous_sizes(2 * 1024 ** 3, [path], expected_java_sources=sources)
    receipt["java_build_sources"] = sources
    preserve_receipt(path, receipt)
    require_previous_sizes(2 * 1024 ** 3, [path], expected_java_sources=sources)
    with pytest.raises(ValueError, match="Java sources"):
        require_previous_sizes(2 * 1024 ** 3, [path], expected_java_sources={"other": "b" * 64})


def test_profiled_run_cannot_unlock_capacity_gate():
    from h4.admission_evidence import validate_successful_online
    receipt = successful_receipt(32)
    receipt["diagnostic_jfr"] = True
    with pytest.raises(ValueError, match="bounded workload"):
        validate_successful_online(receipt)


def test_heap_samples_keep_valid_evidence_and_report_truncated_rows(tmp_path):
    from h4.storage_preflight import heap_evidence
    path = tmp_path / "heap.csv"
    path.write_text("monotonic_ns,heap_used_bytes,heap_committed_bytes,heap_max_bytes\n1,10,20,30\n2,12")
    report = heap_evidence([path])
    assert report["observations"] == report["invalid_observations"] == 1
    assert report["maximum_observed_used_bytes"] == 10
    assert heap_evidence([])["maximum_observed_used_bytes"] is None


def preserve_receipt(path, receipt):
    path.write_text(json.dumps(receipt))
    rows = [dict(sample_id=index, artifact_id=str(index), artifact_sha256="a" * 64)
            for index in range(receipt["samples"])]
    sample_path = path.parent / "sample_hashes.json"
    sample_path.write_text(json.dumps(rows))
    inventory = {item.name: hashlib.sha256(item.read_bytes()).hexdigest() for item in (path, sample_path)}
    (path.parent / "sha256.json").write_text(json.dumps(inventory))


@pytest.mark.parametrize("field", ["reopen_validation", "postread_quiescence", "final_metrics", "shuffled_read"])
def test_incomplete_admission_evidence_does_not_unlock_larger_store(tmp_path, field):
    receipt = successful_receipt(math.ceil(1024 ** 3 / PAYLOAD_BYTES))
    del receipt[field]
    path = tmp_path / "preflight.json"
    preserve_receipt(path, receipt)
    with pytest.raises(ValueError):
        require_previous_sizes(2 * 1024 ** 3, [path])


def test_modified_admission_report_is_rejected_even_when_passed_flag_is_intact(tmp_path):
    receipt = successful_receipt(math.ceil(1024 ** 3 / PAYLOAD_BYTES))
    path = tmp_path / "preflight.json"
    preserve_receipt(path, receipt)
    receipt["diagnostic_exception_logging"] = True
    path.write_text(json.dumps(receipt))
    with pytest.raises(ValueError, match="checksum mismatch"):
        require_previous_sizes(2 * 1024 ** 3, [path])


@pytest.mark.parametrize("counts", [{}, {"flushesStarted": 3, "flushesCompleted": 2},
                                  {"flushesStarted": -1, "flushesCompleted": -1}])
def test_missing_or_pending_flush_evidence_cannot_qualify_quiescence(tmp_path, counts):
    receipt = successful_receipt(math.ceil(1024 ** 3 / PAYLOAD_BYTES))
    receipt["postread_quiescence"]["backgroundCompaction"] = dict(state="IDLE", failed=0, **counts)
    path = tmp_path / "preflight.json"
    preserve_receipt(path, receipt)
    with pytest.raises(ValueError, match="quiescence"):
        require_previous_sizes(2 * 1024 ** 3, [path])


def test_exception_evidence_distinguishes_heap_failure_from_filesystem_noise(tmp_path):
    path = tmp_path / "exceptions.log"
    assert not exception_evidence(path)["java_heap_exhaustion_observed"]
    path.write_text("java/nio/file/AccessDeniedException\n")
    assert not exception_evidence(path)["java_heap_exhaustion_observed"]
    path.write_text("java/lang/OutOfMemoryError: Java heap space\n"
                    "'verify' '()V' in 'io/aetherdb/sstable/SSTableReader'\n")
    assert all(exception_evidence(path).values())


def test_mmap_diagnostic_reopens_consumes_all_bytes_and_cleans_scratch(tmp_path):
    from h4.mmap_storage_preflight import run
    scratch = tmp_path / "scratch"
    scratch.mkdir()
    output = tmp_path / "reports"
    assert run(output, scratch, 32)
    receipt = json.loads((output / "preflight.json").read_text())
    assert receipt["reopen_validation"]["validated_samples"] == 32
    assert receipt["shuffled_read"]["logical_bytes_consumed"] == 32 * PAYLOAD_BYTES
    assert not receipt["primary_h4_eligible"]
    assert not receipt["out_of_core_verified"]
    assert receipt["scratch_cleanup_completed"]
    assert list(scratch.iterdir()) == []
