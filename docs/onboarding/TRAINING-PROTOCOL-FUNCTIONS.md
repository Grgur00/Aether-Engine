# Training Cache Protocol and Trace Functions

[Function index](FUNCTION-INDEX.md) | [Daemon lifecycle](TRAINING-DAEMON-FUNCTIONS.md) | [Cache operations](TRAINING-CACHE-FUNCTIONS.md)

This reference covers explicit declarations in `TrainingCacheProtocol`,
`TrainingCacheProtocolMetrics`, `TrainingCacheRequestTrace`, and `DiagnosticJson`.
The protocol is application-specific local cache access, not the general Java RPC
frame format. Version 2 adds correlated diagnostics to the same cache operations;
it does not change persistent artifact identity or storage envelopes.

## Frame Layout and Operations

Source: [TrainingCacheProtocol.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheProtocol.java).

Integers and longs use big-endian order. A request is a four-byte body length
followed by body: version byte, operation byte, optional 16 trace-ID bytes for
version 2, then operation arguments. Version 1 has no trace ID. Body length must
be 2..64 MiB; version-2-specific minimum structure is checked only by actual reads.

A key is namespace string, sample string and 32 fingerprint bytes. Each string
has four-byte UTF-8 byte length followed by bytes. PUT payloads have a four-byte
length followed by raw bytes. Batch operations begin their arguments with a count.

| Code | Operation | Argument and response semantics |
| --- | --- | --- |
| 1 | GET | One key; MISS or HIT with payload |
| 2 | PUT | One key/value; publication uses `putMany`; HIT with empty operation body |
| 3 | GET_REF | One key; MISS or HIT with segment-reference metadata |
| 4 | GET_MANY_REF | Count and keys; HIT with ordered optional references |
| 5 | GET_MANY | Count and keys; HIT with packed slot statuses/offsets/lengths/payloads |
| 6 | PUT_MANY | Count and key/value pairs; HIT with empty operation body |
| 7 | INFO | No operation arguments; HIT with UTF-8 JSON capabilities/identity/diagnostics |
| 8 | CONTAINS_MANY | Count and keys; HIT with count and one presence byte per key |
| 9 | DRAIN_TRACES | No arguments; HIT with UTF-8 JSON of completed records/drop count |

Response framing is four-byte frame-body length, one status byte, four-byte value
length, then value. Frame-body length = 5 + value length. Status MISS = 0, HIT = 1,
ERROR = 2. For traced requests the value includes a diagnostic envelope even on
MISS/ERROR; "empty operation body" does not imply zero response value bytes.
There is no response protocol-version byte or general structured exception format.

## Connection Loop and Admission

The private `TrainingCacheProtocol()` constructor prevents ordinary utility
instantiation. Static `serve(rawInput, rawOutput, cache, permits, metrics)` owns
DataInputStream and buffered DataOutputStream wrappers and loops over requests.

Its sequence is:

1. Increment connection count. Wait for first header byte; clean EOF returns.
   Timestamp arrival after that byte, excluding preceding idle wait.
2. Read remaining length bytes, validate body length, allocate/read the entire body.
3. Parse version/operation; for version 2 start trace with its 16 bytes and record
   arrival/read duration. Decode keys/entries and record request decode time.
4. Try a semaphore permit without waiting. If unavailable, return ERROR with empty
   operation body. If admitted, count request and dispatch it.
5. Encode/write/flush response. For admitted GET_MANY or PUT_MANY, enqueue the
   post-write completed trace when tracing is active.
6. In a finally block, release any acquired permit and clear trace context; read
   the next request on this connection.

The permit is held through response encoding and flush, so slow writes hold
admission. Reading/decoding and TLS handshake happen outside admission. There is
no total bytes-in-flight budget, request deadline or cancellation protocol here.
One connection is sequential; requests from multiple connections may overlap.

Outer `catch (Exception)` silently ends serving and closes wrappers. Malformed
frames, unsupported operations/versions, parser failures, cache conflicts and I/O
exceptions do not generally produce ERROR replies: they terminate the connection.
ERROR is the explicit permit-denial path. A client can observe disconnection after
publication but before acknowledgement; a trace ID is not transaction deduplication.
`Error` is not swallowed by this catch, though the inner finally still runs if entered.

## Request Decoding Functions

| Function | Behavior and limits |
| --- | --- |
| `decode(operation, input)` | Requires operation 1..9. Allocates key/entry lists; batches read count 0..4096, single-key operations use one. INFO/DRAIN read no arguments. Returns private `Decoded(operation, keys, entries)` record. |
| `readKey(input)` | Reads two strings, copies 32 fingerprint bytes, constructs copying fingerprint then validated CacheKey. Sample/namespace identity constraints come from CacheKey. |
| `readString(input)` | Reads signed length, rejects negative or beyond remaining bytes, copies bytes, decodes UTF-8 with ordinary replacement behavior for malformed sequences. Not strict UTF-8 validation. |
| `readValue(input)` | Reads signed length, rejects negative, >64 MiB or beyond remaining; copies payload. `CacheEntry` then makes another defensive copy. |
| `decodeBulkEntries(input)` | Requires version-1 PUT_MANY body and at least six remaining bytes, uses shared decode, then rejects any trailing bytes. Returns entries; no length-prefix socket framing. |

Socket `decode` does **not** require the operation to consume the whole frame:
trailing request bytes are ignored, including extra INFO/DRAIN arguments. Bulk
staging is stricter and version 1 only. Primitive reads can throw buffer underflow
before explicit length checks. Empty batches are allowed. Frame caps bound the
incoming body, not the cumulative heap copies or size of a GET_MANY response.
Duplicate input keys preserve ordered read slots; write deduplication occurs later
inside the cache owner.

## Dispatch and Response Functions

### dispatch

`dispatch(request, cache)` returns the private `Response(status, value)` record:

- INFO reports engine name, cache durability, process PID, indexed entry count,
  integrity policy, protocol field 1, a limited feature list, and background
  compaction diagnostics. The field remains 1 even though traced requests accept
  version 2; the feature list is not an exhaustive operation enumeration.
- DRAIN_TRACES calls the cache's destructive drain; records are not persisted.
- PUT/PUT_MANY call one cache `putMany`, then return HIT. Failures propagate to the
  connection loop; this handler has no independent transaction-result encoding.
- GET calls cache `get` and distinguishes null absence from a zero-length hit.
- GET_MANY calls `getManyValuesWire` directly: ordered slots and duplicates are
  preserved, slot misses appear inside an overall HIT response.
- GET_REF calls `getRef` and serializes a found reference; this does not itself
  read/hash segment content.
- GET_MANY_REF loops `getRef`, emits count, then one Boolean presence byte per
  slot and encoded reference only for present slots.
- CONTAINS_MANY loops metadata-only `containsKey`, emits count then 0/1 presence
  bytes. Presence is not a fresh payload-integrity result.

Cache operations and most response construction are wrapped in named trace stages.
GET_MANY's wire format is described in the [cache reference](TRAINING-CACHE-FUNCTIONS.md).
Returning a segment reference does not make its file available over the network;
a mapping client needs compatible access to the segment storage location.

### encodeReference and writeResponse

`encodeReference(reference)` encodes ASCII filename as length/bytes, generation
(8 bytes), offset (8), payload length (4), checksum (32). It allocates a new array
and obtains a defensive checksum copy. The reference record validates metadata,
not actual file bounds or content.

`writeResponse(output, status, value)` calls the trace `envelope`, measures that
as responseEncode, writes outer length/status/value length/value and flushes,
then records responseWrite. There is no 64 MiB response-cap check here; request
frame bounds must not be reported as symmetric response bounds. The traced
envelope snapshots diagnostics before responseEncode's current increment and
before responseWrite complete, so those final costs are not fully present inside
the response itself. Completed batch records provide the later boundary.

## Protocol Counter Functions

Source: [TrainingCacheProtocolMetrics.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheProtocolMetrics.java).

| Function | Behavior |
| --- | --- |
| `connection()` | Increments cumulative handler invocations, including connections that later send no valid requests. Not a current-open-connection gauge. |
| `request(operation)` | Increments admitted request count and separate counts for GET, PUT, GET_MANY_REF, GET_MANY and PUT_MANY. GET_REF/INFO/CONTAINS_MANY/DRAIN affect only total. |
| `snapshot()` | Returns immutable map of LongAdder totals; independent reads are not one atomic concurrent snapshot. |

Requests are counted before dispatch completes. Permit-denied or malformed
pre-dispatch requests are not counted as requests; a counted operation can still
fail later. These counters show API usage, not successful artifact count or
protocol end-to-end latency.

## Per-Request Trace Functions

Source: [TrainingCacheRequestTrace.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheRequestTrace.java).

Trace state is ThreadLocal. Synchronous cache/engine stages on the request thread
can contribute; ordinary asynchronous work does not automatically inherit the
trace. Nested durations are inclusive and must not be added as disjoint phases.

### Context and Timing Helpers

| Function | Behavior |
| --- | --- |
| Private `TrainingCacheRequestTrace(id)` | Converts supplied bytes to hex ID, creates flush/read collectors and initial start time. Protocol supplies 16 bytes; constructor does not enforce that length. |
| `begin(id)` | Creates trace, attaches both collectors while remembering their predecessors, then installs active trace. No nested active-trace stack. |
| `requestStarted(started, readNs)` | When active, replaces total start timestamp and stores requestRead duration. No-op without active trace. |
| `add(stage, durationNs)` | Adds supplied duration to named stage when active; no validation of name or duration. |
| `start()` | Returns nanosecond timestamp when active, otherwise zero. |
| `end(stage, started)` | Adds current time minus supplied timestamp to active stage; assumes callers pair start/end within a consistent trace scope. |
| `clear()` | Restores predecessor flush/read collectors if active, then removes active ThreadLocal. Does not restore a previous active trace if begin was nested. |
| `IoWork.run()` | Functional callback may return a result or throw IOException. |
| `measureIo(stage, work)` | Calls work, records end in finally including failing work; IOException propagates. |
| `measure(stage, work)` | Directly calls supplier when inactive; otherwise records elapsed time in finally, including exceptions, using captured trace. |

### envelope and completed

`envelope(payload)` returns the original array unchanged without active trace.
With tracing it builds UTF-8 JSON and allocates a new array containing four-byte
metadata length, JSON, then original payload. It reports trace ID, server duration,
stage durations, write/read diagnostics, flush/compaction/table-finish records,
compaction scheduling/debt and `responseWriteCompleted: false`.

This snapshot is pre-send. Server duration begins at the first header byte's
arrival and excludes initial idle wait, TLS handshake and earlier connection setup;
it does not include a completed response flush. Trace metadata itself adds
serialization/allocation/copy overhead. Stage names and some nested record strings
are inserted manually without generic escaping, assuming internal controlled
diagnostic names rather than arbitrary untrusted text.

`completed(completedAt)` returns null when inactive. Otherwise it returns a map
with trace ID, copied stages, totalServerNs, true responseWriteCompleted and read
diagnostic snapshot. It does not repeat all response-envelope flush/write details.
The copied stage map is mutable within the returned top-level immutable map; this
is not a general deeply immutable diagnostic object.

The protocol enqueues completed records only for admitted GET_MANY/PUT_MANY after
successful write/flush. Legacy version-1 requests have no active trace and enqueue
nothing. Single GET, INFO and overload replies can carry traced response envelopes
but do not produce post-write queue records through this handler. The cache queue
is bounded and may drop old records; a drain is not a complete durable audit trail.

## Diagnostic JSON Functions

Source: [DiagnosticJson.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/DiagnosticJson.java).

| Function | Behavior |
| --- | --- |
| Private `DiagnosticJson()` | Prevents utility instantiation. |
| `encode(value)` | Creates StringBuilder, calls append, returns string. No parser, canonical key sorting, size budget or cycle guard. |
| `append(result, value)` | Emits null/Boolean/Number directly; recursively emits maps and Iterables; otherwise quotes toString output, escaping quote/backslash and control characters below 32 as Unicode escapes. |

Map keys are converted with `toString`; null keys fail. Arrays are not Iterable
and therefore stringify instead of becoming JSON arrays. Nonfinite Number values
are not specially rejected or converted, so this is intended for the controlled
JSON-compatible diagnostic domain, not arbitrary object serialization. Map ordering
comes from the supplied map and is not canonicalized.

## Tests and Coverage Limits

[TrainingCacheTraceTest](../../modules/aether-training-cache/src/test/java/io/aetherdb/training/cache/TrainingCacheTraceTest.java)
checks mixed version-1/version-2 requests on one stream, correlated batch put/get,
misses, permit denial, malformed-operation cleanup, write/flush evidence, and
fragmented reads with duplicate/missing/segment slots. It checks diagnostic field
presence and readback, not that all trace stages are additive or overhead-free.

The declaration inventory covers all explicit functions in these four files,
including `IoWork.run` and private codecs. It does not prove bounded connection
memory, live TLS trust configuration, response-size admission, strict UTF-8 or
trailing-byte rejection, or every Python retry/cancellation behavior. Those remain
separate source and integration boundaries.
