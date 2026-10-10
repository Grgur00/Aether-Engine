# Schema Annotations and Validation

[Function index](FUNCTION-INDEX.md) | [Generated runtime codecs](GENERATED-CONTAINER-FUNCTIONS.md) | [Typed adapter](TYPED-ADAPTER-FUNCTIONS.md)

This page covers all six annotation contracts and the processor's initial
validation/type-resolution path in the current checkout. It is the first part of
the processor reference, not a claim that every generation helper is documented.

## Annotation Contracts

Sources: [annotation package](../../modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation/AetherRecord.java),
[AetherField](../../modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation/AetherField.java),
[AetherMaxLength](../../modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation/AetherMaxLength.java),
[AetherMaxEntries](../../modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation/AetherMaxEntries.java),
[AetherEnum](../../modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation/AetherEnum.java),
[AetherEnumValue](../../modules/aether-codec-annotations/src/main/java/io/aetherdb/codec/annotation/AetherEnumValue.java).

All use CLASS retention: they are available to compilation/class-file consumers,
not ordinary runtime reflection through RUNTIME-retained annotations. Annotation
members declare values; range checks happen in consumers, not in the annotation.

### AetherRecord.schemaId() and version()

TYPE-targeted `AetherRecord` declares a schema family. `schemaId()` defaults to
empty, allowing lock/proposal identity resolution. `version()` is required and
must be positive when processed. The processor requires a public Java record even
though the annotation target itself permits other type declarations.

### AetherField.id(), previousName(), optional(), nullable()

RECORD_COMPONENT-targeted `AetherField` defaults to ID 0, previous name empty,
optional false, and nullable false. ID 0 requests assignment/resolution rather
than durable wire ID zero. `previousName()` explicitly maps a renamed component
to a locked field. `optional()` permits absence from encoded payloads; it is not
an explicit null marker.

`nullable()` is declared but is not read by `AetherRecordProcessor` in this checkout.
Setting it does not generate explicit-null framing or bypass scalar/container
null rejection. Do not treat this annotation member as implemented persistence
support for null values.

### AetherMaxLength.value() and AetherMaxEntries.value()

Both target record components and require an explicit integer argument.
`AetherMaxLength.value()` supplies the variable field's encoded byte bound. Despite
its Javadoc mentioning UTF-8/element counts, the processor uses it as `FieldModel.bound`,
including full encoded container payload bytes, not the container element count.
`AetherMaxEntries.value()` supplies container count separately; Optional uses 1
regardless of that annotation. Fixed-width fields do not use a variable byte bound.

### AetherEnum and AetherEnumValue.value()

TYPE-targeted `AetherEnum` is a marker with no members. FIELD-targeted
`AetherEnumValue.value()` requires a stable integer identity; the processor requires
positive unique IDs on each enum constant. IDs are not ordinal positions. Marking
a type alone does not generate a standalone provider in the main processing loop.

## Processor Entry Points

Source: [AetherRecordProcessor](../../modules/aether-codec-processor/src/main/java/io/aetherdb/codec/processor/AetherRecordProcessor.java).

`AetherRecordProcessor()` creates processor state: generated-record/proposal lists
and a resources-written flag. `getSupportedSourceVersion()` returns RELEASE_21.
The processor declares support for all annotation names and three options:
`aether.schemaMode`, `aether.schemaDirectory`, and `aether.schemaProposalDirectory`.

`process(annotations, roundEnvironment)` visits elements annotated with AetherRecord
and invokes `validateAndGenerate()` on type elements. On the final processing round,
if there are generated records and resources were not yet written, it sets the flag
and writes registration resources and proposals. It returns true, claiming the
annotations presented to it; its wildcard support is broader than the record
annotation it explicitly visits. It does not retry aggregate resource emission
after setting the flag.

`isProposalMode()` recognizes PROPOSE case-insensitively. Missing mode defaults to
VERIFY; other strings also take the non-proposal path rather than receiving an
unknown-mode diagnostic.

## validateAndGenerate: Identity and Fields

### Record Identity

Requires a public record, loads any schema lock, and resolves UUID from explicit
schemaId, an existing lock, or (only in proposal mode without a lock) name-based
UUID over UTF-8 `aether-schema:<qualified Java name>`. Rejects zero/malformed UUID
and nonpositive source version. An empty implicit identity without a lock in
verification mode reports `AETHER_SCHEMA_LOCK_MISSING`.

When a lock exists in verification mode, UUID and version must exactly match it.
Explicit IDs can follow a no-lock path; missing lock is not universally rejected
for every annotated record. Lock parsing and its error behavior are separate work.

### Field Identity and Renames

Builds unavailable IDs from reserved IDs and all locked fields, then visits record
components in source order. Matching names reuse locked identity. If a new name
declares `previousName`, that locked name must exist and marks schema change.
A new implicit-ID field without that mapping can be rejected as an ambiguous
rename when absent, nonretired locked names share its Java type.

ID 0 reuses a matched lock ID, otherwise requires proposal mode and allocates the
first free ID via `nextFieldId()`. That helper scans 16..536,870,911 and throws if
exhausted. Explicit IDs cannot change a matched locked ID or reuse an unavailable
ID for an unmatched field. All IDs must lie in that range and be unique in the
current record. Explicit field IDs are stable identities, not source positions.

### Bounds and Requiredness

`resolveType()` must recognize the component type. Variable byte bounds choose a
positive AetherMaxLength, otherwise positive locked bound, otherwise 1 MiB for a
container, otherwise 65,536 in proposal mode. No such fallback for another variable
field in verify mode reports `FIELD_BOUND_REQUIRED`. A nonpositive MaxLength is
not universally rejected directly: it can fall through to a lock/default bound.

Optional gets count 1; other types get MaxEntries or default 1024. Containers reject
counts below 1. In verify mode a matched lock's Java type and byte bound must match;
in propose mode those changes mark schema change. The model marks Optional (and
the legacy OPTIONAL_INSTANT type) optional automatically, or uses AetherField.optional.
It does not inspect AetherField.nullable.

### Generation Handoff

In proposal mode, removal of locked IDs also marks schema change. A changed locked
schema requires source version greater than the locked version. This test alone
is not the complete compatibility policy for arbitrary edits.

Fields are sorted by stable ID for wire output. The processor builds descriptor
text and SHA-256 fingerprint; verify mode compares any nonempty locked descriptor
hash. It calculates a maximum record size, writes codec/provider/descriptor,
records generated registration metadata, and records a proposal when requested.
I/O failure emits `CODEC_GENERATION_FAILED`; diagnostics and generated outputs are
not an atomic filesystem transaction. Helper generation details remain separate.

## Type-Resolution Functions

`resolveType(type)` recognizes primitives and boxed scalar equivalents, strings,
UUID, supported Java time values, BigInteger/BigDecimal, byte arrays, and supported
Optional/List/Set/Map parameterizations. `resolved(FieldType)` packages an ordinary
type without enum/nested metadata.

For a nested AetherRecord, it resolves explicit UUID or a lock UUID when blank,
and carries the annotation version. This branch does not duplicate every public-
record/positive-version/zero-UUID check of top-level validation. A missing nested
lock reports `NESTED_SCHEMA_LOCK_MISSING` rather than inventing an implicit nested
identity in proposal mode.

For an AetherEnum type, it scans enum constants, requires positive unique annotated
IDs, and rejects an empty constant model. `supportedElementType()` does not include
these custom enums or nested records: scalar/nested top-level-field support does
not imply the same support inside containers.

`supportedContainerType(typeString)` uses `genericArguments()` and expects two
arguments for Map, one for the other supported containers, recursively checking
each through `supportedElementType()`. This supports nested containers of the
listed scalar types, not arbitrary collection implementations or wildcard types.
`genericArguments()` splits compiler type text at commas outside nested angle
brackets and strips whitespace; it is not an independent Java type parser.

## Generated Runtime Contract

The inspected `writeCodec()` path emits a ValueCodec singleton with schema UUID,
current version, fixed maximum, defensive fingerprint copies, encode, and decode.
Encode writes sorted fields through CanonicalRecordWriter. Decode accepts positive
versions no newer than the codec, loops through all fields with CanonicalRecordReader,
dispatches recognized IDs, skips unknown fields after framing validation, checks
seen flags for nonoptional components, and invokes the record constructor in source
component order. Constructor invariants may therefore reject decoded records too.

Optional defaults and individual field-expression generation need their own helper
reference before claiming exhaustive processor coverage. Skipping unknown fields
does not avoid the reader's payload copies or prove semantic validity of unknown
wire types.

## Coverage and Next Work

All annotation members are covered here, together with processor entry points,
`validateAndGenerate`, `isProposalMode`, `nextFieldId`, `resolveType`, `resolved`,
`supportedContainerType`, `supportedElementType`, and `genericArguments`.
Continue with [schema resources](SCHEMA-RESOURCE-FUNCTIONS.md) for lock parsing,
reserved/retired-field models, descriptors, proposals, and registration resources.
The [codec generation reference](CODEC-GENERATION-FUNCTIONS.md) covers field
expressions, defaults, FieldType helpers, and emitted methods. These three pages
collectively cover the processor declarations; Gradle schema tooling remains separate.
