# Engine Function Reference

[Code tour](CODE-TOUR.md) | [Architecture](ARCHITECTURE.md) | [Storage engine](STORAGE-ENGINE.md)

[Heap MVCC functions](MVCC-REFERENCE-FUNCTIONS.md) describes the reference version
store and sequence allocator beneath the in-memory implementation.

This reference describes concrete functions in the current checkout, including
package-private helpers. Names in code formatting identify source symbols; linked
class names open their implementations. Overloads are described together only
when their shared operation and differences are explicit.

## Architecture And State

The byte database owns keys, values, sequence assignment and resource lifetimes.
It does not interpret a Java record, execute a transform or train an ML model.
The typed wrapper encodes application records before calling this layer; the
training cache stores encoded immutable artifacts above it.

There are two implementations of the same interface:

- `InMemoryAetherDatabase` is a single-threaded, heap-backed semantic reference.
  It stores multiple versions through `VersionedKeyValueStore`. It has no WAL,
  file lock or persistence barrier.
- `PersistentAetherDatabase` coordinates a native mutable memtable, an append-only
  WAL, immutable table readers and a manifest-backed `VersionSet`. Its database
  monitor protects public reads and commit-group mutation application. A single
  compaction worker builds outside that monitor and rechecks inputs before install.

The central read boundary is `lastVisibleSequence`: a normal read uses its current
value; a snapshot captures an earlier value. Sequence numbers select versions,
not wall-clock timestamps. Tombstones are versions too. Closing a snapshot can
allow future compaction to discard history; it does not itself compact anything.

The ownership chain matters independently of version selection. A `WriteBatch`
copies caller arrays. A `LookupResult` owns immutable bytes and its `value()`
returns another copy. A cursor materializes rows. A snapshot owns a registration,
not its own database. Closing an owning wrapper closes its underlying database.

## Aether: Composition Root

Source: [Aether.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/Aether.java).
Use this class for ordinary application construction. The private `Aether()`
constructor prevents an instance of the static factory class.

### openInMemory()

Allocates a new `InMemoryAetherDatabase` and returns the public `AetherDatabase`
interface. Every call creates independent state. No directory is opened and no
background storage service starts. The caller owns `close()`.

### open(Path) And open(Path, AetherConfiguration)

Delegate to persistent construction. The path identifies one local database
directory, not a remote endpoint. The configured overload passes the resolved
configuration through validation and runtime extraction; the path-only overload
uses the persistent implementation's development-profile defaults.

Opening may create metadata for a new database or validate/recover an existing
one. A process-exclusive directory lock is acquired. A failed open is not an
instruction to replace the directory with empty files: identity, authoritative
table or manifest corruption must remain an error.

### openInMemoryWithMetrics() And openWithMetrics(Path)

Construct the appropriate database, then call `instrument`. They return the
`MeteredAetherDatabase` decorator rather than changing storage algorithms.
The decorator owns the database it wraps. Operation measurements do not replace
format validation or durability evidence.

### instrument(AetherDatabase)

Constructs `DefaultMeteredAetherDatabase` around an existing byte database and
transfers close ownership to the wrapper. This is an instrumentation boundary,
not a copy of the underlying database or an additional store.

### compactionDiagnostics(AetherDatabase)

For a direct `PersistentAetherDatabase` instance, returns its bounded compaction
diagnostic map. Otherwise returns a map containing `state = UNAVAILABLE`.
The type test is on the object supplied: an arbitrary decorator is not unwrapped
by this function. Unavailable therefore does not prove the underlying storage is
in-memory.

### awaitCompactionIdle(AetherDatabase)

For a direct persistent instance, waits through its compaction coordinator with
the implementation's 60-second timeout. For other objects it is a no-op.
It does not flush new data, change compaction policy or turn background work into
a new benchmark endpoint. Interruption is propagated.

## WriteBatch: Owned, One-Shot Mutations

Source: [WriteBatch.java](../../modules/aether-api/src/main/java/io/aetherdb/api/WriteBatch.java).
The batch retains insertion order, including repeated mutations to one key.
It is not a reusable builder after submission.

### WriteBatch(), put(byte[], byte[]) And delete(byte[])

The constructor creates an empty `OPEN` batch. `put` validates both arrays,
accounts for encoded size, then clones key and value into a `Put` mutation.
`delete` performs the same key/size checks and clones the key into a `Delete`.
Both return the same batch for chaining. They require `OPEN` state.

The limits are 10,000 operations, 32 MiB estimated encoded bytes, 65,536 key bytes
and 16 MiB per value. Null is rejected; zero-length keys and values are not
rejected by these validators. Mutation accessors copy bytes again, so retaining
or changing an accessor result cannot alter the stored mutation.

### operationCount(), size(), isEmpty() And encodedSizeBytes()

`operationCount` reads the accumulated list size; `size` delegates to it.
`isEmpty` checks the list. `encodedSizeBytes` returns zero for an empty batch or
the current size estimate. These methods use `ensureReadable`, which rejects
`CLOSED` but permits the submission and terminal states. The estimate is a batch
encoding bound, not an estimate of total WAL, SSTable or filesystem overhead.

### state(), isClosed() And mutations()

`state` returns the exact lifecycle enum. `isClosed` returns true for every state
other than `OPEN`, including `SEALED` and `SUCCEEDED`; it does not mean only
explicit close. `mutations` returns an unmodifiable ordered list view after a
readability check. It is not a deep-copied list snapshot, although its mutation
objects keep byte ownership private.

### sealForSubmission() And markSubmitted()

`sealForSubmission` requires `OPEN` and changes it to `SEALED`: one writer has
claimed the batch. `markSubmitted` requires `SEALED` and changes it to
`SUBMITTED`: the implementation has crossed its uncertainty boundary.
These transitions are separate so definite pre-submission failure can be
distinguished from a write whose final durable outcome is unknown.

### markSucceeded(), markCommitted(), markFailed() And markIndeterminate()

`markSucceeded` accepts `SEALED` or `SUBMITTED` and writes `SUCCEEDED`.
`markCommitted` is the reference engine's compatibility alias for that method.
`markFailed` accepts the same two source states and writes `FAILED`; its caller
must have evidence of a definite non-commit outcome. `markIndeterminate` accepts
only `SUBMITTED` and writes `INDETERMINATE`. Illegal transitions throw
`IllegalStateException`. None of these methods themselves write data or force a
file: they record the outcome chosen by database orchestration.

### close()

Changes an `OPEN` batch to `CLOSED`. It does not rewrite a submitted/terminal
state, remove stored mutations or undo a successful write. This makes
try-with-resources usable around submission without obscuring its outcome.

### addSize(int, int)

Rejects a full operation list before adding another entry. Adds the 24-byte batch
header once, then 12 bytes of per-operation overhead plus key/value lengths using
`Math.addExact`. Rejects a candidate larger than 32 MiB before storing the new
estimate. The operation itself is appended by `put` or `delete` afterward.

### validateKey(byte[]), validateValue(byte[]) And State Guards

The validators reject null and their respective length limits.
`ensureOpen` rejects any non-`OPEN` state with `AetherClosedException`.
`ensureReadable` rejects only `CLOSED`. `requireState` checks one exact state and
throws `IllegalStateException` on mismatch. These are lifecycle/input guards,
not transaction admission or disk-pressure checks.

### Put And Delete Members

The private `Put(byte[], byte[])` and `Delete(byte[])` constructors retain the
arrays already cloned by the enclosing batch. `Put.key()`, `Put.value()` and
`Delete.key()` return `Arrays.copyOf` results. The sealed `Mutation.key()`
contract exposes that defensive-copy boundary to database implementations.

## InMemoryAetherDatabase: Semantic Reference

Source: [InMemoryAetherDatabase.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/InMemoryAetherDatabase.java).
This class is explicitly single-threaded. Its behavior is useful for checking
MVCC semantics but is not evidence of persistent durability or concurrent group
commit behavior.

### Constructors

`InMemoryAetherDatabase()` starts at visible sequence zero with 1,024 permitted
active snapshots. `InMemoryAetherDatabase(long)` changes the initial sequence,
primarily for overflow tests. `InMemoryAetherDatabase(long, int)` validates the
snapshot limit (1 through 65,536), creates `SequenceSource` and initializes the
visible boundary. The initial sequence does not create matching stored records.

### put(byte[], byte[]) And delete(byte[])

`put` checks database lifetime, copies the key through `ByteKey.copyOf`, copies
the non-null value, reserves one sequence, inserts a value record, then advances
`lastVisibleSequence`. `delete` follows the same sequence path but inserts a
tombstone with no value. Repeated writes retain older versions for older read
boundaries; neither function erases the key's complete history.

These direct reference-engine methods do not pass through `WriteBatch`, so do
not infer its batch-size/key/value limits from this path. Sequence exhaustion
is checked by `SequenceSource` before inserting the record.

### get(byte[]) And get(byte[], Snapshot)

Both check database lifetime and copy/validate the lookup key. The ordinary
overload resolves at `lastVisibleSequence`; the snapshot overload first validates
the supplied handle and resolves at its captured sequence. Neither advances the
sequence source or mutates stored records. Absence and a visible tombstone both
become `LookupResult.notFound()`.

### newSnapshot()

Checks the database is open, enforces the active-snapshot limit and rejects an
exhausted snapshot ID. Allocates a `SnapshotHandle` containing database identity,
new ID and current visible sequence. Its release callback removes that exact
handle from the identity-based registration set. The holder array permits the
callback to refer to the handle being constructed. A snapshot does not clone the
store or copy values.

### scan(byte[], byte[]) And scan(byte[], byte[], Snapshot)

Select the current or validated snapshot sequence and delegate to `scanAt`.
The range is start-inclusive and end-exclusive. `scanAt` copies both endpoints,
rejects start greater than end, and produces an empty list for equal endpoints.
Otherwise it asks the reference store for visible entries, then wraps the
materialized list in `ListCursor`.

### scanAll() And scanAll(Snapshot)

Ask the reference store for all visible entries at the current or validated
snapshot sequence and return a `ListCursor`. They do not implement an unbounded
lazy stream. Later writes do not change the cursor's already selected row list.

### write(WriteBatch) And write(WriteBatch, WriteOptions)

The one-argument overload supplies `WriteOptions.defaults()`. The result-returning
overload checks lifetime and non-null arguments, seals the batch and prepares all
mutations as owned keys/values before applying anything. Preparation failure
marks the batch `FAILED` and propagates the exception.

An empty prepared list succeeds with operation/sequence counts zero and no
barrier. Otherwise reserves one contiguous sequence range for the entire list;
reservation failure also marks definite failure. It marks `SUBMITTED`, inserts
the prepared value/tombstone records in order, advances visibility to the range's
last sequence and marks success through `markCommitted`. `WriteResult` reports
the requested durability mode but its persistent-barrier flag is false: this
engine never forces a WAL, even for a synchronous request.

The mutation-application loop has no rollback handler. Do not generalize its
happy-path atomic visibility into a guarantee that arbitrary VM/allocation
failures during reference insertion are recovered transactionally.

### lastVisibleSequence() And retainedVersionCount(byte[])

The first returns the read boundary after a lifetime check. The second copies
the supplied key and returns the number of retained versions in the reference
store. That count includes historical records rather than only currently visible
values and does not represent persisted SSTable counts.

### restoreVisible(byte[], byte[], long)

A package-private recovery hook. Requires an open database and a sequence in
the interval zero through `lastVisibleSequence`; copies the key/value and inserts
a value record at the supplied sequence. It does not allocate a new sequence,
advance visibility or insert a tombstone. Ordinary callers should use writes.

### isClosed(), close() And ensureOpen()

`isClosed` reads the lifetime flag. `close` invalidates a copy of the registered
snapshot set, clears registrations and sets `closed`. It does not force or
persist anything. `ensureOpen` throws `AetherClosedException` after close.
Closing the reference implementation does not expose or return its internal
record map to callers.

### resolve(ByteKey, long), validateSnapshot(Snapshot), key(byte[]) And value(byte[])

`resolve` asks the store for the version visible at the boundary; missing or
tombstone returns absence, while a value is copied into a found result.
`validateSnapshot` requires the internal handle type, this database's identity
object and an open handle. A handle from another database is rejected even when
its sequence is numerically valid. `key` delegates to `ByteKey.copyOf`; `value`
rejects null and returns `Arrays.copyOf`.

The private `PreparedMutation` record carries the prepared key, nullable value
and tombstone flag during a batch submission. Its generated accessors are data
holders, not additional database operations.

## Persistent Public Entry Points

Source: [PersistentAetherDatabase.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentAetherDatabase.java).
This implementation is package-private. Its internal flush, group-commit,
compaction and recovery algorithms also participate in these calls; read
[Persistent engine internals](PERSISTENT-INTERNALS.md) for private functions and
[Storage engine](STORAGE-ENGINE.md) for publication ordering.

### open(Path) And open(Path, AetherConfiguration)

The path-only overload supplies the development security profile. The configured
overload validates settings, normalizes/creates the directory, validates the
filesystem root and acquires `DatabaseLock`. It initializes or validates the
identity/options pair, creates or recovers the manifest, establishes a checkpoint
WAL when needed and removes obsolete WAL files other than the required segment.

It opens every authoritative table with the expected database identity/metadata,
opens the required WAL, creates a native budget/memtable and replays recoverable
groups. The WAL is truncated and positioned at recovery's valid end before
constructing the database. Failures close every acquired resource with suppressed
cleanup errors and surface `DatabaseOpenException`; corrupt authority is not
silently replaced by an empty database.

### PersistentAetherDatabase Constructor

Takes ownership of the acquired lock, WAL, version set, readers and memtable.
Initializes write pressure from runtime settings, visible sequence and replayed
WAL record count, memtable numbering and the next table-file number. Creates one
`CompactionCoordinator` with `runBackgroundCompaction` as its worker callback and
requests compaction if the recovered inventory already needs it.

### put(byte[], byte[]) And delete(byte[])

Build a temporary `WriteBatch`, append one mutation, submit through `write` and
close the batch through try-with-resources. Therefore these persistent entry
points inherit `WriteBatch` validation/copies and coordinator semantics. They
do not bypass WAL or write-pressure admission merely because there is one entry.

### write(WriteBatch) And write(WriteBatch, WriteOptions)

The void overload uses the database's configured default write options. The
explicit overload null-checks both arguments and hands them to
`CommitCoordinator.submit`. It does not directly apply the mutation list on this
line: the coordinator groups requests and completes each caller's outcome after
`processCommitGroup`. Reads and application of a group share the database monitor.

### get(byte[]) And get(byte[], Snapshot)

Both are synchronized. They call `lookup` at the current visible sequence or
the sequence from a validated snapshot. The synchronization prevents a public
reader observing the middle of a coordinator's mutation application.

### lookup(byte[], long)

Checks lifetime and key size, then asks the active memtable for the visible
version. A value returns a copied found result; a tombstone returns absence.
Only memtable absence continues to tables. It visits current readers, compares
found/tombstone sequence numbers and keeps the greatest visible sequence.
The newest visible table tombstone hides older values. Read diagnostics count
lookups/tables and time the memtable/table-selection work without changing the
selection rule.

### newSnapshot() And validateSnapshot(Snapshot)

Snapshot creation is synchronized, enforces the configured active limit and ID
range, and registers a handle at `lastVisibleSequence`. The release callback
removes its registration under the database monitor. Validation checks internal
handle type, exact database identity and open lifetime. Snapshot retention feeds
compaction planning; it does not pin an independent file inventory per handle.

### scan Overloads, scanAll Overloads And scanAt(byte[], byte[], long)

The synchronized entry points select the current or snapshot boundary. `scanAll`
uses null endpoints internally. `scanAt` validates non-null endpoints and rejects
a reversed range, gathers user keys from the active memtable and all table
entries into an unsigned-byte-ordered `TreeSet`, then resolves each in-range key
through `lookup`. It materializes only visible values into a
`PersistentListCursor`.

This path can enumerate and decode tables before resolving individual keys. It
is not a zero-copy block iterator and does not have the same allocation behavior
as the authoritative streaming verifier. Start is inclusive, end is exclusive,
and duplicate keys are deduplicated before row selection.

### isClosed(), close() And closeResources()

`isClosed` returns the flag under the database monitor. `close` serializes closers
using `closeMonitor`, sets `closing`, stops/joins the compaction coordinator
outside the database monitor and invokes `closeResources` under that monitor.
This separation avoids waiting for a worker while holding the monitor it needs.

`closeResources` first attempts a WAL force and flush, skipping flush after a
recorded compaction-manifest failure. It then marks closed, invalidates snapshots
and closes the native memtable, table readers, version set, WAL and directory
lock. Failures are collected rather than preventing later cleanup; an aggregated
`AetherException` can be thrown after resources have been closed. Close therefore
is not a promise that a preceding uncertain write has become definitely absent.

## SnapshotHandle: Registration Lifetime

Source: [SnapshotHandle.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/SnapshotHandle.java).
Its constructor stores database identity, numeric ID, retained sequence and a
release callback. These fields identify one registration; they do not allocate
a second store.

- `identity()` returns the opaque owner identity used for cross-database checks.
- `id()` returns the handle ID, including after close; it is not a sequence.
- `sequence()` calls `ensureOpen()` before returning the captured read boundary.
- `retainedSequence()` returns that boundary without checking closed state.
  Planning may conservatively retain history for a closed handle awaiting removal.
- `ensureOpen()` throws `SnapshotException` when invalidated or closed.
- `isClosed()` reads the lifetime flag.
- `close()` sets the flag and invokes release only once. Repeated close does not
  run the callback again.
- `invalidate()` sets the flag without invoking release. Database teardown uses
  this when it will clear the entire registration set itself.

## Materialized Cursors

Sources: [ListCursor.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/ListCursor.java)
and [PersistentListCursor.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentListCursor.java).
The two implementations have the same row-positioning algorithm and different
database-lifetime checks.

### Constructors And next()

Each constructor retains its owning database and a `List.copyOf` of selected
visible rows, starting at index -1 (before the first row). The list copy is
shallow; byte ownership comes from the `VisibleEntry` objects, not `List.copyOf`.
`next` validates usability, increments the index when another row exists and
returns true. At exhaustion it sets the index to list size and returns false.
Repeated `next` after exhaustion stays false; it does not wrap around.

### key(), value() And current()

The accessors delegate through `current` to the selected `VisibleEntry`'s byte
accessors. `current` validates lifetime and requires index within the row list.
Access before the first successful `next`, or after exhaustion, throws
`IllegalStateException`. These functions do not advance the cursor.

### isClosed(), close() And ensureUsable()

`isClosed` returns the cursor's own flag, not the database's flag. `close` sets
that flag; it does not close the database or drop a snapshot registration.
The reference cursor calls its database's `ensureOpen`; the persistent cursor
checks `database.isClosed`. Both reject their own closed state with
`AetherClosedException`. A cursor whose database has closed is unusable even if
its own `isClosed()` is still false.

## LookupResult: Copies And Validation Identity

Source: [LookupResult.java](../../modules/aether-api/src/main/java/io/aetherdb/api/result/LookupResult.java).
Its private constructor stores an already owned value array; null represents
logical absence. Absence is not used to hide corruption or I/O errors.

### found(byte[]), notFound() And isFound()

`found` rejects null and copies input through `ReadDiagnostics.copy` into a new
result. `notFound` returns the shared absence singleton. `isFound` tests whether
the privately owned value is non-null. A zero-length value is still found.

### value() And readOnlyValue()

`value` returns another defensive copy through `ReadDiagnostics.copy`.
`readOnlyValue` wraps the privately owned array in a read-only `ByteBuffer`
without copying the payload. The returned buffer has its own position/limit;
the backing array is not exposed as writable memory. Both throw
`NoSuchElementException` for absence.

### validateOnce(Object, Consumer<ByteBuffer>)

Requires non-null validator identity/callback and a found value. If the opaque
identity is the same object as `validatedBy`, returns false immediately. Otherwise
synchronizes on this result, checks the identity again, passes a read-only view
to the validator and only then records successful validation and returns true.
A throwing validator does not mark the representation validated.

There is one identity slot, compared by reference rather than `equals`. Switching
validators causes revalidation. A newly loaded result starts unvalidated even
for identical bytes. This is representation-local validation reuse, not a
global assertion that every future block/value is already trusted.

## Write Result Record

[WriteResult.java](../../modules/aether-api/src/main/java/io/aetherdb/api/WriteResult.java)
is successful synchronous write metadata, not a request or storage barrier itself.

| Function | Behavior and Limits |
| --- | --- |
| `WriteResult.WriteResult(operationCount, firstSequence, lastSequence, requestedDurability, durabilityBarrierPerformed)` | Rejects negative count/sequences and null durability. Empty writes require both sequences zero; nonempty writes require positive first and last at least first. Does not require sequence-range length to equal operationCount or check barrier/durability consistency. |
| `WriteResult.hasSequenceRange()` | Returns operationCount > 0, relying on constructor invariants; does not inspect storage files or barrier flag. |

Implicit record accessors return the five components. Component-based equals,
hashCode and toString are compiler-supplied. requestedDurability is the requested
mode; durabilityBarrierPerformed reports whether a barrier occurred, not an
independent assertion of recovery under every crash/power-loss model. Constructors
permit a caller-created result with inconsistent policy metadata; actual engine
behavior, not merely constructing this record, establishes write evidence.

## Cursor API and Snapshot Limit Failure

[AetherCursor.java](../../modules/aether-api/src/main/java/io/aetherdb/api/AetherCursor.java)
is the public closeable scan contract implemented by the engine cursors described
above. It declares operations, not a storage strategy: materialization, retained
read views and close behavior must be read in the specific implementation.

| Function | Contract |
| --- | --- |
| `AetherCursor.next()` | Advances to the next visible entry; true means positioned. Call before reading the first key/value. The interface does not promise a particular iteration implementation. |
| `AetherCursor.key()` | Returns defensive copy of current key; throws IllegalStateException if not positioned. |
| `AetherCursor.value()` | Returns defensive copy of current value; throws IllegalStateException if not positioned. It is not the training-cache mapped-value API. |
| `AetherCursor.isClosed()` | Reports released-resource state; true after close. Not a substitute for closing a live cursor. |
| `AetherCursor.close()` | Releases held resources through AutoCloseable; use try-with-resources. Interface alone does not define every post-close operation or thread-safety guarantee. |
| `SnapshotLimitExceededException.SnapshotLimitExceededException(message)` | Delegates supplied message to AetherException; no retry, limit adjustment, snapshot disposal or extra state. |

[SnapshotLimitExceededException.java](../../modules/aether-api/src/main/java/io/aetherdb/api/exceptions/SnapshotLimitExceededException.java)
is thrown by both embedded engines' newSnapshot paths when active handle count
is already at the configured maximum, before allocating the next handle. It is
distinct from exhausted snapshot IDs or using a closed/foreign snapshot. Closing
an owned snapshot removes its active handle and can free capacity; ignoring this
exception or closing a cursor does not necessarily release a separate snapshot.
The exception's serialVersionUID is serialization metadata, not a persistent
database error-format identifier.

## Regression Boundaries

Semantic changes belong with [InMemoryAetherDatabaseTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/InMemoryAetherDatabaseTest.java).
Persistence claims also need [PersistentAetherDatabaseTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/PersistentAetherDatabaseTest.java),
and background installation changes need [BackgroundCompactionTest](../../modules/aether-engine/src/test/java/io/aetherdb/engine/BackgroundCompactionTest.java).

For a new function or changed contract, check normal input, empty input, lifetime,
ownership, boundary/overflow and failure behavior independently. A semantic
reference test cannot prove file durability; a successful read after close/reopen
cannot by itself prove every injected crash boundary.
