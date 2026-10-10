# LSM Iterator and Version Reclamation Functions

[Compaction planning](COMPACTION-PLANNING-FUNCTIONS.md) | [Persistent internals](PERSISTENT-INTERNALS.md)

This reference follows the current checkout. Some development implementation may
not yet be published on `main`. These helpers process an already ordered internal
stream; they do not establish durability, retain engine snapshots, or pin native
regions on their own.

## Two Different Pipelines

For a user range read, source iterators merge into internal-key order, then
`SnapshotCollapsingIterator` emits one visible live value per user key within a
fixed range. Tombstones mask earlier values rather than appearing in the result.

For compaction, a merged source enters `CompactionDroppingIterator`, which keeps
versions needed by snapshots and can discard obsolete versions or proven-safe
boundary tombstones. Its result remains an internal stream with multiple versions
and tombstones when required. It is not the user-visible collapsing iterator.

All iterators here are stateful and not synchronized. Their caller controls
single-threaded advancement and resource ownership.

## Internal Entry

Source: [InternalEntry.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/iterator/InternalEntry.java).

### `InternalEntry(userKey, sequence, type, value)`

Private construction requires a nonnull key and positive sequence, plus nonnull
value for a live value entry. It copies key and optional value arrays. Empty keys
and empty live values are allowed. Private factory callers supply valid types;
the constructor does not independently reject a null type.

### `value(key, sequence, value)` and `tombstone(key, sequence)`

The live-value factory selects `Type.VALUE`; the tombstone factory selects
`Type.TOMBSTONE` with null payload. Type enum declaration order puts values before
tombstones when key and sequence are identical.

### `userKey()`, `sequence()`, `type()`, and `value()`

The key accessor returns a fresh clone; sequence and type return scalars.
The no-argument value accessor throws for tombstones and otherwise clones the
payload. Copies can remain valid after their original storage source closes.
This entry class does not override object equality with byte-content equality.

### `compareTo(other)` and `sameIdentity(other)`

Ordering is unsigned user key ascending, sequence descending, then type enum
ascending. It compares private arrays directly without accessor clones.
`sameIdentity` compares key content, sequence, and type but deliberately excludes
value bytes. Equal internal identity with differing payloads is still a duplicate
to the merge layer. Same key/sequence with different types is not equal identity.

## Iterator Contract and List Source

Sources: [InternalIterator.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/iterator/InternalIterator.java)
and [ListInternalIterator.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/iterator/ListInternalIterator.java).

### `InternalIterator.next()`, `current()`, and `close()`

The interface exposes forward-only positioning and resource release over strictly
ordered internal entries. It has no seek, snapshot acquisition, or asynchronous
advance operation. Concrete implementations determine validation and close behavior.

### `ListInternalIterator(entries)`

Copies the list, rejecting null elements through `List.copyOf`, then checks every
adjacent pair is strictly increasing under `compareTo`. Duplicate identities and
out-of-order entries fail construction. Same key/sequence with distinct ordered
types can pass. Entry objects are reused because their arrays are encapsulated.

### `next()`, `current()`, `close()`, and `ensureOpen()`

`next` checks open, increments the index, and returns whether positioned; exhaustion
sets index to list size. `current` checks open and requires index within the list,
returning the existing immutable entry. `close` sets a flag and is idempotent; it
does not clear the retained list. Private `ensureOpen` rejects later access.

## K-Way Merge

Source: [MergingInternalIterator.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/iterator/MergingInternalIterator.java).

### `MergingInternalIterator(sources)` and `Head`

Construction copies the source list into an `ArrayList` but does not eagerly
advance, validate null entries, reject repeated iterator objects, or verify source
ordering. Its priority queue orders nested `Head(source, entry)` records by entry.
Callers must provide independent, strictly sorted sources.

### `next()` and `advance(source)`

On first advance, calls `next` once on each source and queues nonempty heads.
On subsequent advances, removes the previously selected head and advances that
source through the private `advance` helper. An empty heap clears current and
returns false. Otherwise the minimum head becomes the next candidate.

Before returning it, scans all queued heads for matching internal identity and
throws `IllegalStateException` on a duplicate. Payload equality is irrelevant.
This rejects duplicate heads across sources, but it does not independently validate
strict ordering inside a custom source. Duplicate checking adds a linear scan of
active heads to each heap operation; this is not only logarithmic merge work.

Initialization and source advancement can throw after state has changed. There
is no rollback or automatic closing of all sources on such failure; callers should
close in their own failure path and should not treat retry as a reset.

### `current()`, `close()`, and `ensureOpen()`

`current` requires open and a selected entry; it returns that entry without a clone.
`close` sets closed first, closes sources in list order, then clears the heap.
If one source close throws, later closes and heap clearing are skipped, and another
call does not retry because closed is already set. `ensureOpen` rejects use after
close. Source ownership is transferred operationally to the merge's close path;
the constructor itself provides no reader leases.

## Snapshot Collapse

Source: [SnapshotCollapsingIterator.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/iterator/SnapshotCollapsingIterator.java).

### `SnapshotCollapsingIterator(source, visibleSequence, startInclusive, endExclusive)`

Requires nonnull source and bounds, nonnegative visibility, and nonreversed
unsigned bounds. Clones the bounds. Equal bounds represent an empty range.
It assumes the source is internally ordered; it does not register a snapshot or
validate each source ordering transition.

### `next()` and `take()`

Clears the current user result, consumes every version of one key, and chooses
the first record with sequence at or below visibility. Newer versions are ignored;
a chosen tombstone masks the key rather than falling back to an older value.
Keys before start are skipped, keys at or beyond end stop the call, and live
visible keys return a copied value.

`take` consumes a saved lookahead entry first, otherwise advances the source.
The group loop reads one entry from the next key and buffers it. There is no seek
to start: earlier groups are consumed and compared. Key accessors clone during
group comparison, and the chosen value accessor clones its payload.

There is no latched end-of-range flag. Calling `next` again after a false result
at the upper bound may consume more out-of-range groups, although it cannot return
them as results under a sorted source. Stop advancement on the first false.

### `key()`, `value()`, `ensurePositioned()`, and `ensureOpen()`

The accessors require an open positioned iterator and clone the stored current
arrays again. Thus a returned live value is copied when selected and again per
public accessor call. `ensurePositioned` checks key is nonnull; `ensureOpen`
checks the closed flag.

### `close()`

Sets closed before closing its source and normally does so once. Source failure
is not retried. It does not explicitly clear buffered entries or current arrays;
closed access is still rejected. Close does not itself release a snapshot unless
the provided source's own close path carries that ownership.

## Snapshot-Aware Compaction Dropping

Source: [CompactionDroppingIterator.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/CompactionDroppingIterator.java).

### `CompactionDroppingIterator(source, oldestSnapshotSequence, baseLevelForKey)`

Requires nonnull source and predicate and nonnegative oldest snapshot. The caller
supplies a safe boundary and lower-level absence proof; this constructor does not
consult a snapshot registry or validate the predicate against a live version.

### `next()` and `take()`

When the retained queue is empty, consumes an entire user-key group into a list,
buffering the first entry of the next key through `take`. For the sorted group:

- Every version newer than the oldest snapshot is retained.
- The first version at or below that boundary is retained, except a tombstone
  whose `baseLevelForKey` predicate returns true.
- All remaining older records are dropped.

It then emits retained entries in original order. Entirely dropped groups are
skipped. The list and queue retain references to existing entries rather than
copying value payloads, but grouping allocates containers and clones key bytes
through accessors. Memory for one heavily versioned key scales with its full group,
not a fixed one-entry streaming buffer.

The predicate is called only for the boundary tombstone, not every tombstone.
An exception while building or classifying a group has no rollback; retry is not
a supported reconstruction mechanism. Source ordering and duplicate validation
belong to the source/merge contract.

### `current()`, `droppedVersions()`, and `droppedTombstones()`

`current` requires open and positioning and returns the retained entry itself.
Counters have no open check and remain readable after close. `droppedTombstones`
counts only safely removed boundary tombstones. `droppedVersions` counts every
record older than the handled boundary, including older tombstones despite the
source comment's description of discarded values. Counters use ordinary increments,
not saturation or atomic operations.

### `close()` and `ensureOpen()`

Close marks closed then closes the source once, without clearing buffered entries
or the retained queue. Source close failure is not retried. `ensureOpen` rejects
later traversal and current access.

## Base-Level Proof

Source: [BaseLevelKeyChecker.java](../../modules/aether-lsm/src/main/java/io/aetherdb/lsm/compaction/BaseLevelKeyChecker.java).

### `BaseLevelKeyChecker()` and `isBaseLevelForKey(userKey, outputLevel, version)`

Construction is stateless. The query requires nonnull key and output level 1
through 6. It checks file ranges in every level below output; any containing range
returns false, otherwise true. Output L6 returns true without inspecting lower
levels because none exist. There is no explicit null-version guard.

This is a conservative metadata proof. A range containing the key prevents
tombstone removal even if the file has no actual matching record. It does not
inspect output or higher levels, read Bloom filters, perform table lookup, or
validate current-version ownership. The coordinator must supply an inventory
appropriate to the compaction's installation rules.

## Review Boundaries

Keep internal ordering distinct from user-key equality. Trace snapshot and table
leases around these iterators, and close them on failure. Test retention at the
exact boundary, tombstone masking, duplicate identity, empty values, and lower-level
range overlap. Function-name coverage detects omissions, not snapshot safety,
correct live-version installation, or complete cleanup after exceptions.
