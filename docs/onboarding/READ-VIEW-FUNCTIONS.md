# Read View and Snapshot Ownership Functions

[LSM iterators](LSM-ITERATOR-FUNCTIONS.md) | [Persistent internals](PERSISTENT-INTERNALS.md)

This reference describes the current checkout, not necessarily the implementation
already published on `main`. Read topology, source lifetime, and snapshot visibility
are separate concepts; none of these classes performs an SSTable lookup itself.

## Ownership Architecture

`ReadTopology` describes active and immutable memtables, a table version, and a
visibility sequence. A `ReadViewManager` constructs a `ReadView` and retains its
components. The manager owns one view reference. Each successful `pinCurrent`
adds another, released through its handle. Publishing a replacement retires the
old owner reference; existing reader references keep old components alive.

`SnapshotRegistry` separately tracks visibility sequences as a multiset. It does
not retain those components. Its oldest sequence informs version reclamation,
while view handles protect physical source lifetime during reads. The engine must
coordinate both mechanisms and prevent incompatible lifecycle transitions.

## Topology and Source Contract

Sources: [ReadTopology.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/read/ReadTopology.java)
and [RetainedSource.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/read/RetainedSource.java).

### `ReadTopology(activeMemTable, immutableMemTables, version, visibleSequence)`

Copies the immutable-source list with `List.copyOf` and rejects negative visibility.
Active source and version may be null. The constructor does not retain sources,
validate newest-first ordering, reject repeated source objects, or freeze the
underlying memtables. Its implicit accessors return supplied sources, copied list,
and scalar visibility; it is immutable metadata, not immutable source contents.

### `RetainedSource.retain()` and `release()`

The interface delegates component-specific lifetime accounting to implementations.
It has no universal lease type or rollback guarantee. A view assumes successful
retain calls can later be paired with release. Implementations that mutate state
and then throw require particular care during construction rollback.

## View Construction and References

Source: [ReadView.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/read/ReadView.java).

### `ReadView(generation, topology)` and `retainComponents()`

Package-private construction captures visibility, active source, copied immutable
list, version, and creation time. Starts reference count at one, representing the
publisher's owner reference. `retainComponents` retains active first, immutables
in list order, and version last.

On a runtime exception or error, rollback releases successfully counted immutables
in reverse order and active if retained. It does not track a version retain that
changes state and then throws. A rollback release failure can interrupt remaining
cleanup and replace the original exception; cleanup failures are not accumulated
as suppressed exceptions here.

### `tryRetain()`

While not retired, reads the count and attempts a CAS increment. A zero count
returns false. After increment it rechecks retirement: if retired in the meantime,
it releases that increment and returns false; otherwise succeeds. This keeps new
pins from intentionally acquiring retired views while allowing an already retained
view to outlive replacement. Counts have no explicit integer-overflow guard.

### `retireOwner()` and `release()`

Retirement sets the volatile retired flag and releases the owner reference. This
method is not independently idempotent; the manager must call it once per view.
`release` decrements atomically and throws on underflow without restoring the count.
At zero it releases version first, immutable sources in reverse order, then active.

If one component release throws, subsequent component releases are skipped. The
reference count is already zero, so another release is not a cleanup retry. This
class does not aggregate cleanup exceptions or recover partially released sources.

### View accessors

`generation()` returns the captured publication number; `visibleSequence()` returns
the captured boundary. `activeMemTable()` and `version()` return nullable component
references; `immutableMemTables()` returns the copied immutable list.
`createdAt()` returns the construction instant. `isRetired()` reads the retirement
flag; `referenceCount()` reads current owner-plus-reader references.

None of these accessors checks liveness or retains another reference. A caller
must not keep using component references after releasing its handle merely because
the Java `ReadView` object remains reachable. Retired means the manager released
ownership, not necessarily that all components are already destroyed.

## Closeable Reader Pin

Source: [ReadViewHandle.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/read/ReadViewHandle.java).

### `ReadViewHandle(view)`, `view()`, `isClosed()`, and `close()`

The package-private constructor stores an already retained view; it does not
increment the count itself. `view` rejects a closed handle; `isClosed` reads the
atomic flag. Close uses a CAS to release exactly once, setting closed before
calling view release. If release throws, later closes do not retry.

A successful `view()` check is not a lock against another thread closing that
same handle immediately afterward. Treat a handle as caller-owned for its usage
scope, rather than concurrently closing it while using its returned components.

## Manager Publication

Source: [ReadViewManager.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/read/ReadViewManager.java).

### `ReadViewManager(initial)`

Creates an empty atomic current reference and zero generation, then publishes the
initial topology. First successful construction normally publishes generation 1.

### `pinCurrent()`

Repeatedly reads current and attempts `tryRetain`. Null current throws a closed
manager error. A retired view causes a retry, allowing a reader to pin a replacement.
The returned handle owns the successful increment. It may pin a view that is
replaced just afterward; that is safe lifetime retention, not guaranteed newest
visibility at the moment the caller later inspects it.

### `publish(topology)`

Requires nonnull topology, rejects an observed null current after generation has
advanced, increments generation, and constructs a replacement retaining components.
Atomically swaps current, then retires the previous owner if present.

Generation advances before replacement construction and is not rolled back on
failure. The new view is installed before old-owner release; a release exception
therefore does not undo publication. There is no explicit sequence-monotonicity
check or generation overflow check.

The closed check, construction, and swap are separate operations. Concurrent
publish/close can publish after close has cleared current; concurrent publications
can install generations in an order different from their increments. Owners must
serialize the relevant publication/lifecycle transitions. Do not infer those
guarantees from the atomic fields alone.

### `close()`

Swaps current to null and retires the previous owner. Normally idempotent for a
serialized owner and rejects subsequent pins. Existing reader pins survive until
their handles close. If owner retirement throws, current is already null. Close
does not wait for readers or forcibly release their references.

## Snapshot Multiset

Source: [SnapshotRegistry.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/read/SnapshotRegistry.java).

### `SnapshotRegistry()` and `register(sequence)`

Construction creates an empty ordered map and zero count. Synchronized register
rejects negative sequences, increments multiplicity at that sequence, increments
total count, and creates a registration. Several snapshots can share the same
sequence and still count as separate handles. Counters use ordinary integer
arithmetic without overflow guards; sequence zero is valid.

### `release(sequence)`

Private synchronized release rejects a missing sequence, removes a multiplicity
of one or decrements it, then decrements total count. It changes only the multiset;
there is no native-memory, SSTable, or read-view release here.

### `oldestSequence()`, `newestSequence()`, and `activeCount()`

Synchronized observations return first/last registered sequence, or -1 for an
empty registry. Count is registration cardinality, not distinct sequence count.
The -1 empty sentinel is not directly a valid compaction-dropping boundary; callers
must choose an appropriate fallback visibility sequence.

### `Registration(registry, sequence)`, `sequence()`, `isClosed()`, and `close()`

The package-private constructor stores registry and sequence. `sequence` remains
readable after close. Synchronized `isClosed` tests the cleared registry reference.
Synchronized close clears ownership then calls registry release exactly once.
On release failure ownership remains cleared, so close does not retry. Holding a
registration object protects a sequence in the multiset until close, not a file
or native-region lifetime.

## Review Boundaries

Trace every successful pin to handle close and every snapshot registration to its
own close. Distinguish a retired view from a zero-reference view. Audit engine
locking around publication, shutdown, snapshot acquisition, and compaction fallback
boundaries. Function-name coverage does not prove lifecycle race freedom, complete
exception cleanup, or snapshot reclamation correctness.
