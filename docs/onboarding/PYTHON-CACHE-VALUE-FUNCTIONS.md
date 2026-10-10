# Python Cache Value and Batch Functions

[Function index](FUNCTION-INDEX.md) | [Connection and retries](PYTHON-CLIENT-FUNCTIONS.md) | [Mapped views and tensors](PYTHON-MAPPING-FUNCTIONS.md)

This reference covers identity models and value/batch methods in
[client.py](../../clients/python/aether_training_cache/client.py), plus
[batch_values.py](../../clients/python/aether_training_cache/batch_values.py) and
[batch.py](../../clients/python/aether_training_cache/batch.py). Connection and
mapping references cover the remaining declarations in client.py. This client
transports opaque artifact bytes; higher-level codecs define tensor/image formats.

## Identity Models and Functions

### TransformationFingerprint

| Function | Behavior |
| --- | --- |
| `from_descriptor(descriptor)` | Classmethod hashing descriptor UTF-8 bytes with SHA-256; no parsing or normalization. |
| `from_mapping(values)` | Sorts keys, uses Python `str` for values, joins key/value character-length fields, then delegates to from_descriptor. Not recursive canonical JSON. |
| `__post_init__()` | Requires digest length 32. Does not enforce actual bytes type or defensively copy an arbitrary mutable object. |

The frozen dataclass prevents reassignment, not mutation of an incorrectly supplied
bytearray. Normal factory results are immutable bytes. Cross-language descriptors
must agree on actual text/bytes: Python len counts Unicode code points while Java
String.length counts UTF-16 units, and Python/Java value stringification differs
(for example Boolean case). ASCII descriptors with agreed string values avoid
those particular differences; this factory does not enforce such a restriction.

Frozen `CacheKey(namespace, sample_id, transform)` and `SegmentReference(segment_id,
generation, offset, length, checksum)` have no explicit validation functions.
Annotations do not enforce blank names, field types, offsets, generation or checksum
width. Wire/server and mapping consumers add some checks later. Generated dataclass
equality/hash depend on field behavior; they do not make arbitrary field objects
deeply immutable. Sample identity is supplied, not automatically source-content hashed.

## Single Requests and References

| Function | Behavior |
| --- | --- |
| `_request(operation, key, value)` | Builds version-1 body with length-prefixed UTF-8 namespace/sample and fingerprint digest; appends length/value for PUT only; calls `_round_trip`. No shared frame-size preflight. |
| `get(key)` | Opcode 1; returns bytes or None. Zero-length HIT is `b""`, distinct from absence. |
| `put(key, value)` | Copies/coerces value with bytes, sends opcode 2 and discards response. Does not expose a transaction receipt. |
| `_reference(key)` | Opcode 3; None for MISS. Reads filename length and requires exact total metadata size, ASCII decodes ID, unpacks unsigned generation/offset/length, returns SegmentReference. Short initial data can raise struct.error. |
| `get_ref(key)` | Delegates to `_reference`. Does not read segment payload. |
| `get_or_compute(key, compute)` | Gets, computes/coerces bytes on miss, puts, returns result. No Python single-flight map or lock spanning get/compute/put. |

Concurrent get_or_compute calls can both compute; connection serialization does
not make this sequence atomic. Java rejects different payloads for an existing key.
It is not a remote invocation of Java TrainingCache.getOrCompute. References carry
metadata, not a newly verified payload; local mapping hashes content separately.
Transport retries are described in the connection reference; parsing here is after
the exchange and does not automatically replay it.

## Presence and Reference Batches

### contains_many

`contains_many(keys)` materializes keys, rejects >4096, builds opcode-8 body using
bytearray, then requires response count/length to match and every status byte to
be 0 or 1. Returns a set of present keys, collapsing duplicates and losing order.
An empty input still sends a zero-count request. Presence is server metadata-only,
not payload validation or a guarantee the artifact remains present for a later get.

### get_many_refs

`get_many_refs(keys)` materializes input, returns empty dictionary without network
work for no keys, rejects >4096, builds opcode-4 body using repeated bytes
concatenation, checks returned count, and walks optional references. The final
cursor must equal response length. Duplicate keys collapse in the result dictionary.

Presence is interpreted by truthiness, not restricted to 0/1 like contains_many.
Some slices/unpacks can throw IndexError/struct.error; checksum slices are not
independently checked for 32 bytes by the dataclass. This is not a uniformly strict
reference schema validator for every malformed response. Filename IDs are ASCII
decoded but not validated for path traversal here.

## Packed Byte Batches

### put_many and get_many

`put_many(values)` materializes key/value pairs, rejects >4096, coerces each value
to bytes, concatenates opcode-6 count and repeated key/value structures, then sends.
An empty list still sends a request. The count cap does not bound aggregate bytes;
repeated bytes concatenation can allocate/copy intermediate request bodies.

`get_many(keys)` materializes input, returns {} locally when empty, rejects >4096,
calls get_many_values and converts each nonmissing slot to bytes in a dictionary.
This adds a payload copy per returned value; duplicate keys collapse even though
the server answered each input slot. It does not explicitly call packed.close.

### get_many_values

`get_many_values(keys)` materializes keys before tracing, returns an empty
InlineValueBatch locally when empty and rejects >4096. It encodes opcode-5 keys,
optionally uses deferred `_BatchRequestTrace`, then parses returned count, one
status byte per slot, unsigned offset array, unsigned length array and remaining
payload bytes. The payload is a memoryview over immutable response bytes; slot
views can borrow that backing object instead of copying each artifact.

Count must match input count. Status code 1 becomes HIT_INLINE; every other code
becomes MISS, not a rejected unknown status. The Java wire collapses segment/inline
hits to 1, so HIT_INLINE here means a transported packed hit, not proof the server
stored the artifact inline. Offset/length bounds, contiguity and trailing payload
size are not fully validated by this parser or InlineValueBatch.

BaseException after trace creation is recorded by class name and rethrown; finally
emits the single deferred record after parsing succeeds/fails. Decode errors are
outside the exchange's retry scope. With tracing disabled this batch path avoids
UUID/timing calls. No artifact codec or tensor construction occurs in this function.

### InlineValueBatch

The dataclass fields are buffer, offsets, lengths and statuses; no explicit
constructor validation/copying is provided. They remain mutable references.

| Function | Behavior |
| --- | --- |
| `value(index)` | Returns None unless status is exactly HIT_INLINE; otherwise returns buffer slice from offset through offset+length. Python slicing can truncate rather than reject out-of-bounds lengths. |
| `close()` | Releases the parent memoryview. No closed flag/context-manager methods or child-view registry. Existing independent slices need their own lifetime handling. |

Normally buffer bytes are immutable response storage, but arbitrary direct
construction can supply a writable view or inconsistent metadata. Retaining a
packed batch avoids per-artifact byte copies; it does not eliminate request,
receive, envelope, metadata-list or subsequent codec allocations.

## Mapping and Tensor Convenience Calls

| Function | Behavior and boundary |
| --- | --- |
| `get_view(key, mapped_segments)` | Fetches one reference; returns None on miss, otherwise registry.view. Inline values have no segment reference. |
| `get_many_views(keys, mapped_segments)` | Fetches reference dictionary, maps each present reference; duplicate keys already collapsed. Partial mapping failure has no helper-level rollback. |
| `get_view_batch(keys, mapped_segments)` | Times reference lookup, groups references, creates views, returns views plus BatchTiming; does not transport inline payload fallback. |
| `get_numpy(key, mapped_segments, dtype, shape)` | Gets reference, obtains cache_view, constructs NumPy array and returns `(array, view)` for lifetime ownership. Does not explicitly handle None reference misses. |
| `get_tensor(key, mapped_segments, dtype, shape)` | Same leased path through torch_tensor; returns `(tensor, view)`, not a GPU tensor or copied writable artifact. |

get_view_batch sets segment_acquire_nanos to zero and counts registry.view work
(including mapping/hash) in slice_create_nanos. Its lease_nanos is residual total
minus reference/slice timing, not a separately timed lease operation. Do not infer
independent mapping-acquisition or lifetime costs from those names. Views dictionary
collapses duplicates while group iteration can visit duplicate input slots.

### ReferenceBatch and BatchTiming

`ReferenceBatch(keys, references)` is a mutable dataclass with no explicit validation
or copying. Its `segment_groups` property builds a fresh dictionary keyed by
`(segment_id, generation)`, with lists of present keys preserving input occurrences.
Its `present` property returns present input keys, retaining duplicates/order.

Frozen `BatchTiming` exposes total_nanos, segment_acquire_nanos, slice_create_nanos,
lease_nanos, views and segment_groups. It performs no nonnegative or consistency
validation and is a descriptive container, not an automatic timer.

## Information, Trace Drain and Background Work

| Function | Behavior |
| --- | --- |
| `engine_info()` | Sends INFO and JSON-decodes response; no separate capability-schema validation in this method. |
| `drain_completed_server_traces()` | Sends DRAIN_TRACES and JSON-decodes records/drop count unchanged. The server drain is destructive and bounded, not persisted audit retrieval. |
| `wait_for_background_compaction(timeout)` | Polls engine_info every 0.1 s until state is not RUNNING/STOPPING or timeout is reached; returns wall time, drained Boolean and last diagnostics. |

Missing state or unknown state is considered drained by this helper. Drained is
not equivalent to successful compaction: failure counters/lastFailure remain in
the returned diagnostics. Timeout is checked after each engine_info exchange and
is not a strict upper bound on blocked/retried network time. Exceptions propagate.
These methods are intended outside measured training regions; they do not move
the caller's timing boundaries automatically.

## Tests and Inventory

[test_batch_request_timing.py](../../clients/python/tests/test_batch_request_timing.py)
checks borrowed backing bytes, phase totals, post-decode trace emission, count
mismatch without retry and trace drain correlation.
[test_request_tracing.py](../../clients/python/tests/test_request_tracing.py)
checks background-work polling/timeout and preserved failure evidence.
The tests do not exhaust every reference/status/offset malformed-response case.

Python AST inventory checks explicit declaration names collectively across all
client references and both batch files. Dataclass-generated methods and meaningful
metadata bounds still require manual source analysis; declaration-name presence
is an omission check, not proof of all batch/error behavior.
