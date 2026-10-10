# Record Codec Generation Functions

[Function index](FUNCTION-INDEX.md) | [Annotations](SCHEMA-ANNOTATION-FUNCTIONS.md) | [Schema resources](SCHEMA-RESOURCE-FUNCTIONS.md) | [Runtime containers](GENERATED-CONTAINER-FUNCTIONS.md)

Source: [AetherRecordProcessor](../../modules/aether-codec-processor/src/main/java/io/aetherdb/codec/processor/AetherRecordProcessor.java).
This page completes its declared-function reference alongside the annotation and
resource pages. It explains code emitted by the current checkout, not arbitrary
future generated output or a claim of exhaustive runtime correctness.

## Generation Architecture

Validation builds FieldModel objects, sorts them by stable wire ID, and computes
descriptor identity/fingerprint and record bound. `writeCodec()` emits Java source
using expression helpers. The compiler subsequently compiles that source; the
processor does not execute its emitted encoders against example values.

At runtime generated codecs delegate byte framing/checksums to CanonicalRecordWriter
and CanonicalRecordReader, scalar element decoding to CanonicalElementCodecs, and
container framing to CanonicalContainerCodec. Provider/resource emission is covered
in the schema-resource reference. There is no runtime reflection over record
components to discover this layout.

## writeCodec: Source and Metadata Methods

`writeCodec(type, packageName, simpleName, schemaId, version, sortedFields,
fingerprint, maximumBytes)` creates `<simpleName>_AetherCodec` through Filer with
the originating type and closes its source writer with try-with-resources.
It emits a public final ValueCodec implementation, private constructor, and public
static INSTANCE. The generated methods are:

| Generated function | Behavior |
| --- | --- |
| `schemaId()` | Returns the static UUID. |
| `currentSchemaVersion()` | Returns the supplied source writer version. |
| `maximumEncodedSize(value)` | Returns a fixed declared maximum without inspecting the value. |
| `fingerprint()` | Copies the embedded descriptor digest array. |
| `encode(value)` | Rejects a null record, creates the bounded writer, writes fields in sorted-ID order, and finishes checksummed AER1 bytes. |
| `decode(schemaVersion, encoded)` | Accepts versions 1 through this codec's current version, initializes component defaults/seen flags, parses all fields, checks required presence, and constructs the record. |

`decode()` does not use a separate historical layout for every accepted version.
It dispatches current field IDs/types and defaults for omissions; metadata/schema
compatibility rules are necessary to make such reads meaningful. A schema version
above current or below 1 raises SCHEMA_VERSION_UNSUPPORTED.

Unknown IDs are skipped after the record reader validates their framing and CRC.
They still incur payload copies; their logical content is not decoded. Recognized
IDs use `decodeCase()` and mark a seen flag only after decoding succeeds. Required
components missing at EOF fail. Constructor arguments follow original record
component order, not wire-ID order, and constructor exceptions propagate.

## Field Statement Helpers

### encodeStatement(field)

Returns Java source for one field:

- ENUM: switch on the logical constant, map to its stable numeric ID, encode using signedLong zigzag, and tag ENUM. This is not ordinal serialization.
- NESTED: call the nested type's named `_AetherCodec.INSTANCE` through CanonicalRecordWriter.nested with the field byte bound.
- Containers: call `containerExpression()` and tag CONTAINER.
- Legacy OPTIONAL_INSTANT: conditionally emit a TEMPORAL field through `ifPresent`.
- Scalars: use FieldType's wire constant and `writerExpression()`.

The optional flag alone does not make all encoders conditional: a scalar marked
optional still emits its field and still encounters its ordinary null handling.
Current resolved Optional types use container presence framing, including an
empty Optional field; they do not use the legacy omit-if-empty Instant path.

### decodeCase(field)

Returns a switch arm that requires the expected wire type, assigns the decoded
component, and sets its seen flag. ENUM decodes signed long then Math.toIntExact,
switches stable IDs to constants, and rejects unknown values with ENUM_VALUE_UNKNOWN.
Overflow remains ArithmeticException from Math.toIntExact.

NESTED delegates to `reader.nestedValue()` with the named nested codec/bound.
Containers call `reader.rawPayload()` and pass that defensive copy into the
container decoder, which copies again. Other fields delegate to `readerExpression()`.
The generator does not consolidate those allocations into a zero-copy reader.

## Container Expression Helpers

### containerExpression(type, value, bound, maximumEntries, encoding)

Parses generic argument text and chooses optional/list/set/map calls. Optional
passes one element codec and byte bound; list/set pass count/byte bounds plus one
element codec; map passes both key/value codecs. Unsupported container names fail.
The encoding boolean is accepted but not used in this helper; Java overload
selection distinguishes logical container inputs from byte-array inputs.

Nested containers inherit the same `bound` and `maximumEntries` recursively.
Counts apply at each container node, not as a global sum across the entire tree;
the enclosing byte bound still applies to its final encoded payload. There are
no independently generated per-level bounds from this signature.

### elementCodec(type, bound, maximumEntries)

Emits an anonymous ElementCodec whose encode/decode methods return the corresponding
expression strings. Generated calls instantiate these anonymous objects at runtime;
this helper does not emit one cached static element-codec instance per field.

### elementEncodeExpression and elementDecodeExpression

Recognized containers recurse through `containerExpression()`. Scalar mapping is:

| Element type | Writer / element decoder |
| --- | --- |
| Boolean, Byte | bool / bool; signedByte / signedByte |
| Short, Integer, Long | signedLong / signedShort, signedInt, signedLong respectively |
| Float, Double, Character | fixed32, fixed64, character on both sides |
| String, byte[] | string or bytes with the inherited bound |
| UUID | uuid |
| Instant, LocalDate, LocalTime, LocalDateTime, Duration | corresponding temporal helper |
| BigInteger, BigDecimal | corresponding arbitrary-precision helper with inherited bound |

Scalar encoding uses CanonicalRecordWriter payload helpers. Decoding uses
CanonicalElementCodecs, including its temporary AER1 record/checksum allocations.
Unsupported element type text throws IllegalArgumentException during generation.
Nested records and custom enums are not in these element switches even though
top-level fields support them.

### isContainerName(type), findByName(fields, name), defaultValue(field)

`isContainerName()` recognizes exact Optional/List/Set/Map generic prefixes.
It is not assignability-based recognition of arbitrary collection implementations.
`findByName()` searches models by component name and throws when absent; it does
not synthesize a missing model or rename it.

`defaultValue()` emits Optional.empty for Optional/legacy Optional-Instant,
null for nonprimitive Java types, and primitive zero/false constants: long 0L,
int/byte/short/char 0, boolean false, float 0.0f, double 0.0d. It does not inspect
the record constructor for domain-specific defaults. An absent optional boxed
scalar/list/set/map can therefore become null, not an empty container/value.
The record constructor remains responsible for accepting or rejecting that default.

## FieldType Construction and Bounds

Three `FieldType` constructors progressively delegate from fixedBound/wireConstant,
to variableBound, to container, storing those four attributes. They do not validate
the declared constants or dynamically calculate encoded lengths.

| Category | Declared payload byte bound |
| --- | --- |
| Boolean, Byte | 1 |
| Short, Character | 3 |
| Integer, Enum | 5 |
| Long, LocalDate, LocalTime | 10 |
| Float, Double, UUID | 4, 8, 16 |
| Instant, LocalDateTime, Duration, legacy Optional-Instant | 20 |
| String, BigInteger, BigDecimal, byte[], containers, nested records | Variable bound resolved during validation |

These are conservative metadata bounds where applicable, not evidence that every
decoder rejects any payload longer than its writer would emit. Runtime validation
still follows the canonical reader/container helpers.

### writerExpression(name, bound)

Builds scalar CanonicalRecordWriter calls using `value.<name>()`: Boolean/Byte,
zigzag Long/Integer/Short, floating-point, char, UUID, temporal, and bounded
string/numeric/byte-array helpers. Container and ENUM/NESTED cases throw because
`encodeStatement()` handles them separately. Legacy Optional-Instant also throws
here because its writer must be conditional. Boxed primitive nulls can fail during
unboxing; there is no explicit generated null marker.

### readerExpression(bound)

Builds current-reader scalar calls, with Math.toIntExact for Integer and the
short-specific range decoder for Short. Variable scalar helpers receive the bound.
Legacy Optional-Instant wraps `reader.instantValue()` in Optional.of.
Container/ENUM/NESTED cases throw because `decodeCase()` supplies those branches.

### FieldType.wireName()

Maps all active types to wire labels: BOOL, SIGNED_BYTE, SIGNED_VARINT,
UNSIGNED_VARINT, FIXED32/FIXED64, STRING_UTF8, UUID128, TEMPORAL, DECIMAL, BYTES,
CONTAINER, ENUM, or NESTED. It is broader than the outer processor's
`wireName(javaType)` used for retired proposal fields. Shared wire tags such as
TEMPORAL do not identify the particular Java temporal type; schema metadata does.

## Examples and Verification Boundary

[GeneratedContainerCoverageTest](../../modules/aether-embedded-typed/src/test/java/io/aetherdb/embedded/typed/GeneratedContainerCoverageTest.java)
contains round-trip and unordered-input canonicalization assertions for Optional,
list, set, map, and nested lists.
[GeneratedSchemaEvolutionTest](../../modules/aether-embedded-typed/src/test/java/io/aetherdb/embedded/typed/GeneratedSchemaEvolutionTest.java)
contains a persisted V1-to-V2 optional-field example and older-writer rejection.
These examples are not exhaustive malformed-input, arbitrary evolution, or
all-supported-type proofs. This documentation change does not rerun those runtime
tests or modify processor implementation.

The three processor references now collectively cover every explicit declaration,
including private helpers and FieldType constructor overloads. Name inventory
checks are omission checks, not proof of generated source correctness for every
possible record shape. Gradle schema tooling and its acceptance workflow still
need separate detailed coverage.
