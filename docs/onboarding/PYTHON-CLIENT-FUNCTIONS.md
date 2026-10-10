# Python Cache Client Connection Functions

[Function index](FUNCTION-INDEX.md) | [Value and batch APIs](PYTHON-CACHE-VALUE-FUNCTIONS.md) | [Java protocol](TRAINING-PROTOCOL-FUNCTIONS.md)

This reference follows the connection and trace functions in
[client.py](../../clients/python/aether_training_cache/client.py). The value API
and mapping references cover the other declarations in that file. This is the
low-level daemon client, not the separate Python filesystem store or a PyTorch
training loop. It connects lazily to an already-running service.

## Connection Architecture

| State | Responsibility |
| --- | --- |
| `_connection` | At most one stored socket, reused across requests |
| `_lock` | Reentrant lock serializes an entire exchange and both retry attempts |
| `_requests_cancelled` | Permanent abort event; cancellation does not wait for request lock |
| Endpoint/context | TCP address or Unix path plus optional caller-created SSLContext |
| Counters | Connection publications and completed sends, not successful operation totals |
| Trace sink | Optional diagnostic callback, isolated from ordinary request failure/retry |

Ordinary requests on one client do not multiplex: another thread waits for the
exchange lock. This class does not start the daemon, authenticate namespaces,
coordinate dataset versions, or provide inter-process shared socket ownership.
Other wrappers are responsible for worker/process lifecycle.

## Construction and Context Functions

| Function | Behavior |
| --- | --- |
| `AetherTrainingCache.__init__(host, port, timeout, ssl_context, unix_socket, trace_sink, server_trace)` | Stores endpoint/options, initializes lock/event/counters. Defaults are `127.0.0.1:9484`, 30-second socket timeout, no TLS/Unix/tracing. Rejects server tracing without a trace sink. Does not connect or validate all endpoint fields. |
| `__enter__()` | Returns the same client; still no connection until a request. |
| `__exit__(exc_type, exc_value, traceback)` | Calls `close`; no exception suppression return. |
| `close()` | Under request lock, closes stored connection; does not mark the client permanently closed. Later requests can reconnect unless cancelled. |
| `_close_connection()` | Closes stored socket if any and resets `_connection` in finally; does not acquire the lock itself. |

The client's default port differs from the TCP daemon's default port 0. A runner
using an ephemeral daemon must pass its actual printed port. Socket timeout is not
an overall request deadline: retries and multiple reads can extend total wall time.
`close()` can wait behind a blocked request; early-abort callers use cancellation
before waiting for a worker and closing its owned client.

## Establishing and Cancelling Connections

### _connect

`_connect()` first checks cancellation. With a Unix path it creates AF_UNIX socket,
sets timeout and connects. Otherwise it uses `socket.create_connection` and enables
TCP_NODELAY. A second cancellation check prevents a newly established raw socket
from starting TLS after abort.

With SSLContext it calls `wrap_socket` using configured host as `server_hostname`,
then publishes the resulting socket and increments `connections_opened`. A final
cancellation check handles cancellation during TLS or just before publication.
Certificate/hostname trust policy is supplied by the context, not constructed here.
Unix selection does not explicitly prohibit combining it with an SSLContext.

The exception handler catches BaseException but explicitly closes late sockets
only when cancellation is set. It is not a general finally-based cleanup of every
raw socket on arbitrary setup failure. A counter increment means a connection was
published, not that a subsequent request succeeded.

### _raise_if_cancelled and cancel_pending_requests

`_raise_if_cancelled()` raises RuntimeError when the event is set. There is no reset
API. `cancel_pending_requests()` irreversibly sets the event, reads the current
connection without acquiring the request lock and attempts `shutdown(SHUT_RDWR)`.
Already-closed-socket OSError is ignored. It does not itself join a worker or call
normal close; the request path/final cleanup closes the connection.

Cancellation checks before connect, send and after receive prevent new attempts
and future requests. An in-progress connect/TLS handshake may still last until its
socket timeout because the wrapped socket has not yet been published. Later checks
close that late socket. Empty local batch fast paths can return without entering
the exchange/cancellation check; permanent cancellation describes network requests,
not a universal guard on every method invocation.

## Exchange and Retry Functions

### _exchange

`_exchange(body, mark, trace_id, timing)` checks cancellation, takes `_lock` and
performs at most two attempts:

1. Connect if needed and check cancellation again.
2. For server tracing, replace the version/operation prefix with version 2,
   operation and 16 trace bytes; otherwise use supplied body unchanged.
3. Prefix body with four-byte unsigned length and `sendall`. After send completes,
   increment requests/operation counters.
4. Read exactly nine response-header bytes, unpack frame size/status/value size,
   require frame size = 5 + value size, then read the declared value bytes.
5. If traced, validate/strip the diagnostic envelope. Check cancellation, then
   return None for MISS, bytes for HIT, or raise IOError for other status.

ConnectionError, EOFError and OSError cause connection closure and at most one
retry, unless cancelled. In Python IOError is an alias of OSError, so invalid outer
framing and daemon ERROR status also take this retry path. It retries writes as
well as reads; a lost acknowledgement can replay a publication. Java immutable-key
checks make equal existing payloads idempotent, but this is not transaction-ID
deduplication or exactly-once dispatch. Retry uses the same correlation ID.

ValueError from trace-envelope validation is not retried and does not automatically
close a noncancelled connection here. Packed/reference/presence decoding performed
by public methods after `_exchange` is also outside transport retries, even if
that decoder raises IOError. Failed diagnostic sinks never enter this retry loop.

The client checks the header relation but imposes no maximum response value size
before `_read_exact`; the server's request-frame cap is not a client receive cap.
Sending bodies also has no shared 64 MiB preflight check here. Operation-specific
count limits do not bound total bytes. Finally ends any active timing phase and
closes the connection when cancellation is set.

### _read_exact

Module helper `_read_exact(connection, size)` repeatedly receives the remaining
count into a bytearray and returns immutable bytes. Empty recv before completion
raises EOFError. It handles fragmented reads but has no independent deadline/size
budget, relying on socket behavior and caller-supplied size. Zero size returns
empty bytes without reading. Accumulation and conversion allocate; this is not
network zero-copy receive.

## Trace Envelope Validation

Within `_exchange`, traced response value begins with four-byte metadata length.
It must fit remaining bytes and be <= 65536. JSON must carry matching trace ID,
nonnegative integer serverDurationNs and dictionary stages whose integer durations
are between zero and total duration. Boolean values are rejected by exact-type
integer checks.

Flush diagnostics must be a list of dictionaries with supported causes,
Boolean completed, bounded total/stage durations and required numeric counters.
`logicalWalWriteBytes` alone may use -1; other required counters are nonnegative.
This is validation of selected diagnostic fields, not a complete schema validator
for all read/write/table-finish/compaction data. A Java trace envelope larger than
64 KiB is rejected even though the Java encoder does not cap its metadata size.

After validation, the envelope is stripped and server metadata is added as an event.
These stages are inclusive on the Java side; client validation does not make them
additive or prove that measured costs are free of instrumentation overhead.

## Round-Trip Trace Functions

### _round_trip and nested mark

`_round_trip(body, _deferred_trace)` selects one of three paths:

- Deferred batch trace: record frame bytes and call `_exchange` with its `mark`,
  timing phases and optional server ID; caller owns final completion after decoding.
- No sink: directly call `_exchange`, without request timing clocks/UUID generation.
- Ordinary sink: create UUID/event list, use nested `mark(stage, **fields)` to
  record perf-counter timestamps, call `_exchange`, and emit one record in finally.

The ordinary trace covers round trip including lock wait/retries, but excludes body
construction, public-method result decoding and trace sink. It records operation,
frame bytes, outcome/error type, events and whether a server trace appeared. It
catches Exception for request error classification; BaseException paths still run
finally but need not receive equivalent classification. Sink Exception increments
`trace_errors` and is suppressed; no request replay results from it.

### _BatchRequestTrace

| Function | Behavior |
| --- | --- |
| `__init__()` | Creates UUID, event list, named phase totals, zero frame bytes and start event. Only created when batch tracing is enabled. |
| `mark(stage, **fields)` | Appends event with perf-counter timestamp and supplied fields. |
| `begin(phase)` | Sets active phase and its start time; no nested phase stack or name validation. |
| `end()` | Accumulates active phase into totals and clears it; no-op if none active. |
| `finish(client, error_type)` | Ends phase, records complete, emits one GET_MANY record with totals/outcome/scope, suppresses sink Exception while incrementing trace_errors. |

Batch timing covers body construction through packed-result decoding, including
lock/retries in total duration, but excludes key iterable materialization,
artifact/tensor reconstruction and sink. Phase totals accumulate retries and
exclude setup/lock wait/bookkeeping, so their sum need not equal total duration.
RequestEncodeNs = body + frame encoding; responseDecodeNs = header + envelope +
packed parsing. Send and receive phases include failed attempts. Sink callback
runs after packed decoding, unlike ordinary round-trip traces.

## Counter Snapshot and Tests

`protocol_metrics()` returns connectionsOpened, requestsSent and a copied
operationCounts dictionary. It does not take the request lock, so concurrent
sampling is not an atomic snapshot. Completed sends are counted even if response
or parsing later fails; retries can increase request count twice for one API call.

[test_request_tracing.py](../../clients/python/tests/test_request_tracing.py)
checks fragmented replies, retries, TCP_NODELAY, server correlation/invalid traces,
sink failure isolation and disabled instrumentation.
[test_batch_request_timing.py](../../clients/python/tests/test_batch_request_timing.py)
checks phase accumulation, post-decode sink ordering, cancellation during receive,
connect and TLS, permanent abort and normal-close reconnect. These use controlled
socket doubles; they do not prove live certificate policy or network timeout bounds.

Python syntax-tree checks inventory explicit functions across client references,
including nested `mark`. They do not inventory dataclass-generated methods or
establish correctness of every error/transport interleaving.
