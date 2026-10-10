# Python Java Adapter, Mmap Store, and Resource Functions

[Function index](FUNCTION-INDEX.md) | [Pipeline functions](PYTHON-PIPELINE-FUNCTIONS.md) | [Python client](PYTHON-CLIENT-FUNCTIONS.md)

This reference covers all 29 explicit functions in
[java_store.py](../../clients/python/aether_training_cache/java_store.py),
[persistent_mmap.py](../../clients/python/aether_training_cache/persistent_mmap.py),
and [resources.py](../../clients/python/aether_training_cache/resources.py).
JavaArtifactStore adapts caller-computed artifact identities to the Java daemon;
PersistentMmapStore is a **comparison baseline**, not Aether's storage engine.
Resource counters describe one observed process, not an experiment-wide hardware
meter. The separate filesystem provenance prototype remains a different API.

## Architecture and Identity

```text
Java adapter:
  caller digest -> CacheKey(namespace, digest, fixed adapter fingerprint)
  -> packed GET_MANY_VALUES or PUT_MANY -> real Java daemon
  -> owned response slices or copied bytes; no Python artifact index

Mmap baseline:
  caller key -> in-memory index (+ legacy index.json)
  -> checksummed append journal -> offsets into append-only data.bin
  -> read-only mmap slice -> copied framed bytes -> caller's decoder

Worker observations:
  /proc snapshot before/after -> same-PID cumulative counter deltas
  -> available numbers or explicit missing values
```

Neither adapter computes the complete source/transform identity on behalf of its
caller. Java's fixed `aether-derived-artifact-v1` adapter fingerprint is an extra
namespace component; the caller's key must already bind the source/content and
deterministic transformation. The mmap key is likewise caller-defined.

## Java Artifact Adapter Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `JavaArtifactStore.__init__(*, port, namespace="tpds", server_trace=False)` | Creates a low-level localhost client, optional request-trace sink, namespace and fixed adapter fingerprint, then resets operation/transport counters. Does not launch a daemon, validate engine/durability identity or create a Python store. Socket connection is lazy. |
| `JavaArtifactStore._key(key)` | Wraps the supplied artifact digest in CacheKey using namespace and fixed fingerprint. No source-file reading or transformation hashing; normal CacheKey validation still applies. |
| `JavaArtifactStore.engine_info()` | Sends protocol bytes [1,7] via the low-level round-trip path and JSON-decodes its response. Does not compare the returned engine/version/durability to an expected experiment configuration. |
| `JavaArtifactStore.drain_background_compaction()` | Delegates to the client's compaction-wait API. The low-level method owns its response/check contract; this adapter adds no timeout policy or measurement bucket. |
| `JavaArtifactStore._record(operation, started)` | Appends elapsed monotonic milliseconds to the operation's observation list. Caller records only after normal completion, so these observations exclude failed whole adapter operations. |
| `JavaArtifactStore.load_cached_bytes_many(keys)` | Delegates to the shared packed-value loader with copied-byte results. Missing keys are absent from the dictionary. |
| `JavaArtifactStore.load_cached_views_many(keys)` | Delegates with read-only memoryview slices. They retain their immutable RPC response storage, not a daemon mmap file or snapshot lease. |
| `JavaArtifactStore._load_cached_many(keys, *, views)` | Materializes requested keys, chunks up to 64, gets packed values using encoded CacheKeys, iterates positional results and inserts hits into a dictionary. Returns slices or bytes copies; closes each parent packed batch in finally. Records one overall lookup observation, including all chunks/decoding/copying, on success. Duplicate input keys collapse in the returned dictionary; server calls still follow the supplied sequence. |
| `JavaArtifactStore.cached_artifact_ids(keys)` | Chunks materialized keys up to 4096, calls contains_many and returns `{key: key}` for present keys. Presence is not a payload read/validation or a stable snapshot. It does not record lookup latency in the adapter's observation list. |
| `JavaArtifactStore.commit_bytes_many(entries, **metadata)` | Materializes entry mappings, uses cache_key/data, chunks at 64 entries or a heuristic 60 MiB budget, and calls the real client's put_many. Rejects a single payload above 60 MiB. Adds 256 bytes per staged entry as estimated overhead. Metadata kwargs are ignored. Records one successful publish duration and returns SimpleNamespace(size=payload_length) entries, not full artifact/provenance receipts. |
| `JavaArtifactStore.reset_operation_metrics()` | Replaces observation dictionary and resets the client's connection/request/opcode counters. Does not close/reconnect the client, clear stored artifacts, clear request_traces or reset every low-level trace/error field. A reused connection can perform requests with zero new connection openings. |
| `JavaArtifactStore.operation_observations()` | Returns a new dictionary and copies each latency list. Callers can aggregate without modifying these internal lists; this is not synchronized against concurrent use. |
| `JavaArtifactStore.operation_metrics(observations=None)` | Uses supplied observations or current ones, ignores empty lists and computes count, arithmetic mean, max and sorted p95 at min(n-1, int(n*0.95)). No interpolation or merging of backend throughput; inputs are milliseconds. |
| `JavaArtifactStore.close()` | Closes the low-level client. Does not stop the external daemon, drain compaction or remove its database. |

### Copy and Commit Limits

Closing the packed batch releases its parent memoryview; already created child
slices retain the response bytes until released. The view path avoids the adapter's
`bytes(value)` copy, not socket transfer, server-side reads or later tensor work.
Read-only slice exposure is not general zero-copy CUDA training. See the
[value reference](PYTHON-CACHE-VALUE-FUNCTIONS.md) for status parsing and the
[mapping reference](PYTHON-MAPPING-FUNCTIONS.md) for actual mapped-file owners.

The 60 MiB/256-byte accounting is heuristic: real wire framing and arbitrarily
long encoded keys can exceed its estimate. The low-level encoder enforces its own
frame limits. The upcoming entry's full overhead is not included in the threshold
comparison before appending it. A 60 MiB payload boundary is therefore not a promise
that every such request encodes successfully.

Multi-chunk commit is **not one atomic artifact transaction**. Earlier PUT_MANY
chunks may already be committed when a later oversized entry, encoding failure or
transport error occurs. The adapter does not roll them back. Its returned size-only
objects and optional metadata must not be confused with the filesystem prototype's
provenance records. The real Java cache owns immutable-entry and durability policy.

## Mmap Baseline Functions

The baseline owns its in-memory index, append files and optional read-only mapping.
`shared=True` adds an OS lock and journal refresh across cooperating writers;
without it, the per-instance RLock does not serialize other processes/instances.
Closing mappings is reversible: this object has no permanent closed-state guard.

| Declaration | Behavior and boundary |
| --- | --- |
| `PersistentMmapStore.__init__(root, *, shared=False, durable=False)` | Creates root, stores paths, loads any legacy index, initializes journal offset/mapping/lock/settings/metrics, then replays the journal inside a transaction. Does not start Aether or prepopulate data. Corrupt complete metadata is rejected, not treated as an empty cache. |
| `PersistentMmapStore._transaction()` | Context-manager generator holding the RLock. Nonshared mode yields directly. Shared mode opens/initializes writer.lock, obtains an exclusive one-byte Windows lock with a retry loop or Unix flock, refreshes mappings/index when journal mtime/size changes, then yields. Finally closes Windows mappings and releases the OS lock. Serializes shared reads as well as writes; no lock-acquisition timeout. |
| `PersistentMmapStore._load_index()` | Returns empty index if legacy index.json is absent; otherwise JSON-decodes an object and checks each offset/size against current data length, requiring at least four framing bytes. This is metadata bounds validation, not payload hashing or exhaustive validation of all JSON field types. |
| `PersistentMmapStore._replay_journal()` | Starts at this handle's last valid journal offset, rejects journal shrink, scans little-endian length/JSON/SHA-256 frames and updates the index after each valid batch. Stops before an incomplete tail without acknowledging it. Rejects complete checksum errors, oversize records, out-of-data references and conflicting immutable index entries. Only advances the offset after a complete validated batch. |
| `PersistentMmapStore._save_index(published)` | Canonically JSON-encodes the new batch (up to 64 MiB), truncates any unacknowledged journal tail at the last valid offset, appends length/payload/SHA-256, flushes and optionally fsyncs. On first journal creation in durable non-Windows mode, fsyncs the directory. Updates journal offset and mtime/size version afterward. Does not rewrite all historical index entries. |
| `PersistentMmapStore.contains(key)` | Returns whether lookup finds metadata. It does not map/read/hash the payload. |
| `PersistentMmapStore.clear()` | Explicit destructive fresh-cache setup under the transaction: closes mapping/stream, removes data/index/journal, clears index/offset/version and resets metrics. Leaves writer.lock. Requires no active readers; this is not live eviction or a crash-atomic clear transaction. |
| `PersistentMmapStore.lookup(key)` | Under the transaction, returns `(offset, framed_size)` or None. Shared mode can refresh the journal first. No payload integrity read. |
| `PersistentMmapStore.reset_metrics()` | Replaces the metric dictionary with zero counters/durations. Does not clear files/index or reopen a mapping. |
| `PersistentMmapStore.get(key)` | Under the transaction, returns None for a miss; lazily opens/maps the whole data file read-only, copies the indexed slice, checks length prefix and optional stored SHA-256, records successful-read counters and returns framed bytes. The returned bytes include the four-byte prefix. Mapping creation/read/hash failures do not record a successful read. |
| `PersistentMmapStore.put(key, payload)` | Delegates one entry to put_many and returns that key's `(offset, framed_size)`. No separate write protocol. |
| `PersistentMmapStore.put_many(entries)` | Preflights immutable-key conflicts under the transaction, deduplicates equal pending keys and reuses identical indexed values. If new entries remain, closes mapping/stream, appends framed payloads and optional data fsync, stages index additions and writes their checksummed journal batch. Rolls back those in-memory additions on journal failure but leaves appended data/evidence. Records successful append counters only afterward; no payload-copy promise for caller-mutated input. |
| `PersistentMmapStore.close()` | Closes mapping then file handle and clears those references. Does not flush an uncommitted batch, delete files or mark the object unusable; later get may map again. Close itself does not acquire the transaction lock. |

### Framing, Publication, and Failure

Data records are a four-byte unsigned little-endian payload length followed by
payload bytes. Index offsets/sizes refer to that whole record. New entries store
SHA-256 of the **framed** record. A legacy entry may have no hash: get validates its
length but skips absent hash, while attempted identical-key publication rejects an
entry without the expected digest and requires fresh population.

Journal frames are little-endian JSON length, JSON mapping of published entries,
and a 32-byte SHA-256 over length plus JSON. A truncated final frame is invisible;
the next writer truncates it at the last validated offset. A complete corrupt frame
is a hard error. Appended data bytes without published metadata can remain orphaned;
the store does not reclaim or truncate data back to the last valid record.

With durable mode, data file flush/fsync precedes journal publication; journal
flush/fsync precedes success, with a first-journal directory fsync only on non-Windows.
There is no general power-loss experiment here. After a journal write/force or
post-write metadata failure, a failed call is not proof that no complete record
reached storage. Reopen/validate outcome before retrying; the source does not
implement a full indeterminate-commit reconciliation protocol.

Shared transactions notice journal mtime/size changes, close old mappings and replay
only newly validated records, retaining earlier index entries. This assumes
cooperating append-only writers; journal shrink is rejected and unrelated external
mutation/clear while handles live is not supported. On Windows, mapping/stream close
occurs before releasing every shared transaction, so sequential get calls may remap
each time. A close failure can prevent the following unlock in that finally body.

An mmap slice here is Python **bytes**, not an exported borrowed memoryview. The
baseline really reads through read-only mmap but still copies the selected record.
SHA work is part of successful read timing. Misses and failed reads are not included
in readMs; lock acquisition/replay occurs before its timer. bytesRead counts framed
bytes; bytesAppended excludes journal/lock writes. remaps counts created mappings,
not disk I/O. These counters cannot establish physical write amplification.

## Process Resource Functions

| Declaration | Behavior and boundary |
| --- | --- |
| `snapshot(pid=None)` | Uses current PID or int-converts the supplied one. Reads Linux /proc stat fields after the final closing parenthesis in comm, CPU clock ticks, fault counters, VmRSS/VmHWM and, when readable, I/O byte counters. Returns available fields and an unavailableReason for supported parse/OS failures. I/O read permission failure can leave CPU availability true with I/O keys absent. No psutil fallback or recursive child aggregation. |
| `delta(before, after)` | Requires same PID and both available flags to compute declared cumulative counter differences, clamping negative differences to zero. Missing counters become None. Always carries after's RSS/peak values when present, even if deltas are unavailable. Adds scope metadata. Does not detect PID reuse through a process start identity or convert lifetime RSS peaks into interval peaks. |

COUNTERS is CPU seconds, minor/major faults and disk read/write bytes. On Windows
without matching /proc files, observations are unavailable, not invented zeros.
CPU time may span multiple cores; read/write bytes reflect Linux process accounting
and exclude page-cache hits, not a hardware device meter. RSS is an endpoint and
VmHWM a process-lifetime high-water value. An error after CPU parsing may leave
some fields populated; inspect availability and each field rather than assuming
an all-or-nothing schema.

## Verification Scope

[test_paper_storage.py](../../clients/python/tests/test_paper_storage.py) covers
torn journal tails, immutable conflicts, cooperating shared handles, complete
journal corruption, remapping/data corruption, malformed legacy JSON, and benchmark
reuse/parity. This is not an exhaustive format fuzzer or power-loss test matrix.

The [documented-contract tests](../../scripts/tests/test_pipeline_documented_contracts.py)
use the real adapter with fake transport responses to
check copied/retained values, chunking, partial multi-chunk publication and metric
resets, plus real temporary baseline files and modeled /proc observations. They
do not certify real daemon/GPU throughput, remote storage or cross-process locking.
The source inventory independently checks qualified function entries and the
website checks rendering; neither substitutes for those implementation contracts.
