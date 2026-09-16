import hashlib
import socket
import ssl
import struct
import mmap
import threading
import time
import uuid
import json
from collections import OrderedDict
from dataclasses import dataclass
from typing import Callable, Iterable


@dataclass(frozen=True)
class TransformationFingerprint:
    digest: bytes

    @classmethod
    def from_descriptor(cls, descriptor: str) -> "TransformationFingerprint":
        return cls(hashlib.sha256(descriptor.encode("utf-8")).digest())

    @classmethod
    def from_mapping(cls, values: dict) -> "TransformationFingerprint":
        canonical = "".join(f"{len(k)}:{k}{len(str(values[k]))}:{values[k]};" for k in sorted(values))
        return cls.from_descriptor(canonical)

    def __post_init__(self):
        if len(self.digest) != 32:
            raise ValueError("fingerprint must be 32 bytes")


@dataclass(frozen=True)
class CacheKey:
    namespace: str
    sample_id: str
    transform: TransformationFingerprint


@dataclass(frozen=True)
class SegmentReference:
    segment_id: str
    generation: int
    offset: int
    length: int
    checksum: bytes


class _BatchRequestTrace:
    """Request-local timing; phase totals include failed attempts and retries."""

    def __init__(self):
        self.trace_id = uuid.uuid4().hex
        self.events = []
        self.timings = dict.fromkeys((
            "requestBodyEncodeNs", "requestFrameEncodeNs", "socketSendNs",
            "socketWaitReceiveNs", "responseHeaderDecodeNs",
            "responseEnvelopeDecodeNs", "responseBatchDecodeNs"), 0)
        self.active_phase = None
        self.request_frame_bytes = 0
        self.mark("start")

    def mark(self, stage, **fields):
        self.events.append({"stage": stage, "monotonicNs": time.perf_counter_ns(), **fields})

    def begin(self, phase):
        self.active_phase = phase
        self.phase_started = time.perf_counter_ns()

    def end(self):
        if self.active_phase is not None:
            elapsed = time.perf_counter_ns() - self.phase_started
            self.timings[self.active_phase] += elapsed
            self.active_phase = None

    def finish(self, client, error_type):
        self.end()
        self.mark("complete")
        timings = self.timings
        record = {
            "schema": "aether-client-request-trace-v1", "traceId": self.trace_id,
            "operation": 5, "requestFrameBytes": self.request_frame_bytes,
            "outcome": "complete" if error_type is None else "error", "errorType": error_type,
            "durationNs": self.events[-1]["monotonicNs"] - self.events[0]["monotonicNs"],
            "events": self.events,
            "serverCorrelated": any(e["stage"] == "server_trace" for e in self.events),
            **timings,
            "requestEncodeNs": timings["requestBodyEncodeNs"] + timings["requestFrameEncodeNs"],
            "responseDecodeNs": (timings["responseHeaderDecodeNs"]
                                 + timings["responseEnvelopeDecodeNs"]
                                 + timings["responseBatchDecodeNs"]),
            "scope": "get_many_values body construction through packed batch decoding; includes lock and retries; "
                     "excludes key iterable materialization, artifact decoding, tensor materialization, and trace sink",
            "timingScope": "phase totals across all attempts; receive includes header and payload reads; "
                           "phase totals exclude connection setup, lock wait, and diagnostic bookkeeping",
        }
        try:
            client._trace_sink(record)
        except Exception:
            client.trace_errors += 1


class AetherTrainingCache:
    def __init__(self, host: str = "127.0.0.1", port: int = 9484, timeout: float = 30.0,
                 ssl_context: ssl.SSLContext | None = None, unix_socket: str | None = None,
                 trace_sink: Callable[[dict], None] | None = None, server_trace: bool = False):
        if server_trace and trace_sink is None:
            raise ValueError("server tracing requires a trace sink")
        self._address = (host, port)
        self._timeout = timeout
        self._ssl_context = ssl_context
        self._unix_socket = unix_socket
        self._connection = None
        self._lock = threading.RLock()
        self._requests_cancelled = threading.Event()
        self.connections_opened = 0
        self.requests_sent = 0
        self.operation_counts = {}
        self._trace_sink = trace_sink
        self._server_trace = server_trace
        self.trace_errors = 0

    def __enter__(self) -> "AetherTrainingCache":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.close()

    def _connect(self):
        self._raise_if_cancelled()
        raw = None
        try:
            if self._unix_socket:
                raw = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                raw.settimeout(self._timeout)
                raw.connect(self._unix_socket)
            else:
                raw = socket.create_connection(self._address, self._timeout)
                raw.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            # Cancellation during connect must not start a TLS handshake or retry.
            self._raise_if_cancelled()
            self._connection = (self._ssl_context.wrap_socket(raw, server_hostname=self._address[0])
                                if self._ssl_context else raw)
            self.connections_opened += 1
            # Publish before checking: cancellation either sees this socket or
            # this check closes a connection established after cancellation.
            self._raise_if_cancelled()
        except BaseException:
            if self._requests_cancelled.is_set():
                self._close_connection()
                if raw is not None:
                    raw.close()
            raise

    def _raise_if_cancelled(self):
        if self._requests_cancelled.is_set():
            raise RuntimeError("training cache client requests have been cancelled")

    def cancel_pending_requests(self) -> None:
        """Irreversibly abort this client, for early prefetch shutdown.

        Interrupt socket I/O without waiting for the request lock. An in-progress
        connect/TLS handshake may still take the configured socket timeout (30s
        by default), but cancellation prevents retries and future requests.
        Call before joining an aborted worker, then close its owned store.
        Normal epoch completion should retain the persistent connection instead.
        """
        self._requests_cancelled.set()
        connection = self._connection
        if connection is not None:
            try:
                connection.shutdown(socket.SHUT_RDWR)
            except OSError:
                # The request thread or normal close may already have closed it.
                pass

    def _round_trip(self, body, *, _deferred_trace=None):
        if _deferred_trace is not None:
            # The caller owns completion, including failures after the exchange.
            trace = _deferred_trace
            trace.request_frame_bytes = len(body) + 4 + (16 if self._server_trace else 0)
            return self._exchange(body, trace.mark,
                                  trace.trace_id if self._server_trace else None, timing=trace)
        if self._trace_sink is None:
            return self._exchange(body)
        trace_id = uuid.uuid4().hex
        events = []
        def mark(stage, **fields):
            events.append({"stage": stage, "monotonicNs": time.perf_counter_ns(), **fields})
        mark("start")
        outcome = "error"
        error_type = None
        try:
            result = self._exchange(body, mark, trace_id if self._server_trace else None)
            outcome = "complete"
            return result
        except Exception as error:
            error_type = type(error).__name__
            raise
        finally:
            mark("complete")
            record = {"schema": "aether-client-request-trace-v1", "traceId": trace_id,
                      "operation": body[1] if len(body) > 1 else -1,
                      "requestFrameBytes": len(body) + 4 + (16 if self._server_trace else 0), "outcome": outcome, "errorType": error_type,
                      "durationNs": events[-1]["monotonicNs"] - events[0]["monotonicNs"],
                      "events": events, "serverCorrelated": any(e["stage"] == "server_trace" for e in events),
                      "scope": "client round trip including lock and retries; excludes body construction, result decoding, and trace sink"}
            try:
                self._trace_sink(record)
            except Exception:
                # A failed diagnostic sink must never trigger a write retry.
                self.trace_errors += 1

    def _exchange(self, body, mark=None, trace_id=None, timing=None):
        self._raise_if_cancelled()
        with self._lock:
            if mark: mark("lock_acquired")
            for attempt in range(2):
                self._raise_if_cancelled()
                if mark: mark("attempt_start", attempt=attempt)
                try:
                    if self._connection is None:
                        self._connect()
                    self._raise_if_cancelled()
                    if mark: mark("connection_ready")
                    if timing: timing.begin("requestFrameEncodeNs")
                    wire_body = bytes([2, body[1]]) + bytes.fromhex(trace_id) + body[2:] if trace_id else body
                    frame = struct.pack(">I", len(wire_body)) + wire_body
                    if timing: timing.end()
                    if mark: mark("frame_ready")
                    self._raise_if_cancelled()
                    if timing: timing.begin("socketSendNs")
                    self._connection.sendall(frame)
                    if timing: timing.end()
                    if mark: mark("send_complete")
                    self.requests_sent += 1
                    operation = body[1] if len(body) > 1 else -1
                    self.operation_counts[operation] = self.operation_counts.get(operation, 0) + 1
                    if timing: timing.begin("socketWaitReceiveNs")
                    header = _read_exact(self._connection, 9)
                    if timing: timing.end()
                    if mark: mark("header_received", responseHeaderBytes=len(header))
                    if timing: timing.begin("responseHeaderDecodeNs")
                    frame_size, status, value_size = struct.unpack(">IBI", header)
                    if frame_size != 1 + 4 + value_size:
                        raise IOError("invalid daemon response")
                    if timing: timing.end()
                    if mark: mark("header_validated", status=status, responsePayloadBytes=value_size)
                    if timing: timing.begin("socketWaitReceiveNs")
                    response = _read_exact(self._connection, value_size)
                    if timing: timing.end()
                    if mark: mark("payload_received")
                    if timing: timing.begin("responseEnvelopeDecodeNs")
                    if trace_id:
                        if len(response) < 4:
                            raise ValueError("missing server trace envelope")
                        metadata_size = struct.unpack(">I", response[:4])[0]
                        if metadata_size > 65536 or metadata_size > len(response) - 4:
                            raise ValueError("invalid server trace envelope size")
                        server = json.loads(response[4:4 + metadata_size])
                        if server.get("traceId") != trace_id:
                            raise ValueError("server trace ID mismatch")
                        duration = server.get("serverDurationNs")
                        stages = server.get("stagesNs")
                        if (type(duration) is not int or duration < 0 or not isinstance(stages, dict)
                                or any(type(value) is not int or value < 0 or value > duration for value in stages.values())):
                            raise ValueError("invalid server trace durations")
                        flushes = server.get("flushes")
                        counters = ("walPositionBytes", "walSegmentLimitBytes", "logicalWalWriteBytes",
                                    "memtableEntryCount", "memtableNativeRemainingBytes",
                                    "memtableNativeLimitBytes", "memtableNativeUsedBytes", "requiredNativeBytes")
                        if (not isinstance(flushes, list)
                                or any(not isinstance(flush, dict)
                                    or flush.get("cause") not in {"MEMTABLE_CAPACITY", "WAL_SEGMENT", "OTHER"}
                                    or type(flush.get("completed")) is not bool
                                    or type(flush.get("totalNs")) is not int
                                    or not 0 <= flush["totalNs"] <= duration
                                    or not isinstance(flush.get("stagesNs"), dict)
                                    or any(type(value) is not int or value < 0 or value > flush["totalNs"]
                                        for value in flush["stagesNs"].values())
                                    or any(type(flush.get(counter)) is not int
                                        or flush[counter] < (-1 if counter == "logicalWalWriteBytes" else 0)
                                        for counter in counters)
                                    for flush in flushes)):
                            raise ValueError("invalid server flush diagnostics")
                        response = response[4 + metadata_size:]
                        if timing: timing.end()
                        if mark: mark("server_trace", server=server)
                        if timing: timing.begin("responseEnvelopeDecodeNs")
                    self._raise_if_cancelled()
                    if status == 0:
                        return None
                    if status != 1:
                        raise IOError("training cache daemon rejected request")
                    return response
                except (ConnectionError, EOFError, OSError) as error:
                    if timing: timing.end()
                    if mark: mark("attempt_failed", errorType=type(error).__name__)
                    self._close_connection()
                    self._raise_if_cancelled()
                    if attempt == 1:
                        raise
                finally:
                    if timing: timing.end()
                    if self._requests_cancelled.is_set():
                        self._close_connection()
            raise ConnectionError("cache request failed")

    def _close_connection(self):
        if self._connection is not None:
            try:
                self._connection.close()
            finally:
                self._connection = None

    def close(self):
        with self._lock:
            self._close_connection()

    def protocol_metrics(self):
        return {"connectionsOpened": self.connections_opened, "requestsSent": self.requests_sent,
                "operationCounts": dict(self.operation_counts)}

    def _request(self, operation: int, key: CacheKey, value: bytes = b"") -> bytes | None:
        namespace = key.namespace.encode("utf-8")
        sample = key.sample_id.encode("utf-8")
        body = bytes([1, operation]) + struct.pack(">I", len(namespace)) + namespace
        body += struct.pack(">I", len(sample)) + sample + key.transform.digest
        if operation == 2:
            body += struct.pack(">I", len(value)) + value
        return self._round_trip(body)

    def _reference(self, key: CacheKey) -> SegmentReference | None:
        response = self._request(3, key)
        if response is None:
            return None
        size = struct.unpack(">I", response[:4])[0]
        if size + 4 + 8 + 8 + 4 + 32 != len(response):
            raise IOError("invalid segment reference")
        start = 4
        segment = response[start:start + size].decode("ascii")
        start += size
        generation, offset, length = struct.unpack(">QQI", response[start:start + 20])
        checksum = response[start + 20:start + 52]
        return SegmentReference(segment, generation, offset, length, checksum)

    def get(self, key: CacheKey) -> bytes | None:
        return self._request(1, key)

    def contains_many(self, keys: Iterable[CacheKey]) -> set[CacheKey]:
        keys = list(keys)
        if len(keys) > 4096:
            raise ValueError("presence batch is limited to 4096 keys")
        body = bytearray(bytes([1, 8]) + struct.pack(">I", len(keys)))
        for key in keys:
            namespace, sample = key.namespace.encode("utf-8"), key.sample_id.encode("utf-8")
            body.extend(struct.pack(">I", len(namespace)) + namespace)
            body.extend(struct.pack(">I", len(sample)) + sample + key.transform.digest)
        response = self._round_trip(body)
        if response is None or len(response) != 4 + len(keys) or struct.unpack(">I", response[:4])[0] != len(keys):
            raise IOError("invalid presence response")
        if any(value not in (0, 1) for value in response[4:]):
            raise IOError("invalid presence status")
        return {key for key, present in zip(keys, response[4:]) if present}

    def get_ref(self, key: CacheKey) -> SegmentReference | None:
        return self._reference(key)

    def get_many_refs(self, keys: Iterable[CacheKey]) -> dict[CacheKey, SegmentReference]:
        keys = list(keys)
        if not keys:
            return {}
        if len(keys) > 4096:
            raise ValueError("reference batch is limited to 4096 keys")
        body = bytes([1, 4]) + struct.pack(">I", len(keys))
        for key in keys:
            namespace = key.namespace.encode("utf-8")
            sample = key.sample_id.encode("utf-8")
            body += struct.pack(">I", len(namespace)) + namespace
            body += struct.pack(">I", len(sample)) + sample + key.transform.digest
        response = self._round_trip(body)
        if response is None:
            raise IOError("invalid batch reference response")
        count = struct.unpack_from(">I", response)[0]
        if count != len(keys):
            raise IOError("batch reference count mismatch")
        cursor = 4
        result = {}
        for key in keys:
            present = response[cursor]
            cursor += 1
            if present:
                size = struct.unpack(">I", response[cursor:cursor + 4])[0]
                cursor += 4
                segment = response[cursor:cursor + size].decode("ascii")
                cursor += size
                generation, offset, length = struct.unpack(">QQI", response[cursor:cursor + 20])
                cursor += 20
                checksum = response[cursor:cursor + 32]
                cursor += 32
                result[key] = SegmentReference(segment, generation, offset, length, checksum)
        if cursor != len(response):
            raise IOError("trailing batch reference bytes")
        return result

    def get_view_batch(self, keys: Iterable[CacheKey], mapped_segments):
        from .batch import BatchTiming, ReferenceBatch
        keys = list(keys)
        started = __import__("time").perf_counter_ns()
        references = self.get_many_refs(keys)
        reference_nanos = __import__("time").perf_counter_ns() - started
        groups = ReferenceBatch(keys, references).segment_groups
        acquire_nanos = 0
        slice_nanos = 0
        views = {}
        for group_keys in groups.values():
            for key in group_keys:
                reference = references[key]
                slice_started = __import__("time").perf_counter_ns()
                views[key] = mapped_segments.view(reference)
                slice_nanos += __import__("time").perf_counter_ns() - slice_started
        total_nanos = __import__("time").perf_counter_ns() - started
        timing = BatchTiming(total_nanos, acquire_nanos, slice_nanos,
                             total_nanos - reference_nanos - slice_nanos,
                             len(views), len(groups))
        return views, timing

    def get_view(self, key: CacheKey, mapped_segments: "MappedSegmentRegistry"):
        reference = self.get_ref(key)
        return None if reference is None else mapped_segments.view(reference)

    def get_many_views(self, keys: Iterable[CacheKey], mapped_segments: "MappedSegmentRegistry"):
        references = self.get_many_refs(keys)
        return {key: mapped_segments.view(reference) for key, reference in references.items()}

    def get_numpy(self, key: CacheKey, mapped_segments: "MappedSegmentRegistry", dtype="int32", shape=None):
        from .tensor import numpy_array
        view = mapped_segments.cache_view(self.get_ref(key))
        return numpy_array(view, dtype=dtype, shape=shape), view

    def get_tensor(self, key: CacheKey, mapped_segments: "MappedSegmentRegistry", dtype="int32", shape=None):
        from .tensor import torch_tensor
        view = mapped_segments.cache_view(self.get_ref(key))
        return torch_tensor(view, dtype=dtype, shape=shape), view

    def engine_info(self) -> dict:
        """Read engine identity and background-compaction diagnostics outside measured work."""
        return json.loads(self._round_trip(bytes([1, 7])))

    def drain_completed_server_traces(self) -> dict:
        """Drain completed server timing JSON (opcode 9) outside measured work.

        Returns {"records": [{"traceId": ..., "stagesNs": ..., "totalServerNs": ...}],
        "dropped": ...} unchanged for traceId reconciliation.
        """
        return json.loads(self._round_trip(bytes([1, 9])))

    def wait_for_background_compaction(self, timeout=60.0) -> dict:
        """Drain background work between measured regions; report the additional wall time."""
        started = time.monotonic()
        while True:
            diagnostics = self.engine_info().get("backgroundCompaction", {})
            drained = diagnostics.get("state") not in {"RUNNING", "STOPPING"}
            if drained or time.monotonic() - started >= timeout:
                return {"drainWallMs": (time.monotonic() - started) * 1000,
                        "drained": drained, "backgroundCompaction": diagnostics}
            time.sleep(.1)

    def put(self, key: CacheKey, value: bytes) -> None:
        self._request(2, key, bytes(value))

    def put_many(self, values):
        values = list(values)
        if len(values) > 4096:
            raise ValueError("put batch is limited to 4096 keys")
        body = bytes([1, 6]) + struct.pack(">I", len(values))
        for key, value in values:
            namespace = key.namespace.encode("utf-8")
            sample = key.sample_id.encode("utf-8")
            value = bytes(value)
            body += struct.pack(">I", len(namespace)) + namespace
            body += struct.pack(">I", len(sample)) + sample + key.transform.digest
            body += struct.pack(">I", len(value)) + value
        self._round_trip(body)

    def get_many(self, keys: Iterable[CacheKey]) -> dict[CacheKey, bytes]:
        keys = list(keys)
        if not keys:
            return {}
        if len(keys) > 4096:
            raise ValueError("byte batch is limited to 4096 keys")
        packed = self.get_many_values(keys)
        return {key: bytes(value) for index, key in enumerate(keys)
            if (value := packed.value(index)) is not None}

    def get_many_values(self, keys: Iterable[CacheKey]):
        """Fetch packed views; an optional trace sink fires after batch decoding.

        requestEncodeNs sums requestBodyEncodeNs (key body) and
        requestFrameEncodeNs (wire framing, including any server trace ID).
        responseDecodeNs sums header, trace envelope, and packed batch parsing.
        Timings accumulate retries and exclude artifact/tensor reconstruction.
        """
        from .batch_values import InlineValueBatch
        keys = list(keys)
        if not keys:
            return InlineValueBatch(memoryview(b""), [], [], [])
        if len(keys) > 4096:
            raise ValueError("byte batch is limited to 4096 keys")
        trace = _BatchRequestTrace() if self._trace_sink is not None else None
        error_type = None
        try:
            if trace: trace.begin("requestBodyEncodeNs")
            body = bytes([1, 5]) + struct.pack(">I", len(keys))
            for key in keys:
                namespace = key.namespace.encode("utf-8")
                sample = key.sample_id.encode("utf-8")
                body += struct.pack(">I", len(namespace)) + namespace
                body += struct.pack(">I", len(sample)) + sample + key.transform.digest
            if trace:
                trace.end()
                response = self._round_trip(body, _deferred_trace=trace)
                trace.begin("responseBatchDecodeNs")
            else:
                response = self._round_trip(body)
            if response is None:
                raise IOError("invalid packed value response")
            count = struct.unpack_from(">I", response)[0]
            if count != len(keys):
                raise IOError("packed value count mismatch")
            cursor = 4
            status_codes = response[cursor:cursor + count]
            cursor += count
            offsets = list(struct.unpack_from(f">{count}I", response, cursor))
            cursor += count * 4
            lengths = list(struct.unpack_from(f">{count}I", response, cursor))
            cursor += count * 4
            buffer = memoryview(response)[cursor:]
            statuses = ["HIT_INLINE" if code == 1 else "MISS" for code in status_codes]
            result = InlineValueBatch(buffer, offsets, lengths, statuses)
            if trace: trace.end()
            return result
        except BaseException as error:
            if trace: trace.end()
            error_type = type(error).__name__
            raise
        finally:
            if trace: trace.finish(self, error_type)

    def get_or_compute(self, key: CacheKey, compute: Callable[[], bytes]) -> bytes:
        value = self.get(key)
        if value is None:
            value = bytes(compute())
            self.put(key, value)
        return value


class MappedSegmentRegistry:
    def __init__(self, segment_directory: str, maximum_open: int = 32):
        self._directory = segment_directory
        self._maximum_open = maximum_open
        self._maps = OrderedDict()
        self._retired = []

    def __enter__(self) -> "MappedSegmentRegistry":
        return self

    def __exit__(self, exc_type, exc_value, traceback) -> None:
        self.close()

    def view(self, reference: SegmentReference) -> memoryview:
        mapped = self._maps.pop(reference.segment_id, None)
        if mapped is None:
            path = f"{self._directory}/{reference.segment_id}"
            handle = open(path, "rb")
            mapped = (handle, mmap.mmap(handle.fileno(), 0, access=mmap.ACCESS_READ))
        self._maps[reference.segment_id] = mapped
        while len(self._maps) > self._maximum_open:
            _, (handle, old_map) = self._maps.popitem(last=False)
            self._retired.append((handle, old_map))
        view = memoryview(mapped[1])[reference.offset:reference.offset + reference.length]
        if hashlib.sha256(view).digest() != reference.checksum:
            raise IOError("mapped segment checksum mismatch")
        return view

    def cache_view(self, reference: SegmentReference):
        from .mapping import CacheView
        return CacheView(self.view(reference), self)

    def close(self):
        for handle, mapped in self._maps.values():
            try:
                mapped.close()
                handle.close()
            except BufferError:
                self._retired.append((handle, mapped))
        self._maps.clear()
        for handle, mapped in self._retired:
            try:
                mapped.close()
                handle.close()
            except BufferError:
                pass
        self._retired.clear()


def numpy_view(view: memoryview, dtype="int32", shape=None):
    import numpy as np
    result = np.frombuffer(view, dtype=dtype)
    return result.reshape(shape) if shape is not None else result


def torch_view(view: memoryview, dtype="int32", shape=None):
    import torch
    return torch.from_numpy(numpy_view(view, dtype=dtype, shape=shape))


def _read_exact(connection: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        chunk = connection.recv(size - len(chunks))
        if not chunk:
            raise EOFError("daemon closed connection")
        chunks.extend(chunk)
    return bytes(chunks)
