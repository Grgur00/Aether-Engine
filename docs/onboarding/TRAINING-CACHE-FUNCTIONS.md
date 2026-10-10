# Training Cache Operations and Segment Functions

[Function index](FUNCTION-INDEX.md) | [Artifact identity](TRAINING-IDENTITY-FUNCTIONS.md) | [Python and training cache](TRAINING-CACHE-AND-PYTHON.md)

This reference covers all explicit declarations in six files: `TrainingCache`,
`TrainingCacheSegmentStore`, `SegmentReference`, `BatchValueResult`,
`TrainingCacheLatency`, and `TrainingCacheMetrics`. The primary implementation is
[TrainingCache.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCache.java).
Daemon/protocol, offline bulk writer, request tracing, and experiment drivers are
separate implementations, not fully inventoried by this page.
See [Java diagnostic drivers](TRAINING-DRIVER-FUNCTIONS.md) for complete benchmark
and forced-process campaign contracts.

## Architecture and State

The cache owns an `AetherDatabase`, an optional segment-file store, a two-thread
prefetch executor, an access-ordered metadata map, and an in-process single-flight
map. Values are immutable per currently existing key. The application envelope
adds payload SHA-256 and CRC32C checks above database/WAL/SSTable integrity.

| State | Ownership and use |
| --- | --- |
| `entries` | Base64 storage key to accounted bytes; guarded by its own monitor; governs namespace operations and eviction |
| `diskBytes` | Sum of accounted live envelopes plus segment payload sizes; not total physical directory usage |
| `inflight` | Concurrent map of identity to computation promise; coalesces overlapping computations in this cache instance |
| Counters and latency | Instance-local diagnostics; not a persisted history or uniformly instrumented account of all APIs |
| Completed traces | Cache-object monitor guards bounded queue and dropped-record count, separate from `entries` lock |

Logical cache bytes exclude key bytes, WAL/SSTable framing, obsolete database data,
orphan segments, temporary files and filesystem allocation overhead. Eviction can
reduce this accounting before underlying storage reclaims physical space.

## Opening, Reopen and Closing

| Function | Behavior |
| --- | --- |
| `open(directory)` | Uses 10 GiB maximum, RECOVERABLE durability and AUTO storage. |
| `open(directory, maximumBytes)` | Selects the supplied maximum and the same durability/storage defaults. |
| `open(directory, maximumBytes, durability)` | Adds supplied durability, retains AUTO policy. |
| `open(directory, maximumBytes, durability, storagePolicy)` | Requires positive maximum and non-null modes. EPHEMERAL opens in-memory and ignores directory; other modes require directory and open persistent Aether with development profile and disk-pressure checks disabled. |
| Package constructors `TrainingCache(database, maximumBytes)` and `(database, maximumBytes, durability)` | Delegate to the full constructor with AUTO and no segment directory; do not repeat public open's validation. |
| Full `TrainingCache(database, maximumBytes, durability, storagePolicy, directory)` | Retains fields, creates optional segment store and two-thread pool, then calls `loadIndex`. No cleanup wrapper if initialization fails after resources are created. |
| `close()` | Calls executor `shutdownNow`, then database `close`; does not await prefetch termination or explicitly unmap returned mappings. No cache-level closed flag or idempotence guard. |

Private `loadIndex()` scans the whole database, obtains key/value copies, decodes
and validates every entry, accounts its envelope and segment file size, then
evicts if over capacity. It is **not metadata-only reopen**: inline decoding copies
payloads and segment decoding reads/hashes payload files, discarding returned bytes.
It catches `IllegalArgumentException` as corruption, deletes the database key and
corresponding segment, and increments corruption count. Other failures propagate.
Reconstructed access order follows scan insertion, not the previous process's LRU
history. The scan covers the whole backing database, not a dedicated key prefix.

## Single-Value Reads

### get and map

`get(key)` requires a key, derives storage bytes and performs `traceLookup`. Its
get-latency sample covers only that lookup, before application decoding/copying.
Absence increments misses. A valid found value is decoded, returned as caller-owned
bytes, counted as a hit/bytes served, and touches the metadata map's access order.

An `IllegalArgumentException` during decoding increments corruption and misses,
deletes the database key and removes its accounting. It does not delete the segment
file in this path. Storage deletion failures can propagate; exceptions are not
universally converted into misses.

`map(key)` returns a read-only payload view: inline bytes borrow the immutable
lookup representation; segment bytes use validated file mapping or a read fallback.
It counts hits/misses/bytes and uses the same corruption deletion/accounting path
as `get`, but does not record get latency or touch LRU order. For segment detection
it first calls `LookupResult.value()`, copying the encoded representation even for
an inline value; mapping is not synonymous with zero allocation.

### Presence and References

| Function | Behavior |
| --- | --- |
| `containsKey(key)` | Returns database logical presence only. Does not decode, verify payload integrity, touch LRU, or update hit/miss counters. |
| `getRef(key)` | Looks up an entry, returns null for absence/non-segment, copies metadata, checks metadata CRC/name and constructs generation 1, offset 0 reference. Catches runtime parsing failures, counts corruption and returns null. |
| `getManyRefs(keys)` | Repeatedly calls `getRef`, returning a mutable linked map containing only non-null references. Duplicate keys collapse. |
| `getMany(keys)` | Repeatedly calls `get`, returning a mutable linked map of hits with payload copies. Duplicate keys collapse; get's counters/LRU behavior still runs per input occurrence. |

`getRef` is not full `decodeValueView` validation: it reads but does not check the
encoded version, does not require exact metadata-envelope length, and does not
read/hash the segment payload or prove the file exists. A reference is therefore
not a fresh payload integrity result. This API neither deletes malformed entries
nor updates normal hit/miss/served/LRU diagnostics.

## Ordered Packed Reads

Private `collectValueParts(keys)` preserves one result slot per input, including
duplicates and misses. Each lookup is decoded to a private value view. Corruption
increments the corruption counter and deletes the database key, then becomes MISS;
this branch does not remove metadata accounting or delete a segment file. Valid
results receive HIT_INLINE or HIT_SEGMENT and their lengths contribute through
`Math.addExact` to the total. It does not update ordinary hits, misses, served-byte
counters, LRU or get latency. Private `ValueParts` holds statuses, nullable views,
and total bytes; it has no explicit validating constructor.

| Function | Packing behavior |
| --- | --- |
| `getManyValues(keys)` | Collects views, allocates a contiguous payload buffer, fills per-slot offsets/lengths, copies non-null views, flips buffer, returns `BatchValueResult`. Measures `batchPack`. |
| `getManyValuesWire(keys)` | Allocates the complete existing protocol body and packs views directly into it, avoiding an intermediate packed payload. Checked arithmetic computes body size; measures response encoding, packing and artifact copy. |
| Private `isSegmentValue(encoded)` | Tests at least eight bytes and segment magic; currently not called elsewhere in this class. |

The wire body's big-endian layout is count (4 bytes), count one-byte statuses,
count four-byte offsets, count four-byte lengths, then concatenated payloads.
MISS is 0 and either hit representation is 1: the wire does not preserve the
inline/segment distinction. Empty hits and misses may both have length zero but
different statuses. This layer does not impose a batch-count/response-size cap;
protocol admission must be checked separately.

### BatchValueResult

Source: [BatchValueResult.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/BatchValueResult.java).

`BatchValueResult(statuses, offsets, lengths, payload)` requires non-null fields
and matching slot counts; copies the status list and both arrays and creates a
read-only payload view. It does not validate offset/length ranges against payload
size or status consistency. `ValueStatus` includes HIT_INLINE, MISS, CORRUPT and
HIT_SEGMENT; current cache collection reports corruption as MISS, not CORRUPT.

Implicit `offsets()` and `lengths()` expose the record's copied arrays directly,
not fresh clones. Implicit `payload()` exposes the same buffer object, whose
position/limit can change even though its bytes are read-only. Construction shares
the source buffer's storage; an arbitrary caller can still mutate an original
writable buffer. Normal cache construction owns its packed bytes privately, so
these metadata mutations do not change the stored database artifact.

## Publication and Immutable-Key Checks

`put(key, payload)` requires both arguments, constructs a copying `CacheEntry`,
then delegates to `putMany`.

`putMany(values)` holds the metadata-map lock through validation, file creation,
database write, accounting and eviction. Its stages are:

1. Call `CacheEntry.value()` once per entry, deduplicate by `CacheKey`, and reject
   duplicate keys containing different bytes before modifying files.
2. Look up each unique key. Existing entries are decoded/copied and compared;
   different bytes are rejected. Equal existing values are idempotent and omitted
   from publication. Invalid existing values propagate rather than automatically
   becoming replaceable in this path.
3. If nothing is pending, return without put-latency sample or written-byte count.
   Otherwise reach `before-data-write`, encode each pending value (including any
   segment publication), and put its envelope into one `WriteBatch`.
4. Reach `before-index-commit`, call database `write` with SYNC for DURABLE or
   GROUP_SYNC otherwise, a 30-second admission timeout and fail-fast false; reach
   `after-index-commit-before-ack`. This method does not inspect the returned write
   result separately.
5. Add pending keys/byte accounting, count newly published payload bytes, evict
   over-capacity entries, and record total put latency including lock wait.

Conflicts are checked for the whole batch before file modifications. That does
not make segment files and database metadata one rollback transaction: files may
exist after a later encoding/write/hook failure, and an exception after database
commit can precede in-memory accounting. The method has no explicit rollback of
already published segments. The [engine reference](ENGINE-FUNCTIONS.md) describes
the separate database write contract.

## Encoding and Integrity Helpers

Both envelopes are big-endian. Inline magic is `0xAE7CA001`, version 1, length,
32-byte payload SHA-256, payload, then four-byte CRC32C covering preceding bytes:
48 bytes of overhead. Segment magic is `0xAE7CA002`, version 1, payload length,
32-byte payload SHA-256, 68 ASCII bytes of segment filename, and CRC32C: 116 bytes.
CRC32C here is a raw integer, not the SSTable block masked-checksum format.

| Function | Behavior and ownership |
| --- | --- |
| `encode(payload, storageKey)` | Computes payload SHA-256 and admission diagnostics. Policy-selected segments require a store, publish the file, then encode metadata/CRC; otherwise delegates to inline encoding. |
| `inlineEnvelope(payload, digest)` | Allocates the full envelope, writes header/digest/payload, computes CRC, and returns its array. Relies on internal callers for valid 32-byte digest; no independent input contract checks. |
| `traceLookup(storageKey)` | Wraps database `get` in nested `databaseLookup` and legacy `indexLookup` trace stages. Both include the same database API boundary. |
| `decode(encoded, storageKey)` | Traces `valueDecodeAndValidate` around `decodeValue`. |
| `decodeValue(encoded, storageKey)` | Calls `decodeValueView`, then `copyPayload`. |
| `copyPayload(view)` | Transfers the array when an array-backed whole standalone payload is supplied; otherwise allocates and copies remaining bytes, advancing that view's position. |
| `digest(input)` | Creates SHA-256, updates from the buffer's remaining bytes (advancing position), and returns digest; unavailable algorithm becomes `IllegalStateException`. |
| `segmentName(storageKey)` | SHA-256 of full storage key, formatted as 64 lowercase hex characters plus `.seg`. |

### decodeView and Inline Admission

`decodeView(result, storageKey)` obtains a read-only lookup view. Inline magic
uses `LookupResult.validateOnce` with a private opaque identity and
`validateInlineIntegrity`; successful validation is attached to that exact immutable
lookup object, not to a key. It records admission/reuse diagnostics and returns
a read-only slice of the validated payload. Non-inline values take a defensive
encoded-value copy and go through `decodeValueView`.

`validateInlineIntegrity(encoded)` checks minimum envelope length, inline magic,
version, nonnegative/exact payload length, CRC32C and payload SHA-256. CRC failure
short-circuits the SHA comparison. The validation marker is installed only after
success. A new lookup representation must validate again; a retained SSTable
lookup can reuse its marker, whereas newly materialized native values validate
again. These application checks are separate from authoritative SSTable inventory
verification and its streaming scanner.

### decodeValueView and validateMappedSegment

`decodeValueView(encoded, storageKey)` validates inline length/version/checksums
and returns a read-only slice. Segment form requires version 1, exact metadata
length and a store, checks metadata CRC and the filename derived from storage key,
reads the entire segment into a new array, and checks payload length/SHA-256.
Its returned segment view is writable internally over that fresh payload array;
`copyPayload` can transfer ownership to a byte-array caller without another copy.

`validateMappedSegment(metadata, storageKey)` validates exact metadata framing,
CRC/name, attempts `mapReadOnly`, and on `IllegalArgumentException` falls back to
reading and wrapping the file read-only. It checks mapped/read length and hashes
all payload bytes before returning. This fallback is not restricted to Windows
errors. The initial header read can throw buffer-underflow exceptions for severely
truncated metadata; `map` only catches `IllegalArgumentException`, not every parser
exception. A read-only mapping is not protection against external file modification
after validation, and there is no explicit mapping lease/unmap API here.

## Segment File Store

Source: [TrainingCacheSegmentStore.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheSegmentStore.java).

| Function | Behavior |
| --- | --- |
| `TrainingCacheSegmentStore(directory)` | Requires root, resolves `segments`, creates directories; I/O failure becomes `IllegalStateException`. |
| `publish(name, payload, durable)` | Creates a UUID-suffixed temporary file, writes fully, optionally forces it, closes it, reaches fault hook, atomically replaces target, optionally forces directory. |
| `read(name)` | Reads the whole file with a trace stage; wraps I/O failure as `IllegalArgumentException` so retrieval can treat an unavailable segment as corruption. |
| `mapReadOnly(name)` | Rejects Windows explicitly; otherwise maps the entire file read-only while closing the channel. I/O failure becomes `IllegalArgumentException`. |
| `size(name)` | Returns `Files.size`; I/O failure becomes `IllegalArgumentException`. |
| `delete(name)` | Deletes if present; I/O failure becomes `IllegalStateException`. |

Atomic-move unavailability fails publication; there is no non-atomic fallback.
Directory force is requested for durable mode except on Windows. The
`after-data-fsync` hook is reached after writing/closing even when durable is false,
so its name alone does not prove a force occurred. IOException cleanup attempts
to remove the temporary file, ignoring cleanup I/O failure; non-I/O hook exceptions
do not take that catch path. Failure after target replacement does not roll back
the target. Names are resolved directly without path-security validation; ordinary
cache calls supply derived safe hex filenames, not arbitrary user paths.

### SegmentReference

Source: [SegmentReference.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/SegmentReference.java).

`SegmentReference(segmentId, generation, offset, length, checksum)` requires a
nonblank ID, generation >= 1, offset/length >= 0 and 32 checksum bytes; clones the
checksum. `checksum()` returns a fresh clone; `sameChecksum(value)` uses
`Arrays.equals` against supplied bytes, not SHA-256 of a payload. Other implicit
accessors expose fields. Construction does not verify path safety, file bounds,
or file content, and generation/offset are fixed to 1/0 by current `getRef`.

## Namespace Operations, Eviction and Single Flight

| Function | Behavior |
| --- | --- |
| `namespaces()` | Under entries lock, decodes all indexed namespaces into an immutable distinct set. The local start-time variable does not produce a latency measurement. |
| `namespaceStats()` | Under the same lock, counts indexed entries per decoded namespace and returns an immutable map. Not a database integrity scan. |
| `invalidateNamespace(namespace)` | Requires a namespace and delegates to `deleteNamespace`. |
| `deleteNamespace(namespace)` | Iterates indexed matches under lock, deletes each database key then segment (if a store exists), subtracts bytes and removes entry. Not a single multi-key transaction; failure can stop midway. |
| `evictIfNeeded()` | Removes least-recent indexed entries while accounted bytes exceed maximum; deletes key/segment, updates accounting and eviction count. Called under entries lock or during construction. An oversized new entry can be evicted immediately. |
| `removeIndex(key)` | Removes indexed entry and subtracts its size if present; no disk operation or eviction counter. Caller provides synchronization. |
| `keyString(key)` | Encodes bytes as URL-safe Base64 without padding for map identity. |
| `decodeKey(key)` | Decodes that Base64 string back to bytes. |
| `namespaceOf(key)` | Decodes key, reads big-endian namespace length, checks 1..remaining length and decodes UTF-8; does not validate complete digest framing. |
| `prefetch(keys)` | Submits independent `get` tasks to the two-thread executor; ignores futures and does not bound the executor's queue or expose completion/failure. |

`getOrCompute(key, computation)` requires both arguments and installs a promise
by encoded identity. A follower joins the existing promise and receives a clone.
The leader calls `get`; on miss it invokes the supplier, requires a non-null result
and calls `put`. It completes the promise with a clone but returns its own original
result. Runtime exceptions/errors complete the promise exceptionally and propagate;
finally removes only its own promise. Followers can receive completion-wrapped
exceptions. Coalescing is in-process, per cache owner, not durable distributed
deduplication. Once removed, later calls depend on storage presence; eviction can
cause recomputation. It does not hold namespace deletion and supplier execution
as one transaction.

## Diagnostics and Measurement Functions

| Function | Behavior |
| --- | --- |
| `recordCompletedTrace(record)` | Cache-monitor synchronized; ignores null, keeps up to 16384 records, dropping oldest/counting overflow. Stores the supplied map reference rather than copying its contents. |
| `drainCompletedTraces()` | Under same monitor, copies queue into an immutable list with dropped count, clears queue and resets drop count atomically. Does not deep-copy record maps. |
| `cacheEntries()` | Returns metadata-map size under entries lock. |
| `metrics()` | Under entries lock, samples LongAdder totals, accounting and six percentiles into a record; concurrent read increments mean it is not one atomic global event snapshot. |
| `durability()` | Returns selected enum. |
| `integrityPolicy()` | Returns an immutable descriptive policy map for inline/native/segment/write validation; not a verifier invocation or guarantee that a particular read path ran. |
| `compactionDiagnostics()` | Delegates to engine diagnostics for the owned database. |
| `benchmarkDatabase()` | Package-private raw database exposure for layer-one benchmarking, bypassing artifact APIs. |
| `awaitCompactionIdle()` | Package-private engine wait delegate; interruption propagates. |

[TrainingCacheMetrics.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheMetrics.java)
is a record of counters, live accounting and get/put percentiles with no explicit
constructor validation. Its implicit accessors have no side effects. Batch value
reads/reference reads do not update the same counters as `get`, so ratios must be
interpreted in the workload's API context. Newly published payload bytes are counted,
not total physical bytes written by WAL, compaction or segment metadata.

[TrainingCacheLatency.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheLatency.java)
has synchronized `record(nanos)`, retaining at most 100000 samples and overwriting
slots after capacity. `percentile(percentile)` returns zero when empty, copies and
sorts retained samples, uses ceil-rank and clamps the index. It does not validate
the percentile range or nanos, and each percentile call independently copies/sorts.
Get percentiles exclude envelope/hash/copy cost; put percentiles include publication
and eviction for nonempty pending batches. They are not protocol end-to-end latency.

## Opt-In Fault Boundary Hook

[TrainingCacheFaultHooks.java](../../modules/aether-training-cache/src/main/java/io/aetherdb/training/cache/TrainingCacheFaultHooks.java)
is package-private main-source instrumentation for process-death tests. It does
not inject failure during ordinary calls unless the exact fault property matches.

| Function | Behavior and Failure Boundary |
| --- | --- |
| `TrainingCacheFaultHooks.TrainingCacheFaultHooks()` | Private empty constructor prevents ordinary instance construction; hook is static. |
| `TrainingCacheFaultHooks.reach(point)` | Compares point with `aether.training.test.fault`; returns on mismatch/unset property. On exact match, requires `aether.training.test.marker`, writes point into that path, then sleeps repeatedly in one-second intervals until externally killed or interrupted. |

Marker writes use Files.writeString: create/truncate default behavior, no parent
creation, explicit force, atomic rename or durable marker guarantee. Missing marker
property throws IllegalStateException; IO failure is wrapped as cannot-announce
failure. Interrupted sleep restores interrupt status and throws an interruption
exception. Matching with a null point fails before property comparison because
the method calls point.equals; actual call sites supply literal boundary names.
There is no built-in timeout, acknowledgment channel or normal return after marker
publication. Do not enable this property on a normal daemon expecting progress.

TrainingCache calls it at `before-data-write`, `before-index-commit` and
`after-index-commit-before-ack`; segment storage calls `after-data-fsync` after its
force operation; the fault probe calls `after-ack` after receiving an applied put.
The external campaign owns marker observation and process termination. Reaching
a marker identifies that code boundary, not a simulation of power loss or proof
that every OS/device buffer reached durable media.

## Tests and Remaining Coverage

[TrainingCacheTest](../../modules/aether-training-cache/src/test/java/io/aetherdb/training/cache/TrainingCacheTest.java)
exercises conflicts before publication, idempotence, competing writers, reopen,
single flight/retry, namespaces, eviction, ephemeral mode, prefetch, segment
corruption, mapping, storage selection and batch byte ownership.
It does not exhaust malformed metadata framing, returned batch-metadata mutability,
mixed storage failure cleanup, reopen cost, or each API's diagnostic differences.

The syntax-tree documentation test checks explicit function names in these six
files, including private helpers. It is an omission check, not runtime testing or
proof of each overload's behavior. Continue with the
[daemon lifecycle](TRAINING-DAEMON-FUNCTIONS.md) and
[protocol/trace reference](TRAINING-PROTOCOL-FUNCTIONS.md) for listener and wire
boundaries. Python client batching and offline bulk install remain separate areas
for further function-reference coverage.
