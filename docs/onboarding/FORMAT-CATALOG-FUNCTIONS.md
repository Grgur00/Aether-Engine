# Format Catalog Functions

[Function index](FUNCTION-INDEX.md) | [Manifest wire formats](MANIFEST-CODEC-FUNCTIONS.md)

This reference describes the current `aether-format` catalog source. Catalog
metadata identifies known formats and canonical-fixture expectations; it does not
parse files, negotiate compatibility, authenticate bytes, or certify a release.

## Architecture and Authority

`AetherFormatCatalog` maps IDs to descriptors. `FormatGoldenFixtureCatalog` maps
format/name pairs to expected lengths and SHA-256 strings. Both are immutable
in-memory catalogs rebuilt by their factory calls, not registries dynamically
populated from implementation classes or fixture files.

Descriptors are not automatically checked against encoder constants. For example,
the native-record descriptor currently declares `AENR` magic and header size 16,
while [NativeRecordFormatV1](../../modules/aether-memory/src/main/java/io/aetherdb/memory/NativeRecordFormatV1.java)
defines a 24-byte header and its writer has no magic field. The catalog also names
`aether-security` as an owner even though module ownership must be checked against
the actual module guide. These are existing descriptive inconsistencies, not
format changes made by this documentation. Read the encoder/decoder for exact
byte contracts.

## Descriptor Model

Source: [FormatDescriptor.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/FormatDescriptor.java).

### `FormatDescriptor(id, kind, magic, version, headerBytes, byteOrder, checksum, compatibility, ownerModule)`

Requires ID matching `aether.` plus an allowed lowercase identifier and `.v` plus
digits; positive numeric version; nonnegative header size; nonblank magic and
owner; and nonnull kind/order/checksum/compatibility enums. It does not check ID
suffix agrees with numeric version, owner is a real module, or magic/header match
an encoder. Zero header size permits text and implicit representations.

Implicit record accessors expose the immutable scalar/string/enum fields. The
string called magic can be descriptive text such as `implicit` or `json`, not
necessarily literal bytes found in an encoded object.

## Current Format Registry

Source: [AetherFormatCatalog.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/AetherFormatCatalog.java).

### `AetherFormatCatalog(descriptors)` and `current()`

Private construction copies the map immutably. `current` builds 26 hard-coded
descriptors spanning native records, internal keys, WAL, SSTables and subformats,
manifest/CURRENT, database identity/options/checkpoint metadata, RPC, replicated
logs, Raft voting/state, schema locks, security metadata, and backup manifests.
Each call builds a new catalog; there is no singleton, discovery, or disk read.

### `add(...)`

Private helper constructs a validated descriptor and inserts by ID, throwing if
one already existed. The duplicate replacement happens before the exception;
normal `current` construction has no duplicates and does not expose that partial
map. Insertion order is not a public iteration-order guarantee after `Map.copyOf`.

### `descriptors()` and `require(id)`

The first returns the stored immutable map. `require` returns a matching descriptor
or throws an unknown-format `IllegalArgumentException`. It does not select a reader,
load an implementation, validate file bytes, or enforce compatibility policy.

## Policy Enumerations

Sources: [FormatKind.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/FormatKind.java),
[ByteOrderPolicy.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/ByteOrderPolicy.java),
[ChecksumPolicy.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/ChecksumPolicy.java),
and [CompatibilityPolicy.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/CompatibilityPolicy.java).

`FormatKind` groups storage files/records, metadata files, wire frames, schema
locks, security metadata, and backup objects. `ByteOrderPolicy` names little-endian,
big-endian, JSON text, or mixed encoding. `ChecksumPolicy` names none, masked CRC32C,
AES-GCM tag, SHA-256 manifest, or format-specific protection.

`CompatibilityPolicy` names exact-version-only, backward-compatible reader,
length-delimited optional fields, or text-schema compatibility. Enum membership
does not implement those behaviors. An internal record declaring no checksum may
be protected by an outer envelope; a format-wide checksum label does not specify
which exact ranges a parser checks. See the byte-level references for coverage.

## Golden Fixture Model

Source: [FormatGoldenFixture.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/FormatGoldenFixture.java).

### `FormatGoldenFixture(formatId, fixtureName, byteLength, sha256Hex, description)`

Requires a syntactically valid format ID, lowercase fixture name starting with
letter/digit and continuing with letters/digits/dots/hyphens, positive byte length,
exactly 64 lowercase hexadecimal digest characters, and nonblank description.
Strips description's surrounding whitespace. Implicit accessors expose those
fields. The constructor does not hash bytes, locate a resource, or check that
the format ID is registered; registration handles the latter separately.

## Golden Fixture Registry

Source: [FormatGoldenFixtureCatalog.java](../../modules/aether-format/src/main/java/io/aetherdb/format/catalog/FormatGoldenFixtureCatalog.java).

### `FormatGoldenFixtureCatalog(fixtures)` and `current(formats)`

Private construction copies the map immutably. `current` builds 21 hard-coded
fixture expectations and checks their format IDs through the supplied catalog.
They cover selected WAL, SSTable, manifest, identity/options/checkpoint, RPC,
replicated-log, and Raft encodings. This is not one fixture for every catalog
descriptor and not every input case for an encoder.

Fixture length can include a complete region rather than just a descriptor's
fixed header: the WAL header fixture is 32 KiB, for example. Different numbers
therefore need not indicate drift. Construction does not read fixture files or
compare actual encoder output to the declared digest.

### `add(formats, fixtures, fixture)` and `key(formatId, fixtureName)`

`add` requires a known format ID, inserts under the joined format/name key, and
throws on duplicate. The private key helper joins strings with `/`; it does not
perform validation itself. Valid fixture model fields exclude that separator.

### `fixtures()` and `require(formatId, fixtureName)`

Return the immutable map or retrieve a matching expectation. Missing pair throws
`IllegalArgumentException`. Neither validates an actual binary fixture or executes
a compatibility test. Map iteration order is not promised.

## Checksum Implementation

The separate `MaskedCrc32c` functions are covered in the
[manifest wire-format reference](MANIFEST-CODEC-FUNCTIONS.md). They compute byte
checksums; the policy enum only describes a chosen protection mechanism.

## Review Boundaries

Compare descriptors against actual encoder constants and field coverage. Compare
fixture digests against exact emitted bytes with fixed inputs, not only against
another metadata string. Record any mismatch rather than silently altering an
established wire format to match a stale catalog. Function-name coverage catches
omissions, not catalog correctness, fixture execution, or release certification.
