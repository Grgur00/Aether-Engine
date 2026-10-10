# Generated Containers and Codec Lookup

[Function index](FUNCTION-INDEX.md) | [Canonical records](CANONICAL-RECORD-FUNCTIONS.md) | [Collection metadata](COLLECTION-SCHEMA-FUNCTIONS.md)

This reference covers runtime container helpers and generated-codec discovery in
the current checkout. Annotation processor generation is a separate layer.

## Source and Architecture

- [CanonicalContainerCodec](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/CanonicalContainerCodec.java): container framing, ordering, validation, and copies.
- [CanonicalElementCodecs](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/CanonicalElementCodecs.java): scalar decoding through the record reader.
- [GeneratedCodecProvider](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/GeneratedCodecProvider.java): generated registration contract.
- [GeneratedCodecs](../../modules/aether-codec/src/main/java/io/aetherdb/codec/generated/GeneratedCodecs.java): immutable service-loaded registry and descriptor checks.

Generated record codecs select element encoders/decoders and pass explicit count
and byte bounds to the container helpers. Container payloads have no independent
magic, schema identifier, or checksum. An enclosing AER1 record checksums their
bytes; each element codec defines what those bytes mean.

`CanonicalContainerCodec` and `CanonicalElementCodecs` have private constructors
and static operations. `ElementCodec.encode()` accepts one non-null logical item;
`decode()` consumes a complete item payload. Determinism and thread safety are
implementation contracts, not properties these interfaces enforce.

## Optional Functions

### optional(Optional, codec, maximumBytes)

Rejects a null optional or codec. Empty emits the single byte 0 directly, without
checking `maximumBytes`. Present emits byte 1, varint item length, and encoded item,
then checks the completed byte bound. A null encoded result is rejected.
Thus even the empty encoding can succeed with an invalid bound that decoding rejects.

### optional(encoded, codec, maximumBytes)

Copies a bounded non-null input. Presence 0 requires exactly one byte and returns
empty. Presence 1 reads one length-delimited item, requires exact end, delegates
decode, rejects a null result, and returns present. Other presence values fail.
Unlike encoding, the decode overload does not explicitly reject a null codec:
empty input can decode without touching it, while present decoding encounters a
null dereference.

## List Functions

### list(values, maximumEntries, maximumBytes, codec)

Requires non-null list/codec and a nonnegative count within the configured maximum.
Writes the count followed by length-delimited items in iteration order. Rejects
null logical items or null encoded arrays. The final byte check happens after
element encoding and buffer construction, not progressively before allocation.

### list(encoded, maximumEntries, maximumBytes, codec)

Copies bounded input, parses and bounds count, preallocates `ArrayList(count)`,
decodes each copied item, rejects null decoded values, requires no trailing bytes,
and returns `List.copyOf`. The list cannot be structurally mutated; element objects
are not deep-copied or made immutable. The declared count is bounded by configuration,
not by a precheck that the payload contains that many complete items.

## Set Functions

### set(values, maximumEntries, maximumBytes, codec)

Bounds count, encodes all elements, sorts their arrays using unsigned lexicographic
comparison, rejects byte-identical adjacent encodings, and writes count/items.
Ordering follows encoded bytes, not natural Java ordering. Different Java values
that encode identically are rejected even if the input set considers them distinct.
Sorting retains all encoded arrays before the final byte bound is applied.

### set(encoded, maximumEntries, maximumBytes, codec)

Requires strictly increasing encoded items and also rejects duplicates according
to decoded Java equality in a `LinkedHashSet`. Returns an unmodifiable insertion-
ordered set, with iteration order matching canonical encoded order. Unsorted items
and identical byte encodings share the duplicate-canonical-key error text.

## Map Functions

### map(values, maximumEntries, maximumBytes, keyCodec, valueCodec)

Requires non-null inputs/codecs and bounded count. Encodes every key and value into
an `EntryBytes` record, sorts by unsigned key bytes, rejects adjacent identical key
encodings, and writes count followed by key/value item pairs. Value bytes do not
affect ordering. `EntryBytes` retains arrays without cloning; element codecs must
return stable encoded content throughout the operation.

### map(encoded, maximumEntries, maximumBytes, keyCodec, valueCodec)

Copies input, bounds count, requires strictly increasing key bytes, decodes each
key/value pair, rejects null results and duplicate decoded keys via `putIfAbsent`,
and returns an unmodifiable `LinkedHashMap`. Decoded equality and encoded equality
are both relevant rejection boundaries. There is no independent schema check.

Decode overloads for list/set/map do not upfront-check codec arguments; an empty
container can avoid dereferencing null codecs. No operation synchronizes caller
collections or element arrays against concurrent mutation.

## Container Helpers

| Function | Responsibility |
| --- | --- |
| `header(count)` | Creates an output stream and writes count using the record writer's unsigned varint helper. |
| `writeItem(output, item)` | Rejects null bytes, writes length, then copies bytes into the stream. |
| `writeLength(output, length)` | Delegates varint encoding to `CanonicalRecordWriter`. |
| `requireItem(item)` | Rejects null encoded arrays, otherwise returns the same array. |
| `requireDecoded(value)` | Rejects null logical/decoded objects, otherwise returns the same object. |
| `requireCount(count, maximum)` | Rejects negative maximum/count or count exceeding maximum. |
| `bounded(bytes, maximum)` | Requires maximum at least 1 and byte length within it; returns the same array. |
| `boundedCopy(bytes, maximum)` | Rejects null, checks bound, then copies the complete array. |
| `cursor(bytes, maximum)` | Constructs a cursor over that defensive copy at offset zero. |
| `capacity(count)` | Computes collection capacity as at least 1 and ceiling(count / 0.75). It is not an allocation budget. |
| `rejectDuplicates(values, message)` | Compares adjacent sorted arrays for exact equality. |
| `requireIncreasing(previous, current)` | Requires strict unsigned ordering when a previous array exists. |
| `invalid(message)` | Constructs `IllegalArgumentException` with the supplied message. |

`Cursor(bytes, offset)` owns its supplied array reference and mutable offset.
`count(maximum)` parses a varint, rejects values above int maximum, narrows it, then
calls `requireCount`. `item()` parses length, checks upper limit/remaining space,
copies the item, and advances. `requireEnd()` rejects any unconsumed trailing bytes.

`unsigned()` parses at most ten seven-bit groups, rejecting truncation and redundant
zero terminal groups. Like the record parser, it does not constrain the tenth
byte's data bits to 0 or 1. `item()` also lacks an explicit negative-length check
before narrowing the signed-long representation. Do not treat these checks as
exhaustive malformed-varint rejection or promise one exception type for all such
inputs. This reference records the behavior without changing the parser.

## Scalar Element Decoders

`CanonicalElementCodecs` exposes the following payload-to-value functions:

| Functions | Delegated record reader behavior |
| --- | --- |
| `bool`, `signedByte`, `signedShort`, `signedLong` | Corresponding Boolean/Byte/Short/Long payload decoders. |
| `signedInt` | Decodes signed long then `Math.toIntExact`; overflow raises `ArithmeticException`. |
| `fixed32`, `fixed64`, `character` | Float, double, and Java char payload decoders. |
| `string`, `bytes` | Pass the supplied byte maximum to the bounded scalar decoder. |
| `uuid` | UUID payload decoder. |
| `instant`, `localDate`, `localTime`, `localDateTime`, `duration` | Corresponding temporal decoders. |
| `bigInteger`, `bigDecimal` | Pass the supplied maximum to arbitrary-precision decoders. |

Private `read(payload, wireType, decoder)` rejects null payload/decoder, creates a
writer with `Math.addExact(payload.length, 40)`, writes field 16 with the selected
wire type, finishes a checksummed AER1 record, constructs its reader, advances,
checks wire type, calls the scalar decoder, then checks final EOF. This deliberately
reuses record validation but allocates entry/record/copy buffers and calculates CRC
for each scalar element. It is not direct zero-copy scalar decoding. The wire-type
check validates the synthesized framing, not a tag supplied in the original raw
element bytes. Scalar limitations described in the record reference still apply.

## Runtime Provider Lookup

### GeneratedCodecProvider.recordType() and codec()

Return the registered Java class and codec instance. The registry assumes stable
provider results: it invokes these methods repeatedly rather than snapshotting
every result once. The interface alone does not validate annotation presence,
immutability, thread safety, or that the returned class actually is a record.

### GeneratedCodecs.forRecord(recordType)

Rejects null, retrieves the installed codec by exact class key, reports
`CODEC_NOT_AVAILABLE` when absent, and returns it using an unchecked generic cast.
It does not generate a codec at runtime or reflectively derive one from components.

### descriptor(schemaId, version)

Looks up `SchemaVersion(UUID, int)` and returns empty when absent, otherwise a
defensive descriptor copy. It does not validate positivity or perform a fresh
resource/hash check. Only installed providers' current versions are registered.

### load()

Runs once during static class initialization, using `ServiceLoader` providers.
Each descriptor is validated before registration. The same Java class with a
different schema UUID/version is rejected. Distinct classes claiming the same
schema UUID/version are rejected. Same-class providers with the same UUID/version
retain the first codec and descriptor; this branch does not compare their complete
behavior or fingerprints against each other. Provider ordering is not a conflict-
resolution policy applications should rely on.

The resulting `Registry` holds `Map.copyOf` maps of codecs and descriptor arrays.
Maps are immutable, but codec immutability remains a contract; descriptor arrays
are protected by lookup copies. There is no refresh/reload method. Initialization
failure prevents ordinary later use of this class in that class loader.

### validateDescriptor(provider)

Uses the registered class's loader to read
`META-INF/aether/schemas/<schema UUID>/<current version>.aesch`, hashes all bytes
with SHA-256, and requires equality with `codec.fingerprint()`. Missing resources
and mismatched fingerprints fail registration. I/O/digest-availability failures
are wrapped in `IllegalStateException`. Resource loading has no configured byte
bound and does not parse descriptor semantics. A matching hash proves agreement
with the provider fingerprint, not trust in an untrusted provider or schema evolution
compatibility. A null class loader is not specially handled here.

## Remaining Coverage

This page covers the four linked runtime files, including their private helpers
and overloads. Generated annotation-processor code, schema descriptors emitted
during compilation, enum/container type selection, defaults, and required-field
rules still need their own detailed source-backed reference.
