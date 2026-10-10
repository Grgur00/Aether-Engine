# Schema Locks and Generated Resources

[Function index](FUNCTION-INDEX.md) | [Annotations and validation](SCHEMA-ANNOTATION-FUNCTIONS.md) | [Runtime lookup](GENERATED-CONTAINER-FUNCTIONS.md)

Source: [AetherRecordProcessor](../../modules/aether-codec-processor/src/main/java/io/aetherdb/codec/processor/AetherRecordProcessor.java).
This is the resource/metadata part of its current-checkout reference. Field-expression
generation and the FieldType implementation remain separate work.

## Compile-Time and Runtime Artifacts

| Artifact | Producer/consumer boundary |
| --- | --- |
| Schema directory `index.json` and referenced lock files | Read by `loadLock()` during compilation. |
| `<Record>_AetherCodecProvider.java` | Generated source linking Java class to its codec singleton. |
| `META-INF/aether/schemas/<UUID>/<version>.aesch` | Descriptor written to CLASS_OUTPUT, fingerprinted and later checked by runtime lookup. |
| `META-INF/services/io.aetherdb.codec.generated.GeneratedCodecProvider` | ServiceLoader registration consumed by GeneratedCodecs. |
| `META-INF/aether/generated-codecs.idx` | Additional pipe-delimited generated inventory; GeneratedCodecs loads providers, not this index. |
| Proposal directory `<UUID>.schema.json` and `index.json` | Candidate schema-tool output, not automatically accepted committed locks. |

These are compiler outputs and schema metadata, not engine files or transaction
records. Writing them does not publish a database collection or migrate values.

## Lock Loading

### loadLock(type)

Reads `aether.schemaDirectory`. Missing/blank option, missing regular `index.json`,
or absence of a matching index entry returns null. The matching regular expression
expects ordered properties javaType, schemaId, currentVersion, and lockFile with
particular JSON string/number shapes. Qualified Java type text is regex-quoted.
This is not general JSON parsing: property reordering or escaped strings can fail
to match even when JSON is otherwise valid.

Parses UUID/version from the matched index entry, resolves and normalizes the lock
path, requires it to lexically start with the normalized configured directory, and
requires a regular file. This is not a real-path/symlink containment check. Directory
and index Path construction occurs before the I/O parsing catch block.

Within lock text it recognizes field objects with ordered ID, Java name, wire type,
Java type, requiredness, and maximumEncodedBytes properties. It builds a map by
Java name: duplicate recognized names overwrite earlier entries. Wire type text
is matched but not retained. Requiredness is reduced to a retired boolean only
when equal to `RETIRED`; OPTIONAL/REQUIRED distinctions are not retained in
`LockedField`. Matching can collect fields from both retired fields and versions
without an explicit current-version selection in this method.

The first lowercase 64-hex descriptorSha256 match becomes the stored fingerprint;
absence becomes empty. The first reservedFieldIds array is comma-split and parsed
as integers. There is no general JSON syntax validation, formatVersion validation,
duplicate-ID check, or descriptor-file hashing inside this loader.

Returns `LockedSchema` with copied maps/sets. I/O and caught illegal-argument
failures emit `AETHER_SCHEMA_LOCK_INVALID` and return null; null alone therefore
does not distinguish missing locks from locks that already produced a compiler
error. The validation caller handles identity/version checks separately.

## Reserved and Retired Identity

### removedAndReservedIds(locked, current)

Builds the active current field-ID set, starts with existing reserved IDs, adds
every locked field ID absent from the active set, and returns `Set.copyOf`.
It does not distinguish a rename by name: retaining the stable ID preserves its
active identity. It builds a sorted set internally, but the returned immutable
set does not promise iteration order; serialization sorts reserved IDs again.

### retiredFields(locked, current)

Uses active IDs to retain locked name/field entries whose IDs are no longer active,
then returns `Map.copyOf`. Existing retired records are retained when their IDs
remain inactive. The returned map's iteration order is not guaranteed despite
the intermediate TreeMap; `proposalJson()` does not re-sort that map.

## Provider and Descriptor Emission

### writeProvider(packageName, simpleName)

Creates `<simpleName>_AetherCodecProvider` source through Filer, emits a public class
implementing GeneratedCodecProvider, and emits `recordType()` returning the Java
record class and `codec()` returning `<simpleName>_AetherCodec.INSTANCE`. There is
no generated mutable provider registry or reflective construction. This createSourceFile
call does not supply originating elements. I/O failure propagates to the generation
caller; the writer closes through try-with-resources.

### writeDescriptor(schemaId, version, descriptor, originating)

Creates the UUID/version `.aesch` resource in CLASS_OUTPUT with the originating
type, writes descriptor text, and closes its writer. It does not hash again, sign,
validate compatibility, or merge with an existing resource. Hash input is UTF-8
descriptor bytes in the validation caller; this method uses Filer's text writer.

### writeRegistrationResources()

Sorts generated records by qualified Java name, opens the service file and generated
index, then emits provider names and qualifiedName|UUID|version|providerName|fingerprint
rows. Both writers close via try-with-resources. I/O failures emit
`CODEC_GENERATION_FAILED registration`. Opening/writing two files is not atomic:
one resource may exist or contain a prefix when another operation fails. This
helper does not deduplicate providers or merge previous service-file contents.

## Proposal Emission

### writeSchemaProposals()

Returns unless proposal mode is active and proposals exist. Requires a nonblank
proposal-directory option or emits `AETHER_SCHEMA_PROPOSAL_DIRECTORY_MISSING`.
Normalizes the configured directory, creates it, sorts proposals by Java type,
writes each `<UUID>.schema.json` as UTF-8, then writes an index describing those
proposals. Writes use ordinary `Files.writeString`, not staged rename/force/rollback.

The helper does not merge an earlier proposal index or delete stale proposal files.
Same-UUID proposals target the same filename and can overwrite each other; this
function has no independent identity-conflict check. I/O failure produces
`AETHER_SCHEMA_PROPOSAL_FAILED` with potentially partial output already written.
Schema acceptance is not performed here.

### proposalJson(proposal)

Builds JSON text with formatVersion 1, schema identity/type/version, unknown-field
policy SKIP, sorted reserved IDs, retired fields, one version's active fields,
descriptor fingerprint, and a compatibility label. Active fields inherit sorted-ID
order from validation. Retired map entries use map iteration order, not a fresh sort.
Java names/type text are concatenated without a general JSON escaping routine.

Emitted active fields contain ID/name/wire type/Java type/requiredness/byte bound.
Container maximumEntries and enum/nested detail metadata are not emitted as separate
proposal field properties here, though the descriptor includes them. The proposal
is not a complete runtime descriptor substitute.

`compatibilityFromPrevious` is INITIAL for version 1 and COMPATIBLE otherwise.
That choice does not run a compatibility algorithm or preserve previous version
history. Treat it as generated metadata, not independent evidence that arbitrary
source changes are compatible.

### wireName(javaType)

Used for retired-field proposal entries. Container names become CONTAINER. The
switch maps primitive boolean, long/int, double, String, UUID, and Instant to their
wire labels, with UNKNOWN fallback. This is narrower than supported active FieldType
coverage: several boxed scalars and additional scalar types produce UNKNOWN here.
The Optional-Instant switch branch is preceded by container recognition, so an
ordinary Optional type follows the container branch.

## Descriptor and Fingerprint Helpers

### descriptor(schemaId, version, type, fields)

Constructs newline-delimited AETHER_SCHEMA_DESCRIPTOR_V1 text with UUID/version/
qualified type, payloadFormat 1, and unknownFields SKIP. Emits each field's ID,
name, FieldType name, byte bound, optional flag, and count bound for containers.
Enum details preserve constant-model order and include numeric IDs/names; nested
details include UUID/version. Fields are not sorted inside this helper: the caller
passes an already sorted list. Descriptor fingerprints can change when names or
details change, not only when physical payload bytes change.

### maximumBytes(fields), sha256(bytes), byteLiterals(bytes), hex(bytes)

`maximumBytes()` starts at the 24-byte header and adds 22 plus each field's bound
using `Math.addExact` for the running sum. It is a declared upper bound, not a size
measurement of a particular value. The inner `22 + bound` uses ordinary int
arithmetic, so do not infer complete overflow checking for arbitrary extreme bounds.

`sha256()` returns a new SHA-256 digest and wraps missing-algorithm failure in
IllegalStateException. `byteLiterals()` formats unsigned bytes as comma-separated
`(byte)0xNN` Java source literals. `hex()` uses HexFormat for lowercase hex. These
helpers serialize metadata; they do not establish authenticity or durability.

## Internal Models

| Record | Stored role |
| --- | --- |
| `FieldModel` | Stable ID/name/type, byte/count bounds, optional flag, enum/nested metadata. |
| `ResolvedFieldType` | FieldType plus optional enum/nested resolution result. |
| `EnumModel`, `EnumConstantModel` | Java enum type and constant name/positive persistent ID. |
| `NestedModel` | Nested schema UUID/version. |
| `GeneratedRecord` | Qualified record/provider names, schema UUID/version, fingerprint. |
| `LockedSchema`, `LockedField` | Parsed lock identity/version, fields/reserved IDs/hash, and field ID/type/bound/retired state. |
| `ProposedSchema` | Current proposed fields, retired/reserved identities, and fingerprint. |

These records have implicit constructors/accessors without independent validation
or deep-copying bodies. Their callers establish copied collections where shown;
record declarations alone do not enforce schema invariants.

## Coverage Boundary

This page covers lock loading, retired/reserved IDs, provider/descriptor/registration
resources, proposal serialization, fingerprint/size helpers, and internal models.
Together with the annotation reference it explains validation-to-metadata flow.
Continue with [codec generation](CODEC-GENERATION-FUNCTIONS.md) for `writeCodec`,
field encode/decode/default generation, container-expression helpers, and FieldType
methods. Gradle schema acceptance tooling remains separate documentation work.
