# Native Record and Access Functions

[Native regions and allocation](NATIVE-REGION-FUNCTIONS.md) | [Native memtable](NATIVE-MEMTABLE-FUNCTIONS.md)

This describes the current checkout, including development changes not necessarily
published on `main`. Native records are process-local bytes, separate from SSTable
and WAL formats.

## Architecture and Ownership

The allocator reserves space; `NativeRecordWriter` initializes header and payload;
the memtable publishes its initialized node through release links. Readers acquire
those links before inspecting records. `NativeRecordReader.openChecked` validates
structure and returns a borrowed `NativeRecordView`. Key comparisons read bytes
in place, while copy methods produce independent heap arrays.

Neither writer nor view retains a region lease. A checked view caches metadata,
not an immutable snapshot. Owners must prevent close and concurrent mutation
during use. Freezing a region blocks allocation, not direct segment writes.

| Operation | Copy or allocation | Validation |
| --- | --- | --- |
| `writeValue` | Reserves native space and copies input bytes | Input sizes and sequence |
| `writeValueAt` | Copies into caller-owned space, no reservation | Capacity; caller owns alignment and layout |
| `openChecked` | Creates a view and slices, not payload arrays | Header, bounds, semantics, padding |
| `compareKey` | Reads key bytes in place | Reader overload checks structure first |
| `copyKey`, `copyValue` | New array on every call | Region must remain alive |

## Record Layout

Source: [NativeRecordFormatV1.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRecordFormatV1.java).

Multibyte fields are little-endian. The 24-byte header precedes the key, value,
and zero padding up to an 8-byte boundary.

| Offset | Field | Width |
| --- | --- | --- |
| 0 | Total aligned length | 4 bytes |
| 4 | Key length | 4 bytes |
| 8 | Value length | 4 bytes |
| 12 | Version 1 | 2 bytes |
| 14 | Value type 1 or tombstone type 2 | 1 byte |
| 15 | Zero flags | 1 byte |
| 16 | Positive sequence | 8 bytes |

Maximum key length is 65,536 bytes; maximum value length is 16,777,216 bytes.
Empty keys and empty live values are allowed here. Tombstones have no value bytes.
There is no checksum: changing payload bytes while preserving structure can pass
checked decoding. This is not an authenticated or durable record format.

### `NativeRecordFormatV1()`, `validateLengths`, `totalLength`, and `padding`

The constructor is private. `validateLengths(keyLength, valueLength)` rejects
negative and over-limit lengths. `totalLength` validates, adds header and lengths
with checked arithmetic, aligns to 8 using the allocator helper, and converts to
an integer exactly. `MAX_RECORD_BYTES` is derived from the maximum sizes.
`padding` subtracts header and logical payload sizes from total length, yielding
zero through seven bytes for valid inputs. None of these functions allocates
native space or validates record contents, type, or sequence.

## Native Access Helpers

Source: [NativeAccess.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeAccess.java).

### `NativeAccess()` and `checkedSlice(region, offset, length)`

Private utility constructor. `checkedSlice` rejects negative ranges, computes
end with checked addition, rejects end beyond capacity, and returns a slice of
the live root segment. Overflow propagates as `ArithmeticException`. It does not
consult the allocator cursor or prove that bytes were allocated. Valid empty
slices are allowed; returned slices share storage and arena lifetime.

### `getInt`, `setInt`, `getShort`, `setShort`, `getLong`, and `setLong`

Plain scalar reads and writes use unaligned little-endian FFM layouts. FFM checks
segment bounds and lifetime. These helpers do not add acquire/release ordering,
validate record semantics, or retain region ownership.

### `getByte` and `setByte`

Read or write one byte with `ValueLayout.JAVA_BYTE`. Byte order is irrelevant;
segment bounds and lifetime still apply.

### `getIntAcquire` and `setIntRelease`

Slice four bytes at the given offset and use an aligned little-endian integer
VarHandle with acquire or release ordering. Effective integer alignment is
required, unlike plain integer access. These supply skip-list link publication
ordering, not lifetime retention or node-offset validation.

### `copyFromArray(source, target, targetOffset)`

Wraps the source array in a segment and copies its entire length into the target.
There is no intermediate clone or custom null guard. Concurrent source mutation
does not produce a guaranteed stable record image. FFM checks destination bounds
and lifetime.

### `copyToArray(source, sourceOffset, length)`

Allocates a byte array of exactly the requested length, then copies the source
range. Negative length can fail during array allocation before segment access;
invalid source ranges fail at copy. Repeated calls return independent arrays,
including for zero-length payloads.

### `compareUnsigned(segment, offset, length, candidate)`

Lexically compares unsigned bytes up to the shorter length, returning at the first
difference; equal prefixes compare by total length. No key array is created.
The helper does not reject negative lengths or null candidates itself. FFM checks
the bytes actually accessed, not an upfront slice of the entire declared range.
The view caller supplies validated length and rejects null candidates.

## Record Writer

Source: [NativeRecordWriter.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRecordWriter.java).

### `NativeRecordWriter(region)`

Stores the borrowed region without null validation, reservation, or a lease.

### `writeValue(key, value, sequence)` and `writeTombstone(key, sequence)`

`writeValue` rejects a null value and delegates with type 1. `writeTombstone`
creates an empty value array and delegates with type 2. They return the allocator's
descriptor rather than a record view.

### `write(key, value, sequence, type)`

The private allocating path rejects null key and nonpositive sequence, derives
aligned size, and requests space with alignment 8. If full, it returns the failed
allocation unchanged and writes nothing. Otherwise it calls `writeAt` and returns
the successful descriptor. A subsequent write exception does not rewind the
monotonic allocator. Private callers supply valid value and type arguments.

### `writeValueAt(offset, key, value, sequence)` and `writeTombstoneAt(offset, key, sequence)`

Compute size and call `writeAt` without reserving space. The caller supplies its
own record location, usually inside a node allocation. Both evaluate `key.length`
before the private key guard, so null key raises `NullPointerException` here;
`writeValueAt` explicitly rejects null value first.

These methods do not verify alignment, allocator ownership, nonoverlap, or state
`OPEN`. They can write to a frozen but live region. Caller-controlled layout and
writer locking must establish those boundaries.

### `writeAt(offset, total, key, value, sequence, type)`

Rejects null key or nonpositive sequence, obtains a checked slice, zero-fills the
whole record, copies key and live value, then writes lengths, version, type, zero
flags, sequence, and finally total length. Padding stays zero; prior destination
contents are overwritten.

These are ordinary stores. Writing total length last is not a concurrent publish
protocol. The surrounding memtable release-publishes completed nodes. Failure
can leave partially initialized bytes with no rollback or checksum.

## Checked Reader

Source: [NativeRecordReader.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRecordReader.java).

### `NativeRecordReader(region)` and `openChecked(offset)`

Construction stores a borrowed region without null validation or lease acquisition.
`openChecked` requires offset at least 64, aligned to 8, with capacity for a header.
It validates aligned total length within limits, independent payload-length limits,
version 1, zero flags, known type, positive sequence, and zero-length tombstone value.
Derived aligned size must exactly equal stored size and fit region capacity.
Every trailing padding byte must be zero.

Returns a view with cached checked metadata, without key or value array copies.
It does not check checksums, ordering, allocator ownership, or memtable reachability.
A valid-looking record anywhere in allowed capacity can pass. Region/segment
lifetime failures are not converted to corruption exceptions. Concurrent byte
mutation lies outside the decoder's consistency guarantee.

### `compareKey(offset, candidate)` and `corrupt(message)`

The public comparison validates a record on every call before delegating to its
view. It avoids payload copies but still creates a view and checks header/padding.
The private `corrupt` helper constructs a `NativeRecordCorruptionException`.

## Borrowed View

Source: [NativeRecordView.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRecordView.java).

### `NativeRecordView(region, offset, totalLength, keyLength, valueLength, sequence, type)`

Package-private construction stores fields without independent validation. The
reader establishes validity; retaining this object does not keep its arena open.

### Metadata accessors and `alive()`

`offset()`, `totalLength()`, `keyLength()`, `valueLength()`, `sequence()`, and
`recordType()` call `alive()` then return cached metadata. `isTombstone()` checks
liveness and compares cached type to 2. `alive` gets the root segment, rejecting
a closed region. It does not revalidate the header or lock against concurrent close.

### `copyKey()` and `copyValue()`

Copy key bytes after the header or value bytes after the key into new heap arrays.
`copyValue` rejects tombstones; empty live values return empty arrays. Copies remain
valid after region close, but repeated calls copy again. Ranges use cached sizes
even if someone overwrote the header after the view opened.

### `compareKey(candidate)`

Rejects null candidate with `IllegalArgumentException`, checks liveness, and reads
native key bytes through unsigned comparison. It neither copies the key nor
rereads the header. This compares user keys only, not the memtable's complete
key/descending-sequence/type ordering.

## Errors and Review Boundaries

Source: [NativeRecordCorruptionException.java](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRecordCorruptionException.java).

`NativeRecordCorruptionException(message)` preserves a structural failure message.
It is distinct from invalid writer arguments, allocator-full results, FFM bounds
errors, and use-after-close failures.

Keep writer and reader layout constants together; preserve the distinction between
empty live values and tombstones. Review alignment at the allocation owner and
leases at the view owner. Copy-free comparison is not allocation-free, and checked
structure is not checksum verification. Function-name inventory tests detect
omitted names, not contract accuracy or concurrency correctness.
