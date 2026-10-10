# Workbench Universal Typed Value Functions

[Function index](FUNCTION-INDEX.md) | [Workspace](WORKBENCH-WORKSPACE-FUNCTIONS.md) | [Canonical records](CANONICAL-RECORD-FUNCTIONS.md)

Source: [UniversalTypedValue.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/UniversalTypedValue.java).
This reference covers **20 explicit declarations in the complete file**, including
both display overloads and nested Envelope helpers. Generated record members are
excluded. The package-private utility operates without application record classes.

## Architecture and Editing Contract

```text
AETV bytes -> Envelope.read -> copied header/payload
    -> AER1 magic? CanonicalRecordReader -> field text
    -> otherwise scalar UTF-8 / hex text

Original envelope + edited text
    -> AER1? new CanonicalRecordWriter from submitted fields
    -> otherwise scalar encoder selected by original payload
    -> Envelope.withPayload -> preserved outer header + updated payload length
```

This is a schema-independent inspection utility, not an application ValueCodec or
schema evolution engine. Record edits replace the whole payload; omitted fields
are removed rather than merged. Outer schema UUID/version stay unchanged even if
field IDs/types change. Descriptor names are display hints, not validation rules.
The backing workspace writes returned bytes; this utility does not access storage.

## Entry and Dispatch Functions

| Function | Behavior | Validation boundary |
| --- | --- | --- |
| `UniversalTypedValue.UniversalTypedValue()` | Private utility constructor. | No instance state. |
| `UniversalTypedValue.isTyped(value)` | True for nonnull value at least AETV header size with first four bytes AETV. | Heuristic only: does not check envelope version, flags, length or schema. |
| `UniversalTypedValue.display(value)` | Calls descriptor overload with empty descriptor bytes. | No names, but same structural parsing. |
| `UniversalTypedValue.display(value, descriptor)` | Reads copied envelope; non-AER1 payload rendered as scalar. AER1 payload read with 64 MiB record bound; emits each field as ID:optional-name:type=escaped-value followed by LF. | Reader validates canonical structure/checksum. Does not validate envelope schema against descriptor or application class. No independent bound on expanded text or descriptor size. Malformed recognized record errors propagate to caller. |
| `UniversalTypedValue.reencode(original, edited)` | Reads original envelope; chooses new record or scalar payload by original AER1 magic; installs replacement under preserved header. | Original record is not fully decoded/validated before rebuilding. No merge, schema-constraint check or descriptor argument. Null edited text fails downstream. |
| `UniversalTypedValue.isAer1(payload)` | Checks >= CanonicalRecordWriter.HEADER_BYTES (24) and AER1 magic. | No format/version/checksum validation at dispatch. Scalar bytes that happen to satisfy this signature are treated as records. |

The 64 MiB constant bounds CanonicalRecordReader/Writer and string field helpers.
It is not a global envelope/scalar/text-input bound. Envelope copying and scalar
UTF-8/hex conversion can allocate large arrays/strings before backing database
limits apply. This helper does not implement streaming edits.

## Record Text Parsing and Names

| Function | Behavior | Limits |
| --- | --- | --- |
| `UniversalTypedValue.encodeRecord(edited)` | Splits using regex line breaks, preserving trailing empty lines; skips blank lines and lines whose stripped-leading text begins hash. Parses first colon and following equals. Field ID stripped and parsed as int; final colon-delimited metadata component is stripped type. Unescapes value, encodes field and appends to new canonical writer, then finish. | Submitted order must have strictly increasing positive IDs via writer; duplicates/out-of-order rejected, not sorted. Names before final type are ignored. Value text is not stripped. All fields can be omitted, producing empty record. Original fields are not retained. |
| `UniversalTypedValue.descriptorFieldNames(descriptor)` | Null/empty returns empty map. Decodes UTF-8 with replacement, requires exact AETHER_SCHEMA_DESCRIPTOR_V1 plus LF prefix; accepts field= lines with exactly four pipe components and parseable ID, stores second component as name. Returns immutable map. | Duplicate IDs last-wins; malformed lines/IDs skipped. Does not verify descriptor fingerprint, schema/version, field types/bounds or positive ID. No trim/control/name-escaping policy. CRLF header fails exact prefix check. |
| `UniversalTypedValue.displayField(reader)` | BOOL => boolean; SIGNED_VARINT => long; FIXED64 => double; STRING_UTF8 => bounded string; UUID128 => UUID; TEMPORAL => Instant; everything else raw-payload lowercase hex. | Wire type alone does not establish application type. All temporal payloads are interpreted by instantValue, not descriptor-specific LocalDate/time/duration. Unknown/container types have no semantic editor here. |
| `UniversalTypedValue.encodeField(type, value)` | bool/long/double/string/uuid/instant use canonical scalar helpers. Other wire-N types parse raw hex. Catches IllegalArgumentException and wraps with type/value text. | Whole supplied value appears in error message. Instant parsing can throw DateTimeParseException outside this IllegalArgumentException wrapper. No descriptor field limits except shared 64 MiB string bound. Unsupported type rejected. |

Text grammar accepts both `16:string=Ada` and `16:name:string=Ada`. Equals after
the first delimiter belongs to the value; colons in metadata before the final
type are discarded as names. Display names are not escaped: names containing
equals or line breaks can make generated text fail to parse as intended.

Canonical writer supplies field ordering, record framing and checksum. Rebuilding
can normalize representations and discard original record flags/extensions; outer
envelope preservation does not imply byte-identical no-op round-trip. Raw wire-N
fields delegate payload structure responsibility to downstream readers.

## Scalar Type Mapping

| Function | Behavior | Limits |
| --- | --- | --- |
| `UniversalTypedValue.parseBoolean(value)` | Accepts exactly lowercase true or false. | No stripping, case folding or numeric aliases. Other text throws IllegalArgumentException. |
| `UniversalTypedValue.typeName(wireType)` | Known six codes map to bool, long, double, string, uuid, instant; others become wire-N. | Descriptive wire mapping, not schema interpretation. |
| `UniversalTypedValue.wireType(type)` | Maps the six case-sensitive names back to constants; otherwise requires wire- prefix and Integer.parseInt suffix. | Does not bound parsed integer to one byte or recognized codes; CanonicalRecordWriter writes its low byte. Malformed suffix wrapped as invalid wire type. |

Double.parseDouble accepts Java floating syntax, including NaN/infinities; any
additional numeric policy belongs to canonical scalar helpers/application codec.
UUID/Instant parsing use their standard parsers. Unknown raw types do not become
validated structured containers merely because a record checksum can be generated.

## Scalar Display and Editing

| Function | Behavior | Round-trip rules |
| --- | --- | --- |
| `UniversalTypedValue.displayScalar(payload)` | Strict UTF-8 decode; permits printable text plus LF/CR/tab, escapes accepted text. Otherwise emits hex: followed by lowercase hex. | Unlike generic workspace display, does not force 16 bytes into UUID form. Text starting hex: remains plain text if original payload is displayable. |
| `UniversalTypedValue.encodeScalar(original, edited)` | If original is displayable UTF-8, unescapes edited text and UTF-8 encodes it. Otherwise requires hex: prefix, strips remaining text and parses hex, wrapping malformed hex. | Original payload decides mode, not edited prefix. Text mode cannot switch to binary merely by writing hex:. Binary mode can produce printable bytes, changing mode on next edit. No scalar size cap. |
| `UniversalTypedValue.isDisplayableUtf8(payload)` | Same strict decoding/control rule as displayScalar; returns false on CharacterCodingException. | No schema-type check. Empty bytes qualify as text. |
| `UniversalTypedValue.escapeText(value)` | Escapes backslash first, then LF and CR. | Tabs stay literal. Other Unicode line separators remain literal; encodeRecord splits on regex line breaks, so such string content need not round-trip through record text. |
| `UniversalTypedValue.unescapeText(value)` | Stateful scanner accepts backslash-n, backslash-r and double backslash; appends unescaped chars directly. | Unknown escape or terminal backslash throws IllegalArgumentException. Does not support backslash-t or Unicode escape syntax. |

For ordinary scalar text, escaping keeps newline/backslash edits reversible. It
does not promise reversibility for all Unicode text embedded in line-oriented
record editing, nor semantic correctness for arbitrary schema IDs.

## Envelope Helpers and Byte Layout

| Function | Behavior | What it trusts |
| --- | --- | --- |
| `UniversalTypedValue.Envelope.read(value)` | Requires isTyped; big-endian version short=1 at offset 4, header length short=40 at 6. Reads flags at 28, payload length at 32, reserved at 36; requires zero flags/reserved, nonnegative exact remaining payload length. Copies 40-byte header and payload into record. | Schema UUID bytes 8..23 and schema version int at 24 are not validated. No maximum total size or outer checksum. Copy ownership prevents mutating original input through returned arrays, but generated record accessors themselves do not clone. |
| `UniversalTypedValue.Envelope.withPayload(replacement)` | Copies header into new header+payload array, updates big-endian length at 32 and copies replacement bytes at header end. | Retains UUID/version/other accepted header fields. Does not revalidate replacement, enforce size bound or use checked addition for array length. Null/overflow/allocation failures propagate. |

Envelope is a private array-bearing record, not an immutable-by-content public
value. In this flow its arrays originate from copies. Generated array equality is
reference-based. AER1 checksum belongs to the inner record reader/writer, not the
AETV envelope helper. See [typed value functions](TYPED-VALUE-FUNCTIONS.md) for the
application codec's separate schema identity and decode contract.

## Verification Scope

[DatabaseWorkspaceTest](../../modules/aether-workbench/src/test/java/io/aetherdb/workbench/DatabaseWorkspaceTest.java)
exercises typed scalar display/edit, 16-byte text handling, template insertion,
generated record editing and descriptor labels through workspace integration.
There is no dedicated UniversalTypedValue test class. Existing tests do not cover
all wire codes, magic collisions, malformed envelope fields, descriptor trust,
unknown escapes, Unicode separators, raw wire-number narrowing, omitted fields,
scalar mode transitions or large-input allocation. Declaration inventory checks
names and overload presence, not semantic round-trip correctness.

See [record dialog functions](WORKBENCH-RECORD-DIALOG-FUNCTIONS.md) for the
separate text-to-widget selector and modal acceptance rules.
