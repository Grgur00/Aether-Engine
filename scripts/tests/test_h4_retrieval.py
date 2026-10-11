import hashlib
import copy

import pytest

from h4.mmap_backend import MmapBackend
from h4.prepare_pcam_manifest import PAYLOAD_BYTES
from h4.retrieval import consume, measure_pattern, quantile, validate_order
from h4.validate_h4 import validate_retrieval


def samples():
    for index in range(33):
        payload = bytes([index]) * PAYLOAD_BYTES
        yield dict(sample_id=index, artifact_id=hashlib.sha256(str(index).encode()).hexdigest(),
                   artifact_sha256=hashlib.sha256(payload).hexdigest()), payload


def test_real_mmap_shared_workload_consumes_full_payload_in_frozen_order(tmp_path):
    records = list(samples())
    order = list(reversed(range(33)))
    with MmapBackend(tmp_path) as backend:
        backend.append(records)
        result = measure_pattern(backend, [row for row, _ in records], order,
                                 pattern="shuffled", latency_count=8)
    expected = hashlib.sha256(b"".join(records[index][1] for index in order)).hexdigest()
    assert result["correctness_passed"]
    assert not result["primary_h4_eligible"]
    assert not result["out_of_core_resource_evidence_verified"]
    assert [item["index"] for item in result["measured_passes"]] == [0, 1, 2]
    for item in result["measured_passes"]:
        assert item["consumer_sha256"] == expected
        assert item["logical_bytes_consumed"] == 33 * PAYLOAD_BYTES
        assert item["seconds"] > 0
        assert item["resource"]["physical_read_bytes"] is None
    assert len(result["latency"]["sample_seconds"]) == 8
    assert result["latency"]["p50"] <= result["latency"]["p95"] <= result["latency"]["p99"]
    rows = [row for row, _ in records]
    assert validate_retrieval(result, rows, order, "shuffled")["valid"]
    for field, value in (("samples", 34), ("primary_h4_eligible", True),
                         ("out_of_core_resource_evidence_verified", True)):
        bad = copy.deepcopy(result)
        bad[field] = value
        with pytest.raises(ValueError):
            validate_retrieval(bad, rows, order, "shuffled")
    bad = copy.deepcopy(result)
    bad["measured_passes"][1]["seconds"] *= 2
    with pytest.raises(ValueError, match="throughput"):
        validate_retrieval(bad, rows, order, "shuffled")
    bad = copy.deepcopy(result)
    bad["latency"]["p99"] *= 2
    with pytest.raises(ValueError, match="quantile"):
        validate_retrieval(bad, rows, order, "shuffled")


def test_manifest_hash_validation_is_outside_timing_and_fails_closed(tmp_path):
    records = list(samples())
    rows = [row for row, _ in records]
    rows[0] = dict(rows[0], artifact_sha256="0" * 64)
    with MmapBackend(tmp_path) as backend:
        backend.append(records)
        with pytest.raises(ValueError, match="expected sample"):
            measure_pattern(backend, rows, list(range(33)), pattern="sequential")


def test_order_rejects_duplicates_omissions_and_foreign_samples():
    rows = [row for row, _ in samples()]
    for order in (list(range(32)), list(range(32)) + [0], list(range(32)) + [999]):
        with pytest.raises(ValueError, match="exactly once"):
            validate_order(rows, order)
    assert quantile([1, 2, 3], .5) == 2
    assert quantile([], .99) is None
