# Canonical Record Functions

[Function index](FUNCTION-INDEX.md) | [Typed values](TYPED-VALUE-FUNCTIONS.md) | [Schemas](TYPED-API-AND-SCHEMAS.md)

This reference describes the current checkout's generated-codec record framing,
scalar payload helpers, copies, and validation boundaries. It does not describe
every generated codec or container codec, and it does not change runtime behavior.

## Ownership and Format

Sources: [CanonicalRecordWriter](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/CanonicalRecordWriter.java),
[CanonicalRecordReader](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/CanonicalRecordReader.java),
and [WireType](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/WireType.java).

A generated value codec assembles an AER1 payload using these helpers. That payload
is distinct from the outer typed-value envelope, an SSTable entry, and a native
memtable record. Their checksums and size bounds are separate boundaries.

| Bytes | Meaning |
| --- | --- |
| 0..3 | `AER1` magic |
| 4..7 | Little-endian format version 1 and header length 24, two shorts |
| 8..19 | Little-endian field count, complete record length, canonical-order marker 2 |
| 20..23 | Little-endian masked CRC32C |
| 24 onward | Fields: unsigned-varint ID, one-byte wire type, zero flags, unsigned-varint payload length, payload |

This is a materializing API: the writer keeps encoded field arrays until completion;
the reader copies the complete record and then copies each visited field. Skipping
an unknown field at the generated-codec layer still invokes `next()` and allocates
that field's payload. It is not the SSTable streaming verifier.

## Writer Lifecycle

### CanonicalRecordWriter(maximumBytes)

Requires a bound of at least `HEADER_BYTES` (24), stores it, and starts with zero
fields and a 24-byte total. It allocates field buffers as fields are appended;
the bound is not a pre-reserved memory budget or a concurrency guarantee.

### field(fieldId, wireType, payload)

Requires an ID strictly greater than the previous ID (initially zero) and a non-null
payload. Encodes framing and payload into a `ByteArrayOutputStream`, copies that
buffer into an entry array, adds its length with `Math.addExact`, checks the record
bound, and only then records the entry and previous ID.

The writer does not enforce the reader's maximum ID of 536,870,911, validate the
wire-type code, or validate the payload's interpretation. `write(int)` emits only
the low byte of `wireType`. Generated callers must supply valid field definitions.

A size-bound failure occurs after `totalBytes` has changed but before the entry is
added. Discard the writer after that failure: calling `finish()` can produce an
oversized record with trailing zero bytes, not a rollback to the last valid state.

### finish()

Allocates the complete record, writes header and stored entries, calculates the
checksum, writes it at byte 20, and returns the new array. There is no finished
latch: repeated calls create fresh arrays, and subsequent `field()` calls remain
possible. No storage write, schema compatibility check, or manifest publication
occurs here.

## Reader Lifecycle

### CanonicalRecordReader(bytes, maximumBytes)

Rejects null, shorter-than-header, and over-bound inputs. Copies the input into
private storage, then validates magic, version, header length, nonnegative field
count, exact declared total, order marker, and checksum. Header/checksum validation
uses the caller's original array, not the private copy: callers must not mutate
the input concurrently with construction.

Construction does not parse every field. A valid checksum is not evidence that
field framing, ordering, or scalar interpretation has been fully validated.

### next()

Reads an ID, requires increasing IDs in 1..536,870,911, reads wire type and zero
flags, parses length, checks remaining bytes, copies the payload, and advances the
field count. When the declared count is exhausted, requires exact end-of-record
before returning false. Consumers must iterate through that final check to detect
trailing bytes. Unknown wire codes are retained rather than rejected here.

There is no reset or invalid-after-failure state. A parsing failure can leave cursor
state advanced; discard the reader. A successful EOF does not clear the last field.

### fieldId(), wireType(), rawPayload(), requireWireType(expected)

`fieldId()` and `wireType()` expose current metadata (zero before the first field).
`rawPayload()` returns another defensive copy; before the first field it encounters
a null payload. `requireWireType()` compares the current code and throws on mismatch.
Scalar decode helpers do not call it automatically. Use these operations only
after a successful `next()`; after EOF they still refer to the previous field.

## Scalar Payload Functions

Writer helpers are static and return new payload arrays. Reader helpers interpret
the current copied payload; they do not decide schema defaults or required fields.

| Writer | Reader | Representation and checks |
| --- | --- | --- |
| `bool` | `boolValue` | One byte; reader accepts only 0 or 1 with exact length. |
| `signedLong` | `signedLongValue` | Zigzag mapping followed by unsigned varint; reader requires payload consumption. |
| `signedByte` | `signedByteValue` | One exact signed byte, no zigzag. |
| `signedLong` for short values | `signedShortValue` | Same zigzag representation; reader additionally checks the short range. |
| `character` | `characterValue` | Unsigned Java UTF-16 code unit; surrogate code units are representable, not Unicode scalar validation. Reader checks the upper limit only. |
| `fixed32` | `fixed32Value` | Four little-endian IEEE-754 bytes. Writer canonicalizes NaN bits; reader accepts any correctly sized bit pattern. |
| `fixed64` | `fixed64Value` | Eight little-endian IEEE-754 bytes with the same NaN asymmetry; signed zero is retained. |
| `string` | `stringValue` | UTF-8 under a caller-supplied byte bound. Writer allocates before checking and replaces unpaired surrogates; reader reports malformed UTF-8 rather than replacing it. |
| `uuid` | `uuidValue` | Two big-endian longs, exact 16-byte reader check; zero UUID is not rejected. |
| `bytes` | `bytesValue` | Bounds then defensive copy; empty arrays are valid when the bound permits. |

Object-valued writer helpers reject null. Bounds are byte-length checks, not
normalization, identity, or checksum checks on the field's logical content.

## Temporal Functions

| Writer | Reader | Components |
| --- | --- | --- |
| `instant` | `instantValue` | Zigzag epoch seconds, unsigned nanoseconds. |
| `localDate` | `localDateValue` | Zigzag epoch day. |
| `localTime` | `localTimeValue` | Unsigned nanoseconds of day. |
| `localDateTime` | `localDateTimeValue` | Zigzag epoch day followed by unsigned nanoseconds of day. |
| `duration` | `durationValue` | Zigzag seconds followed by normalized Java duration nanoseconds. |

Two-component readers require complete payload consumption. Instant/duration
readers reject nanos above 999,999,999; local-time readers reject values at or above
86,400,000,000,000. Java time factories also enforce their supported date/time
ranges and may throw their own exceptions. These helpers do not universally wrap
those exceptions in `IllegalArgumentException`.

Unsigned parsing returns a signed Java `long` bit pattern. These temporal upper
checks do not themselves reject negative values from malformed unsigned encodings;
Java factories may reject or normalize those values depending on the type.

## Arbitrary Precision and Nested Values

### bigInteger / bigIntegerValue

The writer uses minimal big-endian two's-complement `toByteArray()` and checks the
bound after allocation. The reader requires a nonempty bounded payload, constructs
`BigInteger`, and compares its re-encoded bytes to reject redundant sign extension.

### bigDecimal / bigDecimalValue

The writer emits zigzag scale followed by the minimal unscaled integer, then checks
the complete payload bound. The reader requires an int-range scale and a remaining
integer, copies that suffix, and verifies minimal two's-complement encoding. Scale
is preserved: this is not `stripTrailingZeros()` normalization, so numerically equal
values with different scales retain distinct representations.

### nested / nestedValue

`nested()` calls the supplied `ValueCodec.encode`, adds a 24-byte big-endian header
(schema UUID, writer version, payload length), checks both the field bound and
`maximumEncodedSize(value)`, and copies the payload into the wrapper. It does not
independently validate the codec's UUID or positive version; a null encode result
fails when its length is accessed.

`nestedValue()` requires a codec and a field length between 24 and the supplied
bound, checks schema UUID equality, positive stored version, and exact remaining
payload length, then copies nested bytes and delegates to `codec.decode(version,
nested)`. It does not compare fingerprints or apply `maximumEncodedSize`; nested
compatibility and payload validation belong to the delegated codec.

## Framing Helpers and Wire Codes

`maskedCrc32c()` checks bytes 0..19, four zero bytes in place of the CRC field, and
bytes 24 onward, then rotates right by 15 and adds `0xa282ead8`. It protects the
record bytes, not schema identity in an outer envelope, and is not authentication.

`writeUnsignedVarint()` emits seven-bit groups with logical right shifts, including
all 64 bits of negative Java long bit patterns. Reader `unsignedVarint()` overloads
parse either the record cursor or a supplied array/position; `payloadUnsignedVarint()`
overloads either enforce single-value full consumption or advance a shared position
for composite payloads. `requireRemaining()` rejects negative lengths and truncated
fields. `invalid()` creates an `IllegalArgumentException` carrying the supplied text.

The varint parser rejects truncation, continuation past ten bytes, and a redundant
zero final group, but does not check that the tenth byte's data bits are at most 1.
Overflowing high bits can be discarded by Java shifts. Do not infer exhaustive
canonical 64-bit validation from the class description or checksum alone.

`WireType` is a constants-only class with a private constructor: BOOL=1,
SIGNED_VARINT=2, UNSIGNED_VARINT=3, FIXED32=4, FIXED64=5, BYTES=6, STRING_UTF8=7,
UUID128=8, DECIMAL=9, TEMPORAL=10, SIGNED_BYTE=11, CONTAINER=12, ENUM=13, NESTED=14.
These identifiers are not validators; generated code selects the appropriate
payload decoder and calls `requireWireType()` for recognized fields.

## Coverage Boundary

This page covers every explicit constructor, method, and private parsing/checksum
helper in the three linked files. Name-inventory tests detect missing names, not
incorrect overload explanations. Container determinism, generated provider lookup,
annotation processing, required/default fields, and schema evolution at the generated
codec layer remain separate documentation work.
