# Native Memtable Function Reference

[Heap MVCC](MVCC-REFERENCE-FUNCTIONS.md) | [Persistent internals](PERSISTENT-INTERNALS.md) | [Native regions and allocation](NATIVE-REGION-FUNCTIONS.md)

## Architecture And Ownership

`NativeSkipListMemTable` stores process-local skiplist nodes and native records
inside one budgeted native region. Nodes use integer offsets, not durable file
pointers. Key ordering is unsigned user key, descending sequence, then ascending
record type. The engine owns sequence assignment and visibility; table insertion
alone does not make a mutation committed or durable.

Writers serialize through `writerLock`. Readers traverse acquire-loaded links
without taking that lock. A writer initializes a node and record, then publishes
links with release stores. Reference counting protects region lifetime when
coordinated correctly by the owning engine; methods do not automatically retain
a lease for each read.

| State | Mutation behavior | Read/lifetime behavior |
|---|---|---|
| ACTIVE | Inserts may succeed, be duplicate or run out of space. | Point/range reads allowed; flush iteration forbidden. |
| FROZEN | Insert returns FROZEN. | Reads and full internal iteration allowed. |
| RETIRED | Insert returns FROZEN; new retain requests rejected. | Existing owners/leases may still read until native closure. |
| CLOSED | Insert's state gate returns FROZEN after input validation. | Read guard rejects access; native region is no longer usable. |

Results materialize owning heap arrays. This table does not expose tensor values
as lifetime-independent zero-copy native views through its public lookup API.

## Construction And Mutation Functions

Source: [NativeSkipListMemTable.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/skiplist/NativeSkipListMemTable.java).

### `NativeSkipListMemTable(budget, capacityBytes, id, seed)`

Creates a budgeted region, native record reader/writer and deterministic height
generator. It allocates the 96-byte head at required offset 64, rejecting violation
of that invariant, and initializes head allocation length, zero record offset,
height 20, version one and head flag. The table starts ACTIVE with one owner
reference and maximum linked height one. Constructor failure has no explicit
region-close cleanup block here; allocation/factory behavior is a separate layer.

### `put(key, value, sequence)` And `delete(key, sequence)`

`put` explicitly rejects null values, then delegates to `insert` with VALUE type.
Empty values remain VALUE records. `delete` delegates with an empty array and
TOMBSTONE type. Both return `InsertResult`, not a durable write receipt, and do
not reserve or advance the database-visible sequence.

### `insert(key, value, sequence, type)`

Private insertion requires non-null key, positive sequence and native-format
payload lengths before locking. Under the writer lock, non-ACTIVE state returns
FROZEN. It searches predecessors for the exact internal key; an equal key,
sequence and type returns DUPLICATE without comparing payload contents.

It chooses a height, computes node/record allocation and tries an eight-byte-aligned
allocation. Failure returns FULL. The height generator has already advanced at
that point; a failed allocation consumes a height draw but no linked entry.

For success it zeroes the allocated node, writes its header, writes the native
record and fills outgoing links. It release-publishes level zero first, then
upper levels, updates volatile maximum height and increments entry count. The
writer lock is released in `finally`. There is no per-node deletion/free or
rollback of an already consumed allocation on an unexpected later exception.

`InsertResult` distinguishes INSERTED, FULL, DUPLICATE and FROZEN. A duplicate
VALUE and a TOMBSTONE at the same key/sequence are different internal keys because
type participates in ordering; global mutation identity is an engine concern.

## Lookup And Materialization

### `get(key, visibleSequence)`

Checks readable state, non-null key and nonnegative visibility. Searches the lower
bound for that user key and sequence with synthetic type zero, placing the target
before valid types at the same sequence. No node, a different key or a too-new
record returns NOT_FOUND. A selected tombstone returns TOMBSTONE; otherwise the
record value is copied into an owning `MemTableLookupResult` which clones it again.

It selects one record, not all history, and does not skip a selected tombstone to
expose an older value. It does not independently register a snapshot or retain the
region against concurrent retirement.

### `scan(startInclusive, endExclusive, visibleSequence)`

Requires readable state, non-null bounds and start no greater than end. Starts
at the first version of the start key using a maximum-sequence lower bound,
then walks level zero by user-key groups. For each group it retains the first
record at or below visibility, consumes the rest of that group's versions and
emits only a selected non-tombstone. The end bound is exclusive; equal bounds
return no rows. Returned entries own copied bytes in a mutable result list.

Unlike `get`, this method does not explicitly reject negative visibility; no
positive record qualifies. Traversing an ACTIVE table is not a complete snapshot
isolation protocol by itself; the engine supplies a valid visibility boundary
and safe table lifetime.

### `internalEntries()`

Requires readable state and rejects ACTIVE. Walks every level-zero internal record,
including overwritten values and tombstones, preserving internal ordering. Copies
key/value bytes into `InternalEntry` objects and returns `List.copyOf`. Tombstones
carry empty values. This materializing flush view is not an iterator over borrowed
native memory; it can allocate in proportion to all retained payload bytes.

### `userKeys()`

May run without freezing, but still requires readable state. Copies each record's
key while traversing and emits only changes from the previous user key. Returned
list structure is immutable; contained arrays are owning copies and remain mutable
to the caller. Tombstone-only keys are included because this enumerates distinct
stored keys, not only visible live values.

## Search And Link Helpers

### `lowerBound(...)` And `findPredecessors(...)`

`lowerBound` starts at the head and descends from current maximum linked height,
advancing while nodes precede the synthetic target. Returns the next base-level
offset or zero. `findPredecessors` searches from all twenty possible levels and
fills the supplied predecessor array for insertion, also returning its base-level
candidate. Neither allocates sequences or changes links.

### `compareNode(...)`, `record(node)` And `compareBytes(...)`

`compareNode` opens a checked native record, compares user bytes, then compares
the target sequence against the node sequence to implement descending order,
then compares unsigned types. `record` reads the node's stored record offset and
delegates to `NativeRecordReader.openChecked`. It does not independently validate
every skiplist node header field. `compareBytes` delegates to unsigned array
comparison for copied user-key arrays.

### `next(node, level)` And `setNextRelease(node, level, target)`

`next` acquire-loads the integer link at the format-derived offset.
`setNextRelease` release-stores it. A zero link is the end sentinel. Their ordering
supports seeing a fully initialized node after publication; it does not ensure
that the region cannot be closed concurrently by an incorrectly coordinated owner.

## State And Lifetime Functions

### `freeze()`

Takes the writer lock. Only ACTIVE transitions to FROZEN and freezes the native
region; other states are left alone. The lock excludes an insertion while making
that transition. Freeze does not flush, force a file or advance a manifest watermark.

### `retain()` And `Lease(...)`

Rejects RETIRED/CLOSED, then CAS-increments the reference counter and constructs a
private owner-bound lease. The state check and counter increment are not one atomic
state/refcount transition: the owning engine must coordinate retention against
retirement. This implementation should not be advertised as closing that race
for arbitrary unsynchronized callers. Counter increment also has no explicit
overflow guard.

### `retire()`, `release()` And `close()`

`retire` freezes, sets RETIRED and releases the initial owner reference. Private
`release` decrements, rejects negative count and closes the region when zero is
reached in RETIRED state, then marks CLOSED. Direct repeated `retire` is not
idempotent: it repeats the owner release. Public `close` skips retire if already
RETIRED/CLOSED, providing a state-guarded owner shutdown rather than forcibly
invalidating outstanding leases.

If region closure throws, the subsequent CLOSED assignment does not occur. These
functions do not repair counters or retry every failed native cleanup operation.

### `Lease.table()` And `Lease.close()`

`table` requires an open lease and returns its owner; it does not add another
reference. `close` clears the owner reference before invoking release, so a normal
second close does nothing. Leases themselves are not synchronized for concurrent
use/close. The last outstanding release may trigger physical native closure after
retirement.

### Accessors And `ensureReadable()`

`state()` returns the volatile lifecycle state. `entryCount()` returns a plain
long updated under the writer lock; the accessor itself is not synchronized.
`nativeUsedBytes()` and `nativeRemainingBytes()` delegate to allocator accounting.
`headOffset()` returns the fixed offset. These are observations, not reservations.
`ensureReadable` rejects CLOSED only, not RETIRED with outstanding references.

### `maximumInsertionBytes(keyBytes, valueBytes)`

Computes native record length, node allocation at maximum height and seven extra
bytes for worst-case eight-byte alignment, using checked arithmetic. This is a
conservative admission estimate, not the actual random-height allocation size
and not a guarantee of table capacity after other writes.

## Heap Result Records

`Entry(key, value)` clones both arrays on construction and access. It has no
explicit null/length validation beyond those clone operations. `InternalEntry`
also clones arrays, requires positive sequence and forbids nonempty tombstone
payloads; generated sequence/tombstone accessors return scalars. Generated record
equality for these array fields is identity-based, not content-based.

Source: [MemTableLookupResult.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/skiplist/MemTableLookupResult.java).

`MemTableLookupResult(kind, value)` clones a non-null payload but does not enforce
kind/payload consistency or non-null kind. Custom `value()` returns another copy
or null. Factories `value(bytes)`, `tombstone()` and `notFound()` select VALUE,
TOMBSTONE and NOT_FOUND; the latter two carry null. Direct construction can bypass
those intended combinations. Generated `kind()` reports the tag, which callers
must use to distinguish absence, deletion and a legitimate empty value.

## Node Layout Functions

Source: [NativeSkipListNodeFormat.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/skiplist/NativeSkipListNodeFormat.java).

Private `NativeSkipListNodeFormat()` prevents instantiation. The 16-byte prefix
contains allocation length, record offset, height, version, flags and reserved
fields, followed by four-byte link offsets and padding. Head offset is 64; head
allocation is 96 bytes, enough for twenty links.

- `prefixBytes(height)` requires height one through twenty and rounds header plus links up to eight-byte alignment.
- `allocationBytes(height, recordBytes)` adds that aligned prefix and record bytes with checked addition. It does not independently validate a negative record length.
- `linkOffset(level)` requires level zero through nineteen and returns `16 + 4 * level`. It validates against global maximum, not an individual node's height.

This layout is process-local state, not an SSTable or WAL format to write directly
to durable storage.

## Height Generator Functions

Source: [SkipListHeightGenerator.java](../../modules/aether-memtable/src/main/java/io/aetherdb/memtable/skiplist/SkipListHeightGenerator.java).

Construction records the seed. `nextHeight()` starts at one, obtains private
`nextLong()` output and promotes for each successive two-bit zero pair up to
twenty, giving one-quarter promotion probability. `nextLong` advances SplitMix64
state and applies its fixed mixing constants with long wraparound. It is
deterministic for a seed/call sequence, not cryptographic or thread-safe by itself;
the table calls it under the writer lock.

## Integration Boundary

The [persistent reference](PERSISTENT-INTERNALS.md) explains when tables freeze,
flush and retire. Function-name coverage catches omitted declared helpers; it
does not prove every retain/retire interleaving, allocation failure or concurrency
property. Native memory codecs and allocators are separate contracts below this
table and must be read alongside it for bounds and budget behavior.
