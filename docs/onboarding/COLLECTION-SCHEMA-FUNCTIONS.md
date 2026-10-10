# Collection Metadata and Schema Compatibility Functions

[Typed keys](TYPED-KEY-FUNCTIONS.md) | [Typed values](TYPED-VALUE-FUNCTIONS.md)

These functions describe current collection definitions, persisted metadata, and
generated-schema evolution checks. They do not themselves open a collection,
persist an upgrade, rewrite existing values, or prove a custom codec can decode
old bytes. The typed adapter orchestrates those operations separately.

## Collection Definition

Sources: [CollectionDefinition.java](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/CollectionDefinition.java)
and [CollectionCapability.java](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/CollectionCapability.java).

### `CollectionDefinition(id, name, keyCodec, valueCodec, capabilities)`

Requires nonnull identity/codecs and nonblank name no longer than 128 UTF-8 bytes.
Key version must be 1 through 65,535; declared maximum key size must be 0 through
65,517. Both fingerprints must return nonnull arrays of length 32. Those methods
are called repeatedly rather than storing one validated fingerprint snapshot.

Null capabilities default to point read/write; otherwise the set is copied
immutably. `RANGE_SCAN` requires `OrderedKeyCodec` type, but construction does not
prove comparator agreement with encoded byte order. `SNAPSHOT_READ` is an available
capability label, not a snapshot lease acquired here.

It does not validate key codec ID, value schema UUID/version, value size declarations,
or custom codec determinism. Implicit accessors expose supplied codec objects,
identity/name, and immutable capabilities; codec objects themselves are not copied.

### `of(id, name, keyCodec, valueCodec)`

Creates the same validated record with point reads and writes only. Capability
enforcement belongs to adapter methods, not enum declarations. The other labels
are `RANGE_SCAN` and `SNAPSHOT_READ`.

## Durable Metadata Model

Source: [CollectionMetadata.java](../../modules/aether-codec/src/main/java/io/aetherdb/codec/CollectionMetadata.java).

### `CollectionMetadata(...)` and defensive accessors

Requires identity, nonblank name, nonnull key codec ID, positive key/schema versions,
nonnull schema UUID, two 32-byte fingerprints, and nonnull descriptor at most 1 MiB.
Clones all three binary fields. `keyFingerprint()`, `schemaFingerprint()`, and
`schemaDescriptor()` return fresh copies; scalar/string/UUID accessors use normal
record behavior. Array-based record equality remains identity-based, not content
comparison; use explicit compatibility methods for that purpose.

This constructor is less restrictive than `CollectionDefinition`: it does not
cap name bytes, reject blank codec ID, limit key version to two bytes, or reject
zero schema UUID. Descriptor bytes need not parse as a generated descriptor.
The 1 MiB descriptor allowance is not a guarantee its Base64 metadata will encode
within the separate 1 MiB total limit.

### `from(definition, descriptor)` and `key()`

`from` gathers definition metadata and optional generated descriptor, supplying
an empty array if absent. It does not automatically discover a generated provider.
The constructor copies fingerprints and descriptor.
`key` allocates ASCII zero-prefixed `AETHER/COLLECTION/` plus sixteen big-endian
collection UUID bytes. This reserved metadata namespace differs from data-key
envelope marker `0x40`; name is not part of either identity key.

### `compatibleWith(other)` and `sameFamilyAs(other)`

Exact compatibility compares collection ID, key codec ID/version/fingerprint,
schema UUID/version/fingerprint. Name and descriptor are ignored. Same-family
comparison checks collection/key identity and schema UUID but ignores schema
version, schema fingerprint, name, and descriptor. Neither performs migration,
payload decoding, or a null-other guard.

### `requireCompatibleUpgradeTo(newer)`

Requires same family, otherwise throws `COLLECTION_SCHEMA_CONFLICT`, then invokes
descriptor compatibility. It does not independently compare metadata's version
numbers or fingerprints against descriptor contents. Monotonicity is checked in
parsed descriptor versions. The caller must ensure descriptor and codec metadata
refer to the same schema/version before persisting an upgrade.

## Metadata Text Encoding

### `encode()`

Builds UTF-8 text with `AETHER_COLLECTION_V1` first line and nine named fields:
ID, Base64 name, Base64 key codec ID, key version, hex key fingerprint, schema ID,
schema version, hex schema fingerprint, and Base64 descriptor. Ends with newline.
Rejects output above 1 MiB only after constructing text and bytes. This format
adds no checksum or authentication; underlying byte storage protects its own
representation.

### `decode(key, value)`

Returns empty for null key, wrong metadata-key length, or unrelated prefix without
examining value. For a metadata key, requires nonnull value no larger than 1 MiB,
decodes UTF-8 using replacement behavior, splits lines, and requires ten lines
and exact magic. Default split discards trailing empty strings, so trailing newline
handling is not a strict byte-canonicality check.

Remaining lines need a noninitial `=`; fields enter a hash map without an explicit
duplicate-key or unknown-field rejection. Required fields are then parsed using
UUID/integer/hex/Base64 parsers and the metadata constructor. Missing required
fields normally fail there. Re-encoded metadata key must equal input key, binding
embedded identity to namespace. Runtime failures in that parsing block are wrapped
as invalid metadata with their cause; earlier line/magic checks throw directly.
Field line order is not enforced. No schema descriptor compatibility is evaluated.

## Generated Schema Compatibility

Source: [SchemaCompatibilityChecker.java](../../modules/aether-codec/src/main/java/io/aetherdb/codec/SchemaCompatibilityChecker.java).

### `SchemaCompatibilityChecker()` and `requireCompatible(olderDescriptor, newerDescriptor)`

Private utility constructor. Public check parses both descriptors, requires equal
schema identity text and strictly increased version, then compares fields by ID.
Existing fields cannot disappear, change type/detail, tighten byte bound or entry
bound, or change optional to required. Required to optional is allowed. New fields
must be optional. Field names do not participate, enabling stable-ID renames.

This validates declared generated evolution, not actual old payload decoding. It
does not rewrite data, compare fingerprints, normalize UUID strings, or invoke
codecs. Enum/nested identity details are compared as exact strings.

### `parse(encoded)`

Requires nonnull, nonempty descriptor no larger than 1 MiB and exact first line
`AETHER_SCHEMA_DESCRIPTOR_V1`. Reads UTF-8 text lines into schema ID, version,
field map, and detail map. Schema ID/version repeated lines overwrite earlier
values; unknown lines are ignored. Schema ID only needs to be present, not a
valid nonempty UUID, and version must be positive.

Field rows have four through six pipe-separated components: ID, name, type,
byte bound, optional flag if present, and entry bound if present. IDs must be at
least 16 and unique; byte bound must be nonnegative. Name is ignored; type string
has no independent whitelist. Absent entry bound becomes `Integer.MAX_VALUE`.
Explicit entry bound is parsed but not independently required nonnegative.
Boolean parsing treats text other than case-insensitive `true` as false.

Detail rows require three components and unique detail ID. After parsing, every
detail must refer to a declared field. Numeric parse failure becomes a migration
error. Returns nested immutable `Descriptor` maps and `Field` records; it does not
enforce canonical line order, complete schema semantics, or strict UTF-8 validity.

### `incompatible(reason)`, `Descriptor`, and `Field`

The helper creates `IllegalArgumentException` prefixed `SCHEMA_MIGRATION_REQUIRED`.
Private records store parsed identity/version/field/detail maps and per-field
type/bounds/optionality. Their implicit constructors/accessors add no separate
validation beyond the parser. An unavailable descriptor, including the empty
descriptor of a scalar codec, cannot take this generated-upgrade path.

## Review Boundaries

Test both exact identity and generated upgrades, metadata/descriptor agreement,
stable-ID renames, relaxed/tightened bounds, and optionality. Keep parser acceptance
distinct from generated canonical output. Function-name coverage detects omissions,
not migration correctness or safe metadata publication in the typed adapter.
