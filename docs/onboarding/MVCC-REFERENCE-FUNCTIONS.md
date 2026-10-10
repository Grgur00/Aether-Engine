# Heap MVCC Function Reference

[Engine functions](ENGINE-FUNCTIONS.md) | [Storage architecture](STORAGE-ENGINE.md)

[Native memtable functions](NATIVE-MEMTABLE-FUNCTIONS.md) covers the persistent
engine's offset-linked skiplist and lease-based region lifetime.

## Architecture And Scope

The `memtable.reference` package is the heap-backed semantic store beneath
`InMemoryAetherDatabase`. A sorted map identifies user keys; each key maps to a
second sorted map of immutable versions, descending by sequence. Every inserted
version is retained. This is a reference implementation, not the persistent
engine's native skiplist, WAL, flush scheduler or compaction implementation.

The store does not allocate sequences, register snapshots or track a visible
database boundary. The engine supplies those decisions. These classes are not
synchronized; their use must obey the in-memory engine's single-threaded contract.
Immutable keys and records prevent mutation of owned bytes, not concurrent map
mutation or batch atomicity.

| Operation | Result | Ownership |
|---|---|---|
| Store `insert` | Adds one version under a user key. | Retains immutable key/record objects. |
| Store `resolve` | Newest record at or below the supplied boundary, including a tombstone. | Returns retained immutable record, not a value copy. |
| Store `scan` / `scanAll` | Selected live values in user-key order. | Materialized list of owning key/value entries. |
| Record `copyValue` | Payload only for VALUE. | New defensive copy. |
| Key `copyBytes` | User-key bytes. | New defensive copy. |

## Version Store Functions

Source: [VersionedKeyValueStore.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/reference/VersionedKeyValueStore.java).

### Construction And `insert(key, record)`

The class has an implicit public no-argument constructor; field initialization
creates the outer `TreeMap`. `insert` finds or creates a reverse-sequence map,
then uses `putIfAbsent` under the record's positive sequence. A duplicate sequence
for the same key throws `IllegalStateException`, even if payloads are identical.
The same sequence under a different key is not rejected by this store; global
sequence assignment is the engine's responsibility.

It neither copies the already immutable objects nor validates an enclosing batch,
visibility boundary or sequence allocator. Nulls are not explicitly normalized
into a domain-specific validation error. There is no rollback facility for a
series of inserts that fails partway through.

### `resolve(key, visibleSequence)`

Returns null if the key has no versions. Otherwise iterates newest-first and
returns the first sequence no greater than the boundary. If all versions are too
new, returns null. It does not skip tombstones to reveal an older value; a selected
tombstone is the visible deletion. This traversal is linear in the versions visited,
not a direct predecessor search on the inner tree.

The method does not independently reject a negative visibility boundary; positive
stored sequences simply fail selection. Snapshot registration and validity checks
occur in the engine before this helper is used.

### `scan(startInclusive, endExclusive, visibleSequence)`

Uses an outer-map subrange with inclusive start and exclusive end. For each key in
unsigned byte order, resolves one visible record and includes it only when its
type is VALUE. It obtains copied key/value bytes, then `VisibleEntry` clones those
copies again. Returns an ordinary mutable `ArrayList`, not a lazy iterator.

Range validation comes from the underlying `subMap`; reversed bounds are not
silently normalized. Equal bounds produce an empty range. Historical versions are
not returned separately, and deleting a key does not remove its retained history.

### `scanAll(visibleSequence)` And `versionCount(key)`

`scanAll` follows the same selection/copy rules over all outer-map keys without
range bounds. `versionCount` returns the total retained count for that key, zero
if absent. It counts tombstones and invisible historical versions, not just live
values or versions visible to a particular snapshot.

### `VisibleEntry(key, value)`, `key()` And `value()`

The compact record constructor clones both arrays. Its custom array accessors
clone again, preserving ownership across materialized cursors. Direct construction
does not validate key/value sizes or nulls explicitly; null fails during cloning.
Generated record equality for arrays is identity-based, not a content comparison.
There is no sequence field: this object represents an already selected visible
key/value pair, not a complete MVCC version.

## Immutable Versioned Records

Source: [VersionedRecord.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/reference/VersionedRecord.java).

### `VersionedRecord(sequence, type, value)`

The private constructor requires positive sequence, then retains type and value.
It relies on its public factories to supply a valid type and owned payload; it
does not clone or enforce those conditions itself. `Type` distinguishes VALUE
from TOMBSTONE without treating empty values as deletion markers.

### `value(sequence, value)` And `tombstone(sequence)`

The value factory requires non-null bytes, copies the entire array and constructs
a VALUE record. Empty arrays remain valid values. The tombstone factory constructs
a TOMBSTONE with no payload, represented internally by null. Neither reserves a
sequence or mutates a store. Field-size limits belong to the engine/API layer,
not these factories.

### `sequence()`, `type()` And `copyValue()`

Scalar accessors return the immutable sequence/type. `copyValue` requires VALUE,
then returns a defensive array copy; requesting a tombstone payload throws
`IllegalStateException`. Callers must inspect type rather than infer deletion
from a zero-length copied value.

## Immutable Byte Keys

Source: [ByteKey.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/reference/ByteKey.java).

The private `ByteKey(bytes)` constructor retains its supplied array. Public
`copyOf(bytes)` rejects null and supplies a defensive copy, establishing ownership.
`copyBytes()` returns another copy. Neither method imposes an API key-size limit.

`compareTo(other)` delegates to unsigned byte ordering. `equals(other)` uses object
identity as a fast path, otherwise compares array content for another `ByteKey`.
`hashCode()` is the corresponding array-content hash. Thus independently copied
keys with identical bytes identify the same outer-map key; source-array mutation
after `copyOf` cannot change that identity.

## Unsigned Ordering

Source: [UnsignedBytes.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/reference/UnsignedBytes.java).

Private `UnsignedBytes()` prevents instantiation. `compare(left, right)` compares
each byte as an unsigned integer, returning the first difference or comparing
length when one is a prefix. `COMPARATOR` references that method for sorted
collections. Null arrays have no ordering contract and fail on access.

This is user-key ordering only. It is not the SSTable internal-key comparator,
which additionally orders versions by descending sequence and type.

## Sequence Allocation

Source: [SequenceSource.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/reference/SequenceSource.java).

### `SequenceSource()` And `SequenceSource(lastAssigned)`

Default construction starts at zero, which is reserved rather than assigned.
Explicit construction requires a nonnegative prior boundary. The allocator has
no synchronization, persistence or reset/rollback operation.

### `reserve(count)` And `reserveOne()`

`reserve` requires a positive count and checks available space before changing
the counter. Insufficient space throws `SequenceExhaustedException` and leaves
`lastAssigned` unchanged. Otherwise it records the inclusive range from prior
boundary plus one through boundary plus count and returns it. `reserveOne`
delegates to a one-element reservation and returns its first scalar.

A successful reservation consumes the numbers immediately; it does not guarantee
that later application succeeds or that the database has made them visible.
Visibility and partial-failure handling belong to the engine.

### `lastAssigned()` And `SequenceRange`

`lastAssigned` returns the allocator boundary without modifying it.
`SequenceRange(first, last)` is a record with generated accessors and no explicit
validating constructor; direct construction can bypass the invariants established
by `reserve`. It does not represent an independently durable transaction receipt.

## Test And Integration Boundary

Read the [engine function reference](ENGINE-FUNCTIONS.md) for snapshot ownership,
batch lifecycle and cursor materialization above this store. Function-name
inventory verifies documentation mentions declared helpers; it does not prove
thread safety, all MVCC cases or parity with the native persistent path.
