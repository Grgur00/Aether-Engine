# Typed Key and Namespace Functions

[Typed architecture](TYPED-API-AND-SCHEMAS.md) | [Function index](FUNCTION-INDEX.md)

This reference describes current key codec and collection-prefix source. Typed
keys eventually become ordinary byte-engine keys; their encoding determines
physical ordering. These helpers do not open a collection, write schema metadata,
perform a database lookup, or acquire a snapshot.

## Codec Contract

Sources: [KeyCodec.java](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/KeyCodec.java)
and [OrderedKeyCodec.java](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/OrderedKeyCodec.java).

`codecId()` identifies a stable family; `encodingVersion()` identifies emitted
encoding; `maximumEncodedSize()` declares a byte bound; `fingerprint()` declares
a defensive 32-byte identity fingerprint. `encode(value)` and `decode(encoded)`
translate logical keys and bytes. `OrderedKeyCodec.comparator()` declares a logical
ordering that should agree with unsigned lexical encoded-byte ordering.

The interfaces do not enforce these promises on arbitrary implementations.
Maximum size is a declaration, not an allocation limiter; fingerprint is codec
identity, not a checksum of an individual encoded key. Caller validation matters.

## Built-In Factories

Source: [BuiltInKeyCodecs.java](../../modules/aether-codec/src/main/java/io/aetherdb/codec/BuiltInKeyCodecs.java).

### `BuiltInKeyCodecs()` and `utf8String()`

The utility constructor is private. `utf8String` creates a new anonymous ordered
codec with ID `aether:utf8`, maximum 65,517 bytes, and Java natural string comparator.
Its `encode` rejects null and returns UTF-8 bytes; it does not itself reject an
overlong result. Its `decode` constructs a Java string from UTF-8 bytes using the
standard replacement behavior for malformed input, not strict decoding.

The declared comparator uses UTF-16 code-unit order, while physical bytes use
UTF-8 order. These differ for some supplementary/BMP comparisons: U+10000 sorts
before U+E000 under Java natural order but after it under unsigned UTF-8 bytes.
Unpaired surrogates also undergo replacement during encoding. Do not assume this
codec is a lossless order-preserving mapping for every possible Java string.
Ordinary ASCII keys do not encounter those particular differences.

### `signedLong()`

Creates ID `aether:i64`, maximum eight bytes, and natural signed-long comparator.
Its `encode` XORs the sign bit with `Long.MIN_VALUE` and writes eight big-endian
bytes using `ByteBuffer` default order. This transforms signed numeric order into
unsigned lexical byte order. Null boxed input fails during unboxing.
Its `decode` requires exactly eight bytes, reads big-endian long, and reverses the
sign-bit transform. It does not accept variable-width integer encodings.

### `uuid()`

Creates ID `aether:uuid`, maximum sixteen bytes. Its comparator compares most
significant then least significant 64-bit components unsigned; it is not Java
UUID's signed-component natural comparator. `encode` writes those components in
big-endian order. `decode` requires exactly sixteen bytes and rebuilds the UUID.
Null inputs have no explicit custom guard. Every UUID, including zero, is allowed
as a logical key even though zero collection identity is forbidden separately.

### `forType(type)`

Rejects null class, resolves String, boxed or primitive long, and UUID to newly
created built-ins. Other types raise an `IllegalArgumentException` containing
`CODEC_NOT_AVAILABLE`. It does not invoke service loading, resolve record keys,
or coerce integer classes. The unchecked generic cast is backed by the class match.

### `Base(id, max, cmp)` and shared metadata methods

The private nested abstract base stores supplied metadata without validation.
`codecId` returns ID, `encodingVersion` returns 1, `maximumEncodedSize` returns
the declaration, and `comparator` returns the stored comparator.
`fingerprint` computes SHA-256 of UTF-8 `id + ":v1"` on every call, returning a new
array. Missing SHA-256 becomes `IllegalStateException`. It is not cached and does
not hash function implementations, comparator behavior, or encoded key bytes.

## Collection Identity

Source: [CollectionId.java](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/CollectionId.java).

### `CollectionId(value)` and `of(value)`

The compact record constructor rejects null and all-zero UUID. Its implicit
`value()` accessor returns the immutable UUID. `of` calls `UUID.fromString` then
the constructor; it adds no independent canonical-text roundtrip check beyond
the platform parser. Invalid/null text follows that parser's errors.

### `fromName(name)`

Requires nonnull, nonblank name of at most 128 UTF-8 bytes. Prepends UTF-8
`io.aetherdb.collection.v1` followed by a zero byte, copies the name bytes, and
uses `UUID.nameUUIDFromBytes` for deterministic version-three identity.
Mapping is case-sensitive and performs no trimming or Unicode normalization.
Renaming changes identity and therefore physical keyspace. Use explicit identity
when a display name should change without moving keys.

## Physical Key Envelope

Source: [TypedKeyEnvelope.java](../../modules/aether-codec/src/main/java/io/aetherdb/codec/TypedKeyEnvelope.java).

### `TypedKeyEnvelope()` and `prefix(definition)`

Private utility constructor. `prefix` allocates nineteen bytes: marker `0x40`,
sixteen big-endian collection UUID bytes, then two big-endian key-encoding-version
bytes. It narrows version to short without independently checking its range.
Collection-definition validation is a separate boundary; this prefix omits codec
fingerprint, schema identity, collection name, and value-codec version.

### `encode(definition, key)`

Calls the key codec, rejects null output or bytes longer than its declared maximum,
then allocates prefix plus logical bytes and copies both. It does not independently
apply the engine's total key-size cap or use checked addition for prefix plus
length. The built-in string declaration leaves nineteen bytes within a 65,536-byte
key limit. Custom codec correctness and bounds remain caller responsibilities.

### `decode(definition, physical)`

Builds expected prefix, requires sufficient physical length and exact prefix
match, then copies remaining bytes and delegates to the key codec. Prefix comparison
also creates an array copy. It does not apply the declared maximum to the suffix,
validate a value envelope, or consult collection metadata in a database.
Null definition/bytes have no explicit custom guards.

### `prefixEnd(definition)`

Builds prefix and walks backward to the last byte not equal to 255, increments it,
and returns a copy truncated just after that byte. This is the shortest exclusive
upper bound for all byte strings with the original prefix, not the next complete
collection ID. The no-successor exception cannot occur for a normally generated
prefix because its leading byte is `0x40`. A collection scan uses prefix inclusive
and this bound exclusive; a logical range also needs suitable encoded key bounds.

## Review Boundaries

Test logical versus byte ordering, Unicode edge cases, codec size declarations,
version narrowing, and rename identity. Do not change a stable encoding merely
to match a comparator without a compatibility/migration decision. Function-name
coverage checks omissions, not codec injectivity, order preservation, schema
compatibility, or collection-open behavior.
