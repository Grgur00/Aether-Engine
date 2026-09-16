"""Focused timing regression source; no daemon or real socket required."""
import io
import json
import struct
import threading
from types import SimpleNamespace

import pytest

from aether_training_cache import batch_values
from aether_training_cache import client as module


KEY = module.CacheKey("timing", "sample", module.TransformationFingerprint.from_descriptor("timing"))


def frame(payload):
    return struct.pack(">IBI", 5 + len(payload), 1, len(payload)) + payload


def packed():
    return struct.pack(">IBII", 1, 1, 0, 3) + b"abc"


class Connection:
    def __init__(self, payload, advance=lambda ns: None):
        self.response = io.BytesIO(payload)
        self.advance = advance
        self.sent = []
        self.reads = 0
        self.closed = False

    def sendall(self, value):
        self.advance(11)
        self.sent.append(value)

    def recv(self, size):
        self.advance(7)
        self.reads += 1
        return self.response.read(min(size, 2))

    def close(self):
        self.closed = True


def test_sink_runs_after_decode_and_preserves_backing_buffer(monkeypatch):
    now = [0]
    def advance(ns):
        now[0] += ns
    monkeypatch.setattr(module.time, "perf_counter_ns", lambda: now[0])
    traces = []
    decoded = []
    original = batch_values.InlineValueBatch
    def decode(*args):
        assert traces == []
        advance(23)
        result = original(*args)
        decoded.append(result)
        return result
    monkeypatch.setattr(batch_values, "InlineValueBatch", decode)
    def sink(record):
        assert len(decoded) == 1
        traces.append(record)
    connection = Connection(frame(packed()), advance)
    original_pack = struct.pack
    def timed_pack(*args):
        advance(5)
        return original_pack(*args)
    monkeypatch.setattr(module.struct, "pack", timed_pack)
    client = module.AetherTrainingCache(trace_sink=sink)
    client._connection = connection
    result = client.get_many_values([KEY])
    assert result.value(0) == b"abc"
    assert result.buffer.obj == packed()
    assert isinstance(result.buffer.obj, bytes)
    assert result.value(0).obj is result.buffer.obj
    assert len(traces) == 1
    trace = traces[0]
    assert trace["socketSendNs"] == 11
    assert trace["socketWaitReceiveNs"] == connection.reads * 7
    assert trace["responseBatchDecodeNs"] == 23
    assert trace["responseDecodeNs"] == sum(trace[name] for name in (
        "responseHeaderDecodeNs", "responseEnvelopeDecodeNs", "responseBatchDecodeNs"))
    assert trace["requestEncodeNs"] == trace["requestBodyEncodeNs"] + trace["requestFrameEncodeNs"]
    assert trace["requestBodyEncodeNs"] == 15
    assert trace["requestFrameEncodeNs"] == 5
    assert trace["durationNs"] == 20 + 11 + connection.reads * 7 + 23
    assert trace["outcome"] == "complete"


def test_batch_decode_error_is_logged_once_without_retry():
    traces = []
    client = module.AetherTrainingCache(trace_sink=traces.append)
    connection = Connection(frame(struct.pack(">I", 2)))
    client._connection = connection
    with pytest.raises(OSError, match="count mismatch"):
        client.get_many_values([KEY])
    assert len(connection.sent) == len(traces) == 1
    assert traces[0]["outcome"] == "error"
    assert traces[0]["errorType"] == "OSError"
    assert traces[0]["responseBatchDecodeNs"] >= 0


def test_retries_accumulate_send_receive_time_in_one_record(monkeypatch):
    now = [0]
    def advance(ns):
        now[0] += ns
    monkeypatch.setattr(module.time, "perf_counter_ns", lambda: now[0])
    traces = []
    client = module.AetherTrainingCache(trace_sink=traces.append)
    broken = Connection(b"", advance)
    healthy = Connection(frame(packed()), advance)
    client._connection = broken
    monkeypatch.setattr(client, "_connect", lambda: setattr(client, "_connection", healthy))
    assert client.get_many_values([KEY]).value(0) == b"abc"
    assert broken.closed
    assert client.requests_sent == 2
    assert len(traces) == 1
    assert traces[0]["socketSendNs"] == 22
    assert traces[0]["socketWaitReceiveNs"] == (broken.reads + healthy.reads) * 7
    assert [e["attempt"] for e in traces[0]["events"] if e["stage"] == "attempt_start"] == [0, 1]


def test_disabled_batch_tracing_uses_no_clock_or_uuid(monkeypatch):
    def unexpected():
        raise AssertionError("disabled instrumentation executed")
    monkeypatch.setattr(module.time, "perf_counter_ns", unexpected)
    monkeypatch.setattr(module.uuid, "uuid4", unexpected)
    client = module.AetherTrainingCache()
    client._connection = Connection(frame(packed()))
    assert client.get_many_values([KEY]).value(0) == b"abc"


def test_exhausted_transport_retries_emit_one_error(monkeypatch):
    traces = []
    client = module.AetherTrainingCache(trace_sink=traces.append)
    client._connection = Connection(b"")
    monkeypatch.setattr(client, "_connect", lambda: setattr(client, "_connection", Connection(b"")))
    with pytest.raises(EOFError):
        client.get_many_values([KEY])
    assert client.requests_sent == 2
    assert len(traces) == 1
    assert traces[0]["errorType"] == "EOFError"
    assert traces[0]["outcome"] == "error"
    assert traces[0]["responseBatchDecodeNs"] == 0


def test_batch_sink_failure_does_not_retry_or_mask_decode():
    def fail(record):
        raise OSError("sink unavailable")
    client = module.AetherTrainingCache(trace_sink=fail)
    connection = Connection(frame(packed()))
    client._connection = connection
    assert client.get_many_values([KEY]).value(0) == b"abc"
    assert len(connection.sent) == 1
    assert client.trace_errors == 1


def test_batch_server_identity_and_completed_trace_drain(monkeypatch):
    trace_id = "a" * 32
    monkeypatch.setattr(module.uuid, "uuid4", lambda: SimpleNamespace(hex=trace_id))
    server = {"traceId": trace_id, "serverDurationNs": 100, "stagesNs": {}, "flushes": []}
    metadata = json.dumps(server).encode()
    traces = []
    client = module.AetherTrainingCache(trace_sink=traces.append, server_trace=True)
    connection = Connection(frame(struct.pack(">I", len(metadata)) + metadata + packed()))
    client._connection = connection
    assert client.get_many_values([KEY]).value(0) == b"abc"
    assert len(traces) == 1
    assert traces[0]["traceId"] == trace_id
    assert traces[0]["serverCorrelated"]
    assert next(e["server"] for e in traces[0]["events"] if e["stage"] == "server_trace") == server
    assert connection.sent[0][4:22] == bytes([2, 5]) + bytes.fromhex(trace_id)

    completed = {"records": [{"traceId": trace_id, "stagesNs": {"responseWriteNs": 10},
                              "totalServerNs": 110}], "dropped": 0}
    connection = Connection(frame(struct.pack(">I", len(metadata)) + metadata + json.dumps(completed).encode()))
    client._connection = connection
    assert client.drain_completed_server_traces() == completed
    assert connection.sent[0][4:6] == bytes([2, 9])
    assert len(traces) == 2
    assert traces[-1]["operation"] == 9


def test_cancellation_interrupts_receive_without_request_lock_or_retry(monkeypatch):
    entered = threading.Event()
    released = threading.Event()
    shutdowns = []
    class BlockingConnection(Connection):
        def recv(self, size):
            entered.set()
            if not released.wait(2):
                raise TimeoutError("fixture receive timed out")
            return b""

        def shutdown(self, how):
            shutdowns.append(how)
            released.set()

    traces = []
    errors = []
    reconnects = []
    client = module.AetherTrainingCache(trace_sink=traces.append)
    connection = BlockingConnection(b"")
    client._connection = connection
    monkeypatch.setattr(client, "_connect", lambda: reconnects.append(True))
    def request():
        try:
            client.get_many_values([KEY])
        except Exception as error:
            errors.append(error)
    worker = threading.Thread(target=request, daemon=True)
    canceller = threading.Thread(target=client.cancel_pending_requests, daemon=True)
    worker.start()
    try:
        assert entered.wait(1)
        canceller.start()
        canceller.join(1)
        assert not canceller.is_alive(), "cancellation waited for the request lock"
        worker.join(1)
        assert not worker.is_alive()
        assert shutdowns == [module.socket.SHUT_RDWR]
        assert len(errors) == 1 and isinstance(errors[0], RuntimeError)
        assert "cancelled" in str(errors[0])
        assert reconnects == []
        assert client.requests_sent == 1
        assert connection.closed
        assert len(traces) == 1
        assert traces[0]["errorType"] == "RuntimeError"
    finally:
        released.set()
        worker.join(2)
        if canceller.ident is not None:
            canceller.join(2)
        client.close()


def test_cancellation_is_permanent_even_after_close(monkeypatch):
    client = module.AetherTrainingCache()
    def unexpected():
        raise AssertionError("cancelled client attempted to connect")
    monkeypatch.setattr(client, "_connect", unexpected)
    client.cancel_pending_requests()
    client.cancel_pending_requests()
    client.close()
    with pytest.raises(RuntimeError, match="cancelled"):
        client.get(KEY)


def test_cancellation_during_connect_closes_late_socket_without_tls(monkeypatch):
    raw = Connection(b"")
    options = []
    raw.setsockopt = lambda *args: options.append(args)
    handshakes = []
    context = SimpleNamespace(wrap_socket=lambda *args, **kwargs: handshakes.append(True))
    client = module.AetherTrainingCache(ssl_context=context)
    connects = []
    def connect(address, timeout):
        connects.append((address, timeout))
        client.cancel_pending_requests()
        return raw
    monkeypatch.setattr(module.socket, "create_connection", connect)
    with pytest.raises(RuntimeError, match="cancelled"):
        client.get(KEY)
    assert raw.closed
    assert len(connects) == 1
    assert handshakes == []
    assert raw.sent == []
    assert options == [(module.socket.IPPROTO_TCP, module.socket.TCP_NODELAY, 1)]


def test_cancellation_during_tls_closes_late_wrapped_socket(monkeypatch):
    raw = Connection(b"")
    raw.setsockopt = lambda *args: None
    wrapped = Connection(b"")
    client = module.AetherTrainingCache()
    def wrap_socket(*args, **kwargs):
        client.cancel_pending_requests()
        return wrapped
    client._ssl_context = SimpleNamespace(wrap_socket=wrap_socket)
    monkeypatch.setattr(module.socket, "create_connection", lambda *args: raw)
    with pytest.raises(RuntimeError, match="cancelled"):
        client.get(KEY)
    assert wrapped.closed
    assert wrapped.sent == []
    assert client._connection is None


def test_cancel_tolerates_already_closed_socket():
    client = module.AetherTrainingCache()
    connection = Connection(b"")
    def shutdown(how):
        raise OSError("already closed")
    connection.shutdown = shutdown
    client._connection = connection
    client.cancel_pending_requests()
    client.close()
    with pytest.raises(RuntimeError, match="cancelled"):
        client.get(KEY)


def test_normal_close_still_allows_reconnect(monkeypatch):
    client = module.AetherTrainingCache()
    original = Connection(b"")
    client._connection = original
    client.close()
    replacement = Connection(frame(b"abc"))
    monkeypatch.setattr(client, "_connect", lambda: setattr(client, "_connection", replacement))
    assert client.get(KEY) == b"abc"
    assert original.closed
    assert not replacement.closed
