# Manifest Wire Format Functions

[Publication and versions](MANIFEST-VERSION-FUNCTIONS.md) | [Block codecs](SSTABLE-BLOCK-FUNCTIONS.md)

These codecs turn validated metadata into canonical bytes and decode bytes back
into field-validated objects. They perform no disk writes, force, atomic rename,
version installation or SSTable verification. Those operations belong to
`VersionSet`. A valid checksum is not proof that a file is authoritative.

## Format Layers

| Layer | Fixed prefix | Variable content | Checksum coverage |
|---|---|---|---|
| `CURRENT` | 128 bytes total | None; filename occupies a padded fixed region. | First 124 bytes; stored in final four. |
| Manifest header | 96 bytes in a 4 KiB region | None. | First 92 bytes; remaining region is separately checked for zero padding. |
| Physical record | 24-byte header | One complete edit payload. | Bytes from offset four to record end; stored in first four. |
| Edit payload | 64-byte header | Addition descriptors and deletion descriptors. | Covered by enclosing physical record; no separate payload CRC. |

All multi-byte numeric fields here are little-endian. UUIDs are stored as two
little-endian longs in most-significant/least-significant order, not as textual
UUID strings. The permitted payload maximum is 4 MiB, excluding its 24-byte
physical header.

## CURRENT Pointer Codec

Source: [CurrentFileV1.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/CurrentFileV1.java).

### `CurrentFileV1()` And `manifestName(generation)`

The private constructor prevents utility instantiation. `manifestName` requires a
positive generation and returns `MANIFEST-%020d.aeman` using `Locale.ROOT`. It does
not resolve paths, check filesystem containment or check whether the file exists.

### `encode(databaseId, generation)`

Requires non-null identity and obtains a canonical name through `manifestName`.
Allocates a zero-filled 128-byte array and writes magic `AETHCUR1`, version one,
declared size, zero flags, generation, name length, reserved zero and name bytes.
The 64-byte name region starts at offset 28. UUID occupies offsets 92 through 107;
a repeated publication generation occupies 108 through 115. Reserved bytes remain
zero, and masked CRC32C of bytes 0 through 123 is stored at 124.

It returns bytes only. Calling encode does not publish `CURRENT` or authorize a
manifest generation.

### `decode(encoded, expectedDatabaseId)`

Requires exact size and expected identity, checks checksum, magic/version/size and
zero prefix flags, then reads generation and the declared filename length. The
name must occupy one through 64 bytes; unused name-region bytes must be zero.
It decodes ASCII, reads UUID and repeated generation, and requires trailing
reserved bytes to be zero.

Finally it matches UUID, repeated generation and canonical filename for the
leading generation. It returns a `Pointer`, not a file handle or decoded manifest.
Most failures use `ManifestCorruptionException`; a nonpositive generation reaching
`manifestName` can instead throw its plain `IllegalArgumentException`. This method
does not normalize every possible invalid input to one exception subtype.

### `Pointer`, `little`, `putUuid`, `getUuid` And `corrupt`

`Pointer(generation, manifestName)` is a record with generated scalar/string
accessors and no explicit validating constructor. The decoder establishes its
invariants; direct record construction does not perform those checks.

Private `little(value)` wraps an array in a little-endian buffer, without copying.
`putUuid(target, value)` writes the UUID's two long components;
`getUuid(source)` reconstructs them in the same order. Both rely on valid buffer
position/capacity supplied by callers. `corrupt(message)` constructs descriptive
pointer-format exceptions.

## Manifest Header Region

Source: [ManifestHeaderV1.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestHeaderV1.java).

### `ManifestHeaderV1(...)`

Requires database identity, positive manifest number and initial-next-file counter,
nonnegative creation time and initial-last-sequence. These are header diagnostics;
the constructor does not require next-file counter beyond generation or compare
its diagnostics with a snapshot record. Publication and recovery add those checks.

### `encodeRegion()`

Allocates the entire zero-filled 4 KiB region. Writes `AETHMAN1`, version one,
96-byte fixed-header declaration, flags zero, identity, manifest number,
comparator identifier one, seven-level declaration, creation time and initial
counters. Bytes 72 through 91 remain reserved zero. It stores masked CRC32C of
the first 92 bytes at offset 92. The remaining region is zero padding.

Unlike the SSTable header's `encode`, this method returns the complete reserved
region, not just the fixed prefix.

### `decodeRegion(region)`, `little` And `corrupt`

Requires exactly 4 KiB, valid fixed-header checksum/prefix, supported comparator
and level count, zero reserved fixed fields and zero region padding. Constructs
the validated header from decoded fields, wrapping invalid constructor fields
as `ManifestCorruptionException` with their cause. Padding is enforced explicitly,
not covered by the fixed-header checksum.

Private `little` is a borrowing byte-buffer wrapper. `corrupt` constructs direct
format errors. Generated record accessors return immutable identity or scalars;
they do not replay edits or establish pointer authority.

## Physical Record Functions

Source: [ManifestCodecV1.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestCodecV1.java).

### `ManifestCodecV1()` And `encodeRecord(edit)`

The private utility constructor prevents instantiation. `encodeRecord` requires an
edit, calls `encodePayload`, allocates header plus payload and writes a 24-byte
header. At offset four are payload length, version one, kind one for snapshot or
two for delta, flags zero, edit/record number and reserved zero. It appends the
payload and writes masked CRC32C over all bytes after the checksum field.

The duplicated outer/inner kind and edit number are deliberate consistency fields,
not independent transaction IDs. The function does not choose or increment numbers.

### `decodeRecord(record)`

Requires at least a physical header plus edit header and verifies checksum over
the exact supplied record. It then requires payload length in the allowed range,
exact record size, version one, flags/reserved zero and positive record number.
It copies the payload and delegates to `decodePayload`, then matches outer kind
and record number with the decoded edit.

This expects exactly one record. Passing concatenated records is not stream replay;
their aggregate length/checksum will not satisfy this contract.

### `physicalRecordBytes(header)`

Requires exactly 24 bytes, reads the payload-length field and checks it is between
64 bytes and 4 MiB. Returns that length plus 24. It does **not** validate checksum,
version, kind, flags or record number. Recovery uses it to decide whether enough
bytes remain to attempt full decode; its successful return is only a size probe.

If a tail contains a length-valid header declaring more bytes than are available,
replay classifies it as incomplete without validating the other header fields.
If a complete record is available, `decodeRecord` supplies full validation.

## Edit Payload Functions

### `encodePayload(edit)`

Private encoding computes 64 bytes plus 16 per deletion and, per addition, 64 fixed
bytes plus both encoded key-bound lengths. It uses checked addition for addition
sizes and rejects totals above 4 MiB before allocating the payload. Accessing key
bounds takes defensive copies, including during sizing.

The header writes total length, header size/version, kind/flags/reserved fields,
edit number, next-file counter, assigned sequence, persisted watermark, minimum
WAL number and addition/deletion counts. Additions encode file number, level,
size/count/sequence extrema, bound lengths, reserved zero and bound bytes.
Deletions encode number, level and reserved zero. Input list order is preserved;
the codec does not sort additions or construct a live inventory.

### `decodePayload(payload)`

Private decoding validates total length, fixed header size/version, kind code,
flags/reserved fields and plausible nonnegative counts. Each addition requires
its fixed prefix, zero flags/reserved long and two key bounds of at least nine
bytes whose lengths fit remaining input. It allocates bound arrays and constructs
`ManifestFileMetadata`, which performs internal-key and field checks and clones
those bounds again.

Each deletion requires 16 remaining bytes and reserved zero, then constructs a
validated `ManifestDeletion`. Any unconsumed trailing bytes are corruption.
Finally `ManifestEdit` validates counters and duplicate/contradictory operations
and takes immutable list copies. This is field/record validation, not verification
that deletions name live files or that additions exist on disk.

Existing `ManifestCorruptionException` is rethrown. `IllegalArgumentException` and
`ArithmeticException` from field validation become manifest corruption with their
cause. The catch does not list `BufferUnderflowException`; fixed-prefix and length
checks are what prevent truncated inputs from reaching unchecked buffer reads.

### `little(value)` And `corrupt(message)`

Private `little` wraps the supplied array without copying and selects byte order.
`corrupt` constructs the dedicated exception; neither performs recovery or repair.

## Shared Checksum Functions

Source: [MaskedCrc32c.java](../../modules/aether-format/src/main/java/io/aetherdb/format/checksum/MaskedCrc32c.java).

The private `MaskedCrc32c()` constructor makes this a utility. `crc(bytes, offset,
length)` creates a Java `CRC32C`, updates it over the range and returns the 32-bit
value as an `int`; Java's checksum API validates the supplied range.
`masked(...)` composes `crc` with `mask`. `mask(crc)` rotates right by 15 and adds
the fixed delta `0xA282EAD8`, with Java integer wraparound. `unmask(stored)`
subtracts that delta and rotates left by 15 to invert the transform.

Masking is a storage representation transform, not encryption or authentication.
CRC32C detects accidental corruption; it does not prove identity, authorization,
freshness or resistance to a deliberate attacker able to recompute checksums.
Identity and structural checks must still run after checksum validation.

## Corruption Exception Constructors

Source: [ManifestCorruptionException.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestCorruptionException.java).

`ManifestCorruptionException(message)` preserves a validation detail.
`ManifestCorruptionException(message, cause)` also preserves the originating field
or transition failure. The class extends `IllegalArgumentException`, so callers
catching that superclass also catch manifest corruption. These constructors have
no filesystem or repair behavior.

## Verification Boundary

[ManifestFormatV1Test](../../modules/aether-sstable/src/test/java/io/aetherdb/sstable/manifest/ManifestFormatV1Test.java)
is the starting point for format regression coverage. A record successfully decoded
here can still be rejected by `Version.apply` or inventory verification. Function
inventory tests catch missing names in this page, not correctness of every wire
offset or equivalence between malformed-input acceptance paths.
