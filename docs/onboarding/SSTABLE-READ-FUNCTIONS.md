# SSTable Reads And Verification

[Block functions](SSTABLE-BLOCK-FUNCTIONS.md) | [Persistent internals](PERSISTENT-INTERNALS.md)

[SSTable construction](SSTABLE-BUILD-FUNCTIONS.md) follows the writer, partitioning
and trace stages that precede these reads.

This page describes functions in the local development checkout. In particular,
the streaming verifier may not yet be present in published `main` source.

## Two Different Lifetimes

`SSTableReader` owns an open channel, a validated Bloom filter, index-derived
block descriptors and lazily initialized decoded-entry caches. Its public open
methods immediately run complete verification, which populates all those caches.
Consequently, lazy loading is an implementation mechanism, not a promise that
ordinary open avoids reading DATA blocks.

`SSTableVerifier` owns a temporary channel for one sequential validation pass.
It materializes metadata blocks but uses `RestartBlockScanner` for DATA entries.
It returns accounting, closes the channel and retains no general-purpose reader
or decoded value cache. It does not publish a manifest or make a table live.

| Operation | DATA values | Persistent state after return |
|---|---|---|
| Reader `open` | Materialized and copied during complete verification. | Open channel and decoded block caches. |
| Reader `lookup` after open | Reuses an owned cached result; its public value accessor still copies. | Same cache/result identity. |
| Reader `entries` | Copies values into new owning entries. | Returned immutable list; reader cache remains. |
| Reader `verify` again | Reuses cache but copies values to obtain lengths. | Same reader caches; no disk re-read required for cached blocks. |
| Streaming `verify` | Skips value materialization; checksum still covers the bytes. | Scalar verification result only. |

## Reader Construction And Open Paths

Source: [SSTableReader.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableReader.java).

### Private `SSTableReader(...)`

Stores the path, expected metadata, channel, physical size, block descriptors and
validated filter. The footer parameter is passed by `finishOpen` but is not stored
or used by the constructor. Construction alone performs no I/O or verification.

### `open(path, expected)`

Requires a path and `TableFileMetadata`, opens a read-only channel and checks
exact file size and minimum header/footer capacity. It reads and decodes the
4 KiB header region and 128-byte footer, then delegates to `finishOpen`.
On any failure it closes the channel; an I/O failure from that cleanup is attached
as suppressed rather than replacing the original failure.

### `open(path, databaseId, manifest)`

Requires all three arguments and checks file size against manifest metadata.
After decoding header/footer, it reads properties to obtain raw key/value byte
counts absent from `ManifestFileMetadata`. It builds expected metadata from the
manifest identity, counts, key bounds and sequence bounds, plus header block count
and those properties. `finishOpen` then cross-checks observed content. The
explicit key-bound comparison here compares the manifest with copies sourced
from that same manifest; it is not the content proof. That comes later.

### `open(path, databaseId)`

Opens an unreferenced candidate using its own header and properties as the source
of expected file number, counts and bounds. The supplied database identity still
must match the file. This proves internal consistency, not that a manifest has
authorized or published the candidate. Failure cleanup matches the other paths.

### `finishOpen(...)`

Checks identity and footer handle bounds/non-overlap, loads and validates the
Bloom filter, properties, metaindex and index. A previously decoded properties
payload is reused instead of re-reading it. It builds block descriptors, creates
the reader and invokes `reader.verify()` before returning. It relies on the outer
open method to close the channel if this sequence fails.

## Point Lookup And Iteration

### `lookup(userKey, visibleSequence)`

Checks the reader is open, requires a non-null key and nonnegative visibility
boundary. A Bloom negative returns `Absent` and increments filter-negative
diagnostics. Otherwise an index search selects the first block whose upper-bound
user key is at least the requested key. No candidate block also returns `Absent`.

Within that block, a lower-bound search finds the user-key run. The method walks
versions in newest-first internal order and returns the first whose sequence is
not greater than the visibility boundary. It returns the cached `SSTableLookup`
object itself. No visible version returns `Absent`; an I/O loading failure becomes
`SSTableCorruptionException` with its cause. This method searches one selected
block, not a cross-block version run. The current builder can split versions of
one user key across blocks, so generic multi-version inputs require care; the
reader does not compensate by scanning subsequent blocks. Index upper-bound
validity is also required for correct selection.

### `findCandidateBlock`, `binarySearch`, `sameUserKey` And `sequence`

- `findCandidateBlock(userKey)` performs a lower-bound binary search over index upper-bound user keys, returning -1 after the last block.
- `binarySearch(entries, userKey)` finds the first decoded entry whose user key is at least the request; its result may equal the list size.
- `sameUserKey(encoded, userKey)` compares the user portion directly, with no decoded-key allocation.
- Private `sequence(encoded)` delegates to the whole-array sequence accessor for an already validated internal key.

### `entries()`

Loads each block and returns all entries in internal order as `List.copyOf`.
Every element receives a newly decoded `InternalKey` and a copied value, which
`SSTableEntry` clones again on construction. This is full materialization,
not a streaming iterator or borrowed block view. Closing the reader does not
invalidate the separately owned returned entries.

### Reader `verify()`

Traverses cached-or-loaded entries, decodes each internal key, checks strict
global ordering, counts entries and raw key/value bytes, tracks minimum/maximum
sequences and rejects Bloom false negatives. It compares these observations and
first/last keys with expected metadata, then records a verification trace.

`entry.value().length` obtains a defensive value copy even though only the length
is needed. A repeat call can therefore allocate heavily despite no DATA disk
read. Cached bytes, not a new read of every physical block, are revalidated.
The code assumes the first DATA block has an entry when checking its first key;
it does not give that assumption a dedicated empty-block error here.

### `metadata()`, `close()`, `ensureOpen()` And `corrupt(...)`

`metadata` checks open state and returns the immutable metadata object.
`close` marks the reader closed before closing its channel. `ensureOpen` rejects
subsequent read/metadata/verification operations with an `IllegalStateException`
that includes the path. `corrupt` constructs structural exceptions. Closing
does not explicitly clear decoded cache fields; their memory is reclaimed when
the reader and references to its cached results become unreachable.

## Index, Metadata And Physical Read Helpers

### `decodeIndexAndData(...)`

Materializes index entries under internal-key ordering and requires a nonempty
index whose count matches the header. Each index value decodes to a handle,
which must fit the body, not overlap its predecessor and end no later than the
filter block's start. Each index key becomes a block's upper bound. The returned
descriptor list is immutable; DATA content loads later, during verification.

The channel parameter is unused in this helper. It does not compare each index
upper bound with the actual last key of its DATA block. Global DATA order and
table bounds are separate checks, not a proof of that correspondence.

### `validateIdentity(...)`

Matches header/footer file numbers and database IDs against expected metadata,
both recorded sizes against actual size, and header entry/block counts and
sequence bounds against expectations. The path supplies error context; it does
not enforce a canonical filename by parsing it.

### `validateMetaindex(...)` And `validateProperties(...)`

The metaindex is decoded into named entries and must contain handles matching
the footer for `aether.filter.bloom.v1` and `aether.properties.v1`. Properties
must match comparator, file number/size, entry/block counts, raw byte counts,
sequence/key bounds, Bloom policy, compression policy and creation timestamp.
Required property checks do not reject every possible additional property.

### `bytewiseMap(...)` And Property Validators

`bytewiseMap` turns materialized metadata keys into US-ASCII strings and obtains
defensive value copies. Duplicate decoded names are corruption. It does not
independently validate that every input byte is ASCII.

- `requireHandle(values, key, expected)` requires and decodes a handle, then compares it with the expected record.
- `requireAscii(values, key, expected)` encodes the expected ASCII literal and delegates to byte comparison.
- `requireLong` and `requireInt` require exact eight/four-byte little-endian representations and matching scalar values.
- `requireBytes` compares array content against expected bytes.
- `propertyLong` requires an eight-byte property and returns its scalar; semantic bounds are checked by consumers.
- `propertyBytes` requires presence and returns a clone, not the map-owned array.

### `raw(...)` And `readRange(...)`

`raw` validates handle bounds and requires its offset to fit a signed `int`
even though the lower-level reader accepts a `long` offset. It times and counts
the physical block read, then calls `BlockEnvelope.decode` for metadata/checksum
validation and payload copying. An offset above that addressable limit is
corruption, not support for an arbitrarily large SSTable.

`readRange(channel, offset, length)` allocates exactly `length` bytes and loops
positional channel reads until filled. End of file throws `EOFException`.
The channel's shared position is not advanced. The method has no timeout or
explicit zero-progress read limit. Diagnostics count requested block bytes,
not physical-device I/O or operating-system cache misses.

## Decoded Block Cache

### `DataBlock(...)` And `loadEntries(...)`

Construction stores a handle, owned index-key array and initial entries list.
`loadEntries` records a cache hit/miss from the initial observed volatile field.
Double-checked initialization under the block monitor prevents simultaneous
threads from publishing two converted lists. That diagnostic observation can
report a miss even when another thread completes initialization before the lock.

On first initialization it reads/checksums the DATA block, materializes restart
entries, decodes and validates each internal key, rejects a tombstone with a
nonempty value, and creates `DataBlockEntry` objects. `List.copyOf` is published
through the volatile field. Decode timing ends in `finally`, including failures.
Cached loads do not perform another envelope checksum read.

### `loadEntriesUnchecked(...)` And `upperBoundKey()`

`loadEntriesUnchecked` wraps an `IOException` from loading in
`IllegalStateException`; it does not translate every failure to corruption.
`upperBoundKey` exposes the internally owned key array only to private reader
logic, without another clone.

### `DataBlockEntry(...)`, `encodedKey()`, `lookup()` And `value()`

The constructor stores its already-owned encoded key and creates one immutable
lookup result using the encoded sequence. `encodedKey` and `lookup` return those
private cached fields directly. `value` delegates to a found result's copying
accessor or returns a new empty array for a tombstone.

Current classification uses **value length**, not internal-key type: an empty
value becomes `Tombstone`, even if its key type is 1. The codec permits a type-1
empty value, so this reader-cache path should not be advertised as preserving
that distinction. This reference describes existing behavior; it does not alter
read semantics as part of documentation work.

## Authoritative Streaming Verification

Source: [SSTableVerifier.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableVerifier.java).

The private `SSTableVerifier()` constructor prevents utility instantiation.
`verify(path, databaseId, manifest)` opens a temporary read-only channel in
try-with-resources. Its stages are:

1. Require exact manifest file size and enough room for header/footer; decode both and validate footer handles.
2. Read properties, construct expected metadata, check identity/properties/metaindex, and decode a reusable Bloom view.
3. Materialize index entries using the validating internal-key range comparator; require a nonempty index with the expected block count.
4. Visit DATA handles in order, rejecting overlap and handles extending beyond the DATA region. Read/checksum each block once through `raw` and scan entries without materializing values.
5. Validate each internal-key range, require zero value length for type-2 tombstones, enforce global order, match the first key and check Bloom membership using the borrowed user-key range.
6. Accumulate counts/byte lengths/sequence extrema, retain only previous-key scratch, then compare totals and final key with metadata. Return observations and record the streamed trace.

The first empty DATA block is explicitly rejected. The implementation does not
reject every later empty block independently; the aggregate checks still apply.
As with the reader, it does not compare each index upper bound with that block's
last scanned key. Handle gaps are not rejected merely for being gaps.

The scanner's borrowed key is copied into previous-key scratch for cross-entry
ordering; tensor-sized values are not cloned. Physical block arrays and the
existing envelope payload copy remain. This is a low-allocation mandatory pass,
not a zero-allocation or zero-I/O pass.

`IllegalArgumentException` and `IndexOutOfBoundsException` are normalized to
`SSTableCorruptionException` with the original cause. Existing corruption and
I/O failures propagate; the channel closes on success and failure. Private
`corrupt(message)` creates direct structural failures.

### `VerificationResult`

The returned record contains observed entry count, raw internal-key/value byte
counts, sequence extrema, requested read bytes, index-derived DATA-block count
and elapsed nanoseconds. Generated accessors return scalars. `bytesRead` includes
header/footer and physical metadata/DATA handles, not OS-level read amplification.
Elapsed time includes this verification path; it is not total population time.

## Result And Metadata Objects

Sources: [SSTableLookup](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableLookup.java),
[SSTableEntry](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableEntry.java)
and [TableFileMetadata](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/TableFileMetadata.java).

- `SSTableLookup.sequence()` distinguishes a selected positive sequence from zero for absence. It is an interface contract, not a visibility search.
- `Found(sequence, result)` requires a positive sequence and a non-null found `LookupResult`; it retains that immutable result. The byte-array constructor delegates to the copying `LookupResult.found` factory. `value()` returns a defensive copy; generated `result()` exposes the immutable result object, not its private array.
- `Tombstone(sequence)` requires a positive selected deletion sequence. `Absent()` has no fields and its explicit `sequence()` returns zero.
- `SSTableEntry(key, value)` requires both fields and forbids nonempty tombstone payloads, then clones the value. Its `value()` clones again; generated `key()` returns the immutable `InternalKey` object.
- `TableFileMetadata(...)` validates non-null path/identity/key bounds, positive number/size/counts/sequence minimum, ordered sequence extrema and nonnegative raw byte counts. It copies both key-bound arrays. This constructor does not decode bounds or verify their semantic key order.
- `smallestInternalKey()` and `largestInternalKey()` return clones. `smallestUserKey()` and `largestUserKey()` decode the owned bounds and obtain copied user keys; they are not allocation-free slices. Remaining generated metadata accessors return immutable references or scalars.

## Header And Footer Functions

Sources: [SSTableHeaderV1](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableHeaderV1.java)
and [SSTableFooterV1](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/SSTableFooterV1.java).

### `SSTableHeaderV1(...)`, `encode()` And `decodeRegion(...)`

Construction requires positive file number, database identity, nonnegative
counts/sequences, matching empty/nonempty entry and block counts, positive minimum
sequence for nonempty content, ordered sequence extrema and minimum file size.
Zero-count explicit fixtures are permitted here, unlike `TableFileMetadata`.
Creation time is diagnostic, not a transaction sequence.

`encode` allocates the fixed 128-byte header with magic/version/length, identity,
fixed format policy, counts, creation time and final size; it stores masked CRC32C
at byte 124 over the preceding 124 bytes. It returns only the header, not the whole
4 KiB reserved region. DATA target 16,384 and restart interval 16 are format
options, distinct from the configured whole-SSTable size target.

`decodeRegion` requires the complete 4 KiB region, zero trailing reserved bytes,
valid checksum/prefix/fixed policies and zero reserved header fields. It decodes
fields through the validating constructor, translating invalid fields to
corruption. Private `corrupt(message)` constructs format failures.

### `SSTableFooterV1(...)`, `encode()` And `decode(...)`

Construction requires all four metadata handles, positive file number, database
identity and minimum size. It does not call `validateHandles` itself. `encode`
allocates 128 bytes, stores magic/version, handles, identity and size, and writes
masked CRC32C over the first 124 bytes. `decode` requires exact size, valid checksum,
canonical prefix/reserved bytes and valid constructor fields. It decodes handles
through private `handle(ByteBuffer)`, which copies each 16-byte representation
before delegating to `BlockHandle.decode`.

### `validateHandles()`, `overlap(...)` And `corrupt(...)`

`validateHandles` checks each mandatory metadata handle against the footer offset,
then tests every pair with private `overlap`, using half-open byte intervals.
It rejects overlap, not all gaps or every alternative metadata ordering. DATA
handle validation occurs in the reader/verifier, not this method. Private
`corrupt` constructs footer-format failures. Generated record accessors expose
immutable handles/identity and scalars.

## Reading The Tests

[StreamingVerifierTest](../../modules/aether-sstable/src/test/java/io/aetherdb/sstable/StreamingVerifierTest.java)
covers the streaming path. Tests and function-name inventory serve different
purposes: name coverage catches reference omissions, while behavior tests establish
which valid/corrupt files each implementation accepts. Neither should be described
as proof that all reader and verifier acceptance rules are identical.
