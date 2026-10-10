# WAL Format Function Reference

[Persistent internals](PERSISTENT-INTERNALS.md) | [Manifest publication](MANIFEST-VERSION-FUNCTIONS.md)

## Architecture And Responsibility

The WAL format module supplies byte codecs, not a live log service. The engine
assigns sequences, chooses/rotates segment files, writes/forces bytes and applies
mutations to the memtable. During recovery it validates segment identity,
reassembles physical records, decodes logical groups and checks continuity against
the manifest watermark before replay. No codec here opens a file or publishes
database visibility.

A segment begins with a 32 KiB header block. Subsequent bytes contain 16-byte
physical fragment headers and payloads within 32 KiB blocks. A logical mutation
group may span multiple fragments. Its 48-byte logical header describes assigned
sequences and operation count; each operation has a 12-byte header plus key/value
bytes. All multi-byte fields are little-endian.

| Identity | Meaning | Who checks it |
|---|---|---|
| Database UUID and segment number | Owning file identity. | Segment-header decoder against caller expectations. |
| Physical record number | Segment-local contiguous group position, starting at one. | Fragment reassembly. |
| Fragment index | Position within one physical group, starting at zero. | Fragment reassembly. |
| First/last sequence | Logical MVCC range across ordered mutations. | Logical codec internally; engine checks cross-group recovery continuity. |

These counters are not interchangeable. A correctly framed group need not be the
next group the database should apply.

## Frozen Sizes And Arithmetic

Source: [WalFormatV1.java](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalFormatV1.java).

The private `WalFormatV1()` constructor prevents utility instantiation. Constants
declare 64 MiB segment capacity, 32 KiB physical/header blocks, 96 meaningful
segment-header bytes, 16-byte fragment headers, 48-byte group headers, 12-byte
operation headers and a 48 MiB maximum logical group for fragmentation. The
24-byte `BATCH_HEADER_BYTES` constant is not used by the logical-group codec
described below; do not insert an extra batch prefix based only on its name.

### `estimateEndOffset(start, logicalLength)`

Requires a start at or beyond the reserved header block and a positive logical
length no larger than the fragmentation maximum. Simulates fragmentation using
checked offset addition: if remaining block capacity is at most 16 bytes, it
skips zero trailer padding; otherwise it accounts for one header and the payload
that fits. Returns an exclusive end offset, not bytes written or segment count.
It does not itself reject an end beyond the 64 MiB segment capacity; the engine
uses the result to choose rotation.

### `fileName(segment)`

Requires a positive segment and formats `WAL-%020d.aewal`. It returns a name only,
not a managed/safe path. This implementation uses `String.formatted` without an
explicit locale, unlike some manifest naming helpers.

## Segment Header Functions

Source: [WalSegmentHeader.java](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalSegmentHeader.java).

### `WalSegmentHeader(...)` And `encodeBlock()`

The record has no explicit validating constructor; generated construction can
hold invalid fields until encoding. `encodeBlock` requires identity, positive
segment/first sequence, nonnegative predecessor number and nonnegative creation
timestamp. It allocates the entire zero-filled 32 KiB block and writes magic
`AETHWAL1`, version one, 96-byte prefix size, block size, segment capacity, UUID,
segment/predecessor numbers, first eligible sequence, timestamp, flags and reserved
zero bytes. Masked CRC32C over bytes 0 through 91 is stored at offset 92.

Predecessor number is not required to be immediately preceding this segment, and
creation time is not a visibility sequence. The codec does not prove a segment
chain exists. Padding after byte 95 is not covered by that checksum but is checked
separately during decode.

### `decode(block, expectedDatabase, expectedSegment)`

Requires exactly one header block, matching magic/version/options, zero flags and
reserved bytes, correct checksum and zero remaining block padding. It then
matches UUID and segment number against caller expectations and validates scalar
field bounds before returning the record. It does not compare `firstSequence`
with replayed groups or validate the predecessor against another physical file.

There is no initial null-block guard; null produces `NullPointerException`, not
the dedicated corruption exception. Private `corrupt(message)` supplies direct
format errors. Generated record accessors return immutable identity/scalars.

## Logical Mutation Groups

Source: [WalLogicalGroupCodec.java](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalLogicalGroupCodec.java).

### `WalLogicalGroupCodec()` And `encode(batch, first, last)`

The private constructor prevents instantiation. Encoding requires a batch and a
positive inclusive sequence range whose size equals operation count. It computes
total header/operation/key/value size using checked integer addition, allocates
one group array and writes magic `AETHGRP1`, format/header length, total bytes,
sequence bounds, count and reserved zero fields.

Mutations preserve batch order. PUT uses type one and includes its value; DELETE
uses type two and an empty value. Each operation stores type, three zero flag
bytes and key/value lengths, then payload bytes. The logical header CRC covers
only its first 44 bytes and is stored in bytes 44 through 47. Operation bytes are
protected by enclosing fragment checksums, not that logical-header checksum.

Batch mutation accessors return copies. Encoding calls them during sizing and
again during writing, so it is not a zero-copy serialization path. It neither
seals the batch nor marks it submitted; commit coordination owns that lifecycle.

### `decode(encoded)`

Requires a complete group header, matching magic/version/header/total length,
reserved zero fields, valid header checksum, a positive consistent sequence
range and operation count no greater than `WriteBatch.MAX_OPERATIONS`.

For each operation it requires a full fixed header, zero flags, valid PUT/DELETE
type, nonnegative key/value lengths within batch per-field limits and enough
remaining bytes. DELETE must have zero value length. It copies key/value payloads
into arrays, constructs owning `Mutation` records, and rejects trailing bytes.
Returns a `DecodedGroup`. These checks do not prove physical fragment integrity
if callers bypass reassembly; the logical header CRC does not cover value bytes.

The decoder enforces per-operation sizes/count but does not independently check
`WriteBatch.MAX_ENCODED_BYTES`. Do not equate all decoded-group limits with the
application batch admission contract.

### `DecodedGroup(...)`, `Mutation(...)` And Accessors

`DecodedGroup(firstSequence, lastSequence, mutations)` requires a positive ordered
range matching list length and copies the list immutably. Direct construction
does not enforce the decoder's operation-count maximum. Generated `mutations()`
returns the immutable list of owning mutations.

`Mutation(key, value, delete)` requires non-null arrays and clones both. Its
`key()` and `value()` accessors clone again; generated `delete()` returns a boolean.
Direct record construction does not enforce per-field limits or zero DELETE value
length. Decode establishes those stronger conditions before constructing it.

Private `little(bytes)` borrows an array through a little-endian buffer;
`corrupt(message)` constructs `WalCorruptionException`.

## Physical Fragment Encoding

Source: [WalFragmentCodec.java](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalFragmentCodec.java).

### `WalFragmentCodec()` And `fragment(logical, startOffset, recordNumber)`

The private constructor prevents instantiation. `fragment` requires non-null,
nonempty logical bytes at most 48 MiB and a positive record number. Unlike the
size estimator, it does not validate start offset against the reserved block or
check segment capacity; valid offsets are the caller's responsibility.

At each physical block boundary, remaining space of at most 16 bytes becomes
zero padding. Otherwise it writes as much payload as fits after a fragment header.
Type is FULL when the whole record fits, otherwise FIRST, zero or more MIDDLE,
then LAST. Headers encode payload length as unsigned short, type/version, record
number, fragment index and zero flags. CRC32C covers header bytes after its first
four plus payload. Output is one owning array containing all fragments and
padding; input contents are copied, not retained as scatter/gather views.

## Reassembly And Forensic Prefix Recovery

### `reassemble(physical, startOffset)`

Starts expected physical record at one and fragment index at zero. It validates
zero bytes only in the short physical-block trailer, copies fragment headers,
verifies complete-fragment checksums and requires version one, zero flags and
expected record/fragment counters. FULL emits a complete payload; FIRST begins
assembly, MIDDLE extends it, and LAST emits/clears assembly. Other type sequences
throw corruption. Completed groups advance the record counter and reset index.

The returned list contains materialized group arrays; it is an ordinary mutable
list, not a defensive-copy result record. Incomplete assembly is never emitted.
The method does not decode logical mutations or enforce a reassembled total-size
maximum. It expects the stream from its first segment-local record, not an
arbitrary middle-of-segment slice.

Its tail policy is broader than checksum rejection: fewer than 16 remaining bytes
ends processing; zero declared payload length, a length exceeding block capacity
or insufficient supplied bytes also **breaks and returns completed records**.
A large all-zero fragment header, bad checksum or metadata/type mismatch throws.
Thus not every malformed length is surfaced as corruption. Null input and invalid
start offsets are not explicitly validated by this API.

### `recoverPrefix(physical, startOffset)`

Requires non-null bytes and start at or beyond the header block. Replays the same
framing while tracking the last complete physical offset. It returns incomplete
header/payload or unfinished-group issues as strings, and catches
`WalCorruptionException` to return the completed prefix plus corruption boundary
instead of throwing that error. Other unchecked failures are not universally
converted to an issue.

Valid short-block zero padding extends the verified end when no record is being
assembled. Bytes of a partial multi-fragment record do not extend the last complete
boundary. The method does not truncate files, repair logs or approve decoded
mutations for replay; it is an offline salvage observation.

### `recovery(...)`, `PrefixRecovery(...)`, `records()` And `hasIssue()`

Private `recovery` clones each emitted group, then constructs `PrefixRecovery`,
whose constructor clones each group again and requires a non-null list and valid
end offset. The custom `records()` accessor returns new group clones every time.
Generated `validEndOffset()` and `issue()` return scalar/string observations.
`hasIssue()` reports whether the string is non-null, without classifying whether
it means incomplete tail versus corruption.

These ownership copies isolate forensic results, but the constructor does not
independently prove that each supplied group was checksum-verified. Private
`corrupt(message)` constructs framing failures.

## Exception And Test Boundaries

Source: [WalCorruptionException.java](../../modules/aether-wal/src/main/java/io/aetherdb/wal/format/WalCorruptionException.java).

`WalCorruptionException(message)` preserves the failure description and extends
`RuntimeException`, not `IOException` or `IllegalArgumentException`. Prefix salvage
catches that specific type; ordinary reassembly propagates it. The exception has
no repair side effects.

The engine's [recovery helpers](PERSISTENT-INTERNALS.md) supply database-level
watermark, segment and visibility checks above these byte codecs. Documentation
function-name coverage catches omitted symbols, not proof that every malformed
tail policy or crash sequence has test coverage.
