# Workbench Record Dialog Functions

[Function index](FUNCTION-INDEX.md) | [Workspace](WORKBENCH-WORKSPACE-FUNCTIONS.md) | [Typed values](WORKBENCH-TYPED-VALUE-FUNCTIONS.md)

Source: [RecordDialog.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/RecordDialog.java).
This guide covers **14 explicit declarations in the complete file**, including
two `show` overloads, interface methods and nested editor constructors. Generated
members of `FieldInput` and `RecordInput` records are not explicit declarations.

## Architecture and Ownership

```text
DatabaseWorkspace -> RecordDialog.show
    -> StructuredEditor.create(initial text)
       -> recognized field lines: fixed rows of JTextField
       -> otherwise: TextEditor / free-form JTextArea
    -> modal OK/Cancel -> Optional<RecordInput>
DatabaseWorkspace -> text/typed encoder -> database write
```

The dialog performs no database access and receives no schema descriptor or
original binary envelope. Its input and output are strings. Structured mode is
selected by the initial text's syntax, not by a storage type discriminator.
The workspace remains responsible for encoding, write outcomes, refresh and
error reporting. See the typed-value guide for the later AETV/AER1 validation.

All editor state is local to one invocation. `FieldInput` holds an ID, display
name, type label and mutable Swing text field. The editor copies the list but
not its controls. `RecordInput` is the returned key/value pair. There is no
background worker, transaction, undo history or live storage synchronization.
Callers use the dialog as Swing UI work; these methods do not dispatch to the
event thread themselves.

## Dialog Entry Points

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `RecordDialog.RecordDialog()` | Private constructor prevents utility instantiation. | No initialization or shared state. |
| `RecordDialog.blankStructuredValues(encoded)` | Splits on Java `\\R`, skips blank lines, retains metadata before the first `=`, and chooses a default using the substring after its last `:`. Defaults: `bool=false`, `long=0`, `double=0.0`, other labels empty. Appends one LF per retained field. | A missing/leading `=` or missing/leading final `:` returns an empty string for the entire form. Does not parse IDs, validate types or strip type whitespace. Existing values are discarded, not decoded. Blank input yields empty output. |
| `RecordDialog.show(parent, title, key, value)` | Delegates to the five-argument overload with `keyEditable=true`. | Does not create a second form or additional confirmation. |
| `RecordDialog.show(parent, title, key, value, keyEditable)` | Creates a 36-column key field, selects structured or plain value editor once, lays out labels/controls with GridBagLayout, and opens a modal OK/Cancel confirmation. Returns `Optional<RecordInput>` on OK with a nonempty key. | Cancel/close returns empty. Empty key displays a warning and returns empty rather than reopening. Whitespace-only keys are accepted; neither key nor value is trimmed. `keyEditable=false` disables key editing but preserves the same key check. Numeric/type/schema validation is not performed here. |

Changing the initial syntax changes the chosen widget. Editing free text into
structured syntax does not switch modes while the dialog is open. Conversely,
structured rows expose values only: field IDs, labels and types are fixed until
the dialog closes. Adding/removing fields requires another editing surface, not
this form. The later encoder may reject values accepted by these widgets.

## Plain Editor Contract

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `RecordDialog.ValueEditor.component()` | Interface contract supplies the Swing component inserted into the form. | No database or serialization operation. |
| `RecordDialog.ValueEditor.value()` | Interface contract retrieves the edited value text at acceptance time. | Different editors preserve/normalize text differently. |
| `RecordDialog.TextEditor.TextEditor(value)` | Creates a 10-row, 36-column JTextArea with line wrapping and word wrapping, inside a scroll pane preferred at 520 by 260 pixels. | Wrapping is visual, not a structured-record parser. No field validation or size limit is added. |
| `RecordDialog.TextEditor.component()` | Returns the existing scroll pane. | The same mutable widget, not a copy. |
| `RecordDialog.TextEditor.value()` | Returns the text area's current text directly. | No trimming, escaping, blanking or binary conversion. |

## Structured Editor Functions

| Function | What It Does | Boundaries |
| --- | --- | --- |
| `RecordDialog.StructuredEditor.StructuredEditor(fields)` | Copies the field list and builds one row per field. Displays a name when present, otherwise `Field <id>`, plus the type label. Uses a vertical filler and borderless scroll pane preferred at width 620 and height `min(360, 55 + fieldCount * 42)`. | List membership is immutable, controls remain mutable. Preferred sizes are hints, not fixed bounds. All values use JTextField, including booleans and numbers. |
| `RecordDialog.StructuredEditor.create(encoded)` | Splits on `\\R`, ignores blank lines, parses integer ID before the first `:`, finds `=` after that colon, strips metadata, splits the name/type at the last metadata colon, checks a recognized type, unescapes the value and creates a 38-column JTextField. Returns a structured editor only when every nonblank line succeeds and at least one field exists. | Any malformed ID, delimiter, type or escaped value makes the entire form fall back to plain text. IDs are not checked for positivity, uniqueness or order. Type payloads are not parsed here. Comment lines are not supported by this selector. |
| `RecordDialog.StructuredEditor.isFieldType(type)` | Accepts exact `bool`, `long`, `double`, `string`, `uuid`, `instant`, or any string beginning `wire-`. | Case-sensitive. `wire-` alone and `wire-nonsense` pass this predicate; the later typed encoder can reject them. No descriptor-based type validation. |
| `RecordDialog.StructuredEditor.component()` | Returns the already-built scroll pane. | No rebuild, refresh or validation. |
| `RecordDialog.StructuredEditor.value()` | Serializes fields in their existing row order as `id:[name:]type=escapedValue` followed by LF. Uses `UniversalTypedValue.escapeText` on each current input. | Always emits a trailing LF; IDs/names/types cannot be edited here. Initial whitespace, ID spelling and line endings are normalized. Names/types are emitted without escaping. Does not sort or deduplicate fields. |

## Parsing and Round-Trip Boundaries

A recognized unnamed field is `12:string=hello`; a named one is
`12:title:string=hello`. The last metadata colon separates the type, allowing
other colons inside the name. Delimiters inside values are retained because the
parser finds only the first applicable `=`. Delimiters inside labels are not a
general escaping system and can change parsing or force fallback.

Unescaping accepts the typed utility's escaped backslash, LF and CR syntax.
Invalid escape sequences force plain mode rather than displaying a partially
parsed record. The structured serializer escapes values again; visual text
fields and arbitrary Unicode line separators are not proof of a byte-exact
record round trip. Scalar UTF-8/hex mode selection is owned by the later typed
encoder, not by `StructuredEditor.create`.

Blank-form generation is deliberately narrower than successful saving. UUID,
instant and unknown-wire fields default to empty text, which may be invalid for
their eventual encoders. `blankStructuredValues` also uses unstripped type text,
whereas structured parsing strips it: `long ` does not receive the numeric
default even though the selector subsequently recognizes `long`.

## Tests and Verification Scope

[DatabaseWorkspaceTest](../../modules/aether-workbench/src/test/java/io/aetherdb/workbench/DatabaseWorkspaceTest.java)
tests blank structured defaults alongside workspace typed-value editing. It is
not a modal interaction test: cancellation, keyboard focus, large field forms,
malformed-selector fallback and validation warnings are not covered by opening
the Swing dialog. The documentation inventory checks all explicit declaration
names and the two `show` overloads; it does not establish UI correctness.

The [window/action reference](WORKBENCH-WINDOW-FUNCTIONS.md) covers desktop
assembly separately. The workspace and typed
guides explain the downstream write/encoding paths without conflating their
validation with accepting text in this dialog.
