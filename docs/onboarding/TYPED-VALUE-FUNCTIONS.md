# Typed Value and Envelope Functions

[Typed key functions](TYPED-KEY-FUNCTIONS.md) | [Typed architecture](TYPED-API-AND-SCHEMAS.md)

This reference describes current scalar value codec and envelope source. Generated
record/container payloads and schema compatibility checks are separate layers.
These functions do not open a database, register a schema, or commit a write.

## Encoding Architecture

`ValueCodec` creates logical payload bytes. `TypedValueEnvelope` adds a fixed
40-byte header associating payload with schema UUID and version. The byte engine
then stores that physical value and applies its own storage integrity policy.
An envelope is not a tombstone: deletion belongs to the underlying mutation API.

The envelope does not contain the codec fingerprint or a checksum. Matching schema
UUID and valid lengths do not detect arbitrary payload changes. Storage envelopes
and WAL checksums protect different outer byte ranges; they must be traced separately.

## Value Codec Contract

Source: [ValueCodec.java](../../modules/aether-api/src/main/java/io/aetherdb/api/typed/ValueCodec.java).

### Metadata and payload methods

`schemaId()` identifies durable schema UUID. `currentSchemaVersion()` declares the
version emitted by `encode(value)`. `maximumEncodedSize(value)` declares an upper
payload-size bound, potentially dependent on value. `fingerprint()` declares a
defensive 32-byte schema fingerprint. `decode(schemaVersion, encoded)` receives
the stored version so implementations can choose supported decoding behavior.

The interface does not validate custom implementations' metadata, enforce the
size bound, perform schema migration, or limit allocation before encoding. The
fingerprint identifies codec/schema expectations rather than hashing each value.
Null handling and supported versions are implementation responsibilities.

## Scalar UTF-8 Codec

Source: [BuiltInValueCodecs.java](../../modules/aether-codec/src/main/java/io/aetherdb/codec/BuiltInValueCodecs.java).

### `BuiltInValueCodecs()` and `utf8String(schemaId)`

Private utility constructor. The factory creates a new anonymous codec capturing
the supplied UUID. It does not validate null or all-zero schema ID at construction.
The scalar codec is not a generated record codec and has no field map.

### `schemaId()`, `currentSchemaVersion()`, and `maximumEncodedSize(value)`

Return captured UUID, version 1, and 16 MiB payload bound respectively. The size
method ignores its argument and does not calculate actual UTF-8 length. Its
16 MiB declaration excludes the 40-byte typed header: a maximum-sized payload
becomes a physical value larger than 16 MiB and may fail the engine's value limit.
The codec itself does not reserve storage or check that limit.

### `fingerprint()`

Computes SHA-256 of UTF-8 `aether:utf8-value:v1` on each call and returns the new
array. Missing algorithm becomes `IllegalStateException`. The fingerprint is
independent of the captured schema UUID and actual value. There is no cached
digest or checksum of encoded data.

### `encode(value)` and `decode(version, bytes)`

Encode returns UTF-8 bytes without explicit null or length validation. Decode
rejects versions other than 1, then builds a string using Java's standard UTF-8
replacement behavior for malformed input. Unpaired UTF-16 surrogates are replaced
during encoding, so not every possible Java string round-trips losslessly.
The codec does not implement strict decoding, normalization, or a migration path.

## Envelope Layout

Source: [TypedValueEnvelope.java](../../modules/aether-codec/src/main/java/io/aetherdb/codec/TypedValueEnvelope.java).

All multibyte fields use big-endian order. `HEADER_BYTES` is 40.

| Offset | Field | Width |
| --- | --- | --- |
| 0 | ASCII `AETV` magic | 4 bytes |
| 4 | Envelope version 1 | 2 bytes |
| 6 | Header length 40 | 2 bytes |
| 8 | Schema UUID most significant bits | 8 bytes |
| 16 | Schema UUID least significant bits | 8 bytes |
| 24 | Payload schema version | 4 bytes |
| 28 | Zero flags | 4 bytes |
| 32 | Payload byte length | 4 bytes |
| 36 | Zero reserved field | 4 bytes |
| 40 | Codec payload | Variable |

Envelope version describes this wrapper; schema version describes payload encoding.
They are distinct numbers and are not required to match.

## Envelope Functions

### `TypedValueEnvelope()` and `encode(codec, value)`

Private utility constructor. Encode rejects null logical value, invokes codec
encoding, then rejects null payload or payload above the codec's declared bound.
It allocates header plus payload, writes metadata and bytes, and returns that
array. Payload creation occurs before bound enforcement. Copying into the envelope
creates another full payload-sized allocation; it is not streaming encoding.

No explicit guard validates null codec, positive schema version, nonzero schema
UUID, fingerprint size, or total engine value limit here. Addition of header and
payload length is ordinary integer arithmetic, not checked addition. Custom codec
metadata validation belongs elsewhere; a null schema UUID fails when fields are
written. Codec metadata is queried during encoding, not frozen by this helper.

### `decode(codec, input)`

Rejects null or shorter-than-header input through `invalid`. Reads and validates
magic, envelope version, fixed header length, and both UUID components against the
provided codec. Then reads schema version, flags, payload length, and reserved
field. Requires zero flags/reserved and payload length exactly equal to remaining
bytes, rejecting truncation and trailing bytes.

Allocates a payload array, copies it, and delegates schema-version handling to
`codec.decode`. No independent positivity check is applied to that schema version;
no declared maximum payload-size check or fingerprint comparison is performed
during envelope decode. Exact remaining-length comparison rejects negative length
before allocation. Codec decode errors propagate rather than being converted to
one universal envelope error.

The helper does not forbid a custom codec returning null, retain a borrowed input
view, or verify a payload checksum. Input mutation concurrent with decoding is not
synchronized. Null codec/schema metadata can fail on dereference if reached.

### `invalid()`

Private helper returns `IllegalArgumentException` with `invalid typed value envelope`.
It gives no field-specific detail. Encoding-bound errors and codec-version errors
have their own messages and are not constructed through this helper.

## Review Boundaries

Test schema mismatch, flags, reserved fields, exact payload length, empty strings,
unknown schema versions, malformed UTF-8, and total physical value size. Keep schema
identity, version, fingerprint, and checksum roles distinct. Function-name coverage
detects omissions, not codec compatibility or database write correctness.
