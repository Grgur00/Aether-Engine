import io
import struct
import json
from types import SimpleNamespace

import pytest

from aether_training_cache.client import AetherTrainingCache, CacheKey, TransformationFingerprint


class FragmentedConnection:
    def __init__(self, response):
        self.response = io.BytesIO(response)
        self.sent = []
        self.closed = False

    def sendall(self, data):
        self.sent.append(data)

    def recv(self, size):
        return self.response.read(min(size, 2))

    def close(self):
        self.closed = True


def response(value=b"fixture", status=1):
    return struct.pack(">IBI", 5 + len(value), status, len(value)) + value


KEY = CacheKey("test", "sample", TransformationFingerprint.from_descriptor("test"))


def test_server_trace_envelope_preserves_response_and_identity(monkeypatch):
    import aether_training_cache.client as module
    trace_id = "a" * 32
    monkeypatch.setattr(module.uuid, "uuid4", lambda: SimpleNamespace(hex=trace_id))
    traces = []
    server = {"traceId": trace_id, "serverDurationNs": 100, "stagesNs": {"indexLookup": 20}}
    metadata = json.dumps(server).encode()
    connection = FragmentedConnection(response(struct.pack(">I", len(metadata)) + metadata + b"fixture"))
    client = AetherTrainingCache(trace_sink=traces.append, server_trace=True)
    client._connection = connection
    assert client.get(KEY) == b"fixture"
    assert connection.sent[0][4:22] == bytes([2, 1]) + bytes.fromhex(trace_id)
    assert traces[0]["serverCorrelated"]
    assert next(e["server"] for e in traces[0]["events"] if e["stage"] == "server_trace") == server


@pytest.mark.parametrize("metadata", [
    {"traceId": "wrong", "serverDurationNs": 1, "stagesNs": {}},
    {"traceId": "a" * 32, "serverDurationNs": -1, "stagesNs": {}},
    {"traceId": "a" * 32, "serverDurationNs": 1, "stagesNs": {"indexLookup": 2}},
])
def test_invalid_server_trace_does_not_replay_publication(monkeypatch, metadata):
    import aether_training_cache.client as module
    monkeypatch.setattr(module.uuid, "uuid4", lambda: SimpleNamespace(hex="a" * 32))
    encoded = json.dumps(metadata).encode()
    client = AetherTrainingCache(trace_sink=lambda trace: None, server_trace=True)
    connection = FragmentedConnection(response(struct.pack(">I", len(encoded)) + encoded))
    client._connection = connection
    with pytest.raises(ValueError):
        client.put(KEY, b"value")
    assert len(connection.sent) == 1


def test_trace_preserves_fragmented_response_and_records_retry(monkeypatch):
    traces = []
    broken = FragmentedConnection(b"")
    healthy = FragmentedConnection(response())
    client = AetherTrainingCache(trace_sink=traces.append)
    client._connection = broken
    monkeypatch.setattr(client, "_connect", lambda: setattr(client, "_connection", healthy))
    assert client.get(KEY) == b"fixture"
    assert broken.closed
    assert client.requests_sent == 2
    trace = traces[0]
    assert trace["outcome"] == "complete"
    assert trace["serverCorrelated"] is False
    events = trace["events"]
    assert [e["attempt"] for e in events if e["stage"] == "attempt_start"] == [0, 1]
    assert any(e["stage"] == "attempt_failed" for e in events)
    stamps = [e["monotonicNs"] for e in events]
    assert stamps == sorted(stamps)
    assert trace["durationNs"] == stamps[-1] - stamps[0]


def test_failed_trace_sink_never_retries_publication():
    def fail(trace):
        raise OSError("diagnostic disk unavailable")
    client = AetherTrainingCache(trace_sink=fail)
    connection = FragmentedConnection(response(b""))
    client._connection = connection
    client.put(KEY, b"payload")
    assert len(connection.sent) == 1
    assert client.trace_errors == 1


def test_disabled_trace_uses_no_timing_clock(monkeypatch):
    import aether_training_cache.client as module
    def unexpected():
        raise AssertionError("clock called with tracing disabled")
    monkeypatch.setattr(module.time, "perf_counter_ns", unexpected)
    client = AetherTrainingCache()
    client._connection = FragmentedConnection(response(b"", status=0))
    assert client.get(KEY) is None


def test_failed_request_is_recorded_without_masking_error(monkeypatch):
    traces = []
    client = AetherTrainingCache(trace_sink=traces.append)
    client._connection = FragmentedConnection(b"")
    monkeypatch.setattr(client, "_connect", lambda: setattr(client, "_connection", FragmentedConnection(b"")))
    with pytest.raises(EOFError):
        client.get(KEY)
    assert traces[0]["outcome"] == "error"
    assert traces[0]["errorType"] == "EOFError"


@pytest.mark.parametrize("kind", ["cpu", "cuda"])
def test_backend_report_uses_actual_device_for_transfer_label(kind):
    from benchmark_gpu_segmentation import summarize_backend
    context = SimpleNamespace(args=SimpleNamespace(batch_size=2, resize=8),
                              peak_memory_bytes=lambda: 0, populate_ms=lambda backend: 0,
                              checksums={"combinedChecksum": "fixture"})
    samples = {"samples": [], "status": "UNAVAILABLE", "source": None, "error": None, "intervalMs": 0}
    report = summarize_backend("fixture", [], [], 0, context, samples, {}, device=SimpleNamespace(type=kind))
    if kind == "cpu":
        assert "no accelerator transfer" in report["transferLabel"]
        assert "cuda" not in report["transferLabel"]
    else:
        assert "host-to-device" in report["transferLabel"]
