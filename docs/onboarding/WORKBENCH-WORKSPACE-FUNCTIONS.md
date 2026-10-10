# Workbench Workspace and Inspector Functions

[Function index](FUNCTION-INDEX.md) | [Engine](ENGINE-FUNCTIONS.md) | [Typed APIs](TYPED-API-AND-SCHEMAS.md)

This reference covers **38 explicit declarations across four complete files** in
[aether-workbench](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench):
DatabaseWorkspace (21), WorkspaceTableModel (7), RpcFrameInspector (6) and
ReplicationInspector (4). Main [window](WORKBENCH-WINDOW-FUNCTIONS.md) and
[dialog](WORKBENCH-RECORD-DIALOG-FUNCTIONS.md) functions have separate references;
[UniversalTypedValue functions](WORKBENCH-TYPED-VALUE-FUNCTIONS.md) have a companion
reference. Generated record members are excluded.

## Ownership and Data Flow

```text
DatabaseWorkspace -> AetherDatabase scanAll/get/put/delete/write
    -> hidden collection metadata + application physical rows
    -> display keys/values + knownKeys index -> WorkspaceTableModel

Inspector controls -> codec encode -> editable hex
    -> codec decode -> controls + summary / warning dialog
```

Workspace can own or borrow the database. It is an unsynchronized editing session,
not a typed collection adapter, transaction snapshot or paginated query service.
Inspector panels operate on local byte arrays: they do not connect to RPC, append
replicated logs, establish quorum or apply commands to the workspace database.

## Workspace Construction and Writes

Source: [DatabaseWorkspace.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/DatabaseWorkspace.java).

| Function | Behavior | Failure/ownership limits |
| --- | --- | --- |
| `DatabaseWorkspace.DatabaseWorkspace(database)` | Delegates to two-argument constructor with closeDatabase=true. | Owns database lifetime; does not load rows on construction. |
| `DatabaseWorkspace.DatabaseWorkspace(database, closeDatabase)` | Requires nonnull database and stores ownership flag; initializes sorted display-key and collection-UUID maps. | No closed-state guard or synchronization. Null database throws NullPointerException. |
| `DatabaseWorkspace.put(key, value)` | Requires nonnull strings; writes UTF-8 key/value then indexes the key and freshly encoded bytes. | Empty strings allowed. Ignores returned WriteResult. Index may imply success when write returned a rejection/failure outcome. Plain put can overwrite existing data. |
| `DatabaseWorkspace.addTypedEntry(templateKey, userKey, value)` | Requires loaded typed template and AETV-looking current value. Infers logical key encoding, copies typed prefix, rejects duplicate displayed key, reencodes value using original envelope and puts/indexes new entry. Returns rendered key. | Cannot start an empty collection without template. Uniqueness is cached display identity, not atomic physical-key existence. Key type inferred from byte length, not registered metadata. Ignores write outcome; concurrent changes/stale template can invalidate assumptions. |
| `DatabaseWorkspace.edit(originalKey, newKey, value)` | Requires loaded original. Typed key cannot rename; current AETV-looking value reencoded and put. Plain edit uses WriteBatch: delete old text key if renaming, put new UTF-8 key/value; then replaces index entry. | Collision check covers known keys only. Plain branch reconstructs original key from displayed text, not stored physical bytes: binary/UUID-rendered keys are not safely round-tripped. Returned write outcome ignored. Typed edit preserves envelope via helper but does not validate application schema constraints itself. |
| `DatabaseWorkspace.delete(key)` | Removes indexed physical key first; unknown key returns false; otherwise calls database.delete and returns true. | Ignores returned outcome. Thrown failure after removal leaves index out of sync until reload. True means key was known, not independently verified durable deletion. |
| `DatabaseWorkspace.validate(value, name)` | Rejects null string with IllegalArgumentException naming argument. | No blank/size/encoding/control checks; limits delegated to backing APIs. |

Batching plain rename groups its delete/put requests for the backing database, but
workspace does not interpret the returned outcome. This is separate from the
engine's publication and durability contracts. A current display index does not
reserve a key or prevent external writers from changing it.

## Reload, Query and Close Functions

| Function | Behavior | Limits |
| --- | --- | --- |
| `DatabaseWorkspace.rows()` | Clears indexes, scans all entries with closeable cursor, decodes/hides CollectionMetadata and retains other physical rows. Second pass renders keys/values using available descriptors, rebuilds knownKeys and returns immutable row list with physical value length. | Full materialization: no row/byte bound or pagination. List follows cursor order, not TreeMap display order. Display collisions overwrite index entries while both rows remain in list. Failure may leave cleared/partial state. Metadata collected before rendering regardless of physical order. |
| `DatabaseWorkspace.contains(key)` | Exact lookup in loaded display index. | Does not query database; null can fail TreeMap lookup. |
| `DatabaseWorkspace.size()` | Number of distinct loaded display keys. | May differ from rendered row count under collisions; excludes metadata. |
| `DatabaseWorkspace.canEdit(key)` | False if unknown; plain keys true; typed keys call database.get and check AETV magic/header-size heuristic. | Not full typed-envelope validation, application schema validation or proof that a binary plain key can safely rename. Can throw on stale/missing reads. |
| `DatabaseWorkspace.keyEditable(key)` | True only for known non-typed physical keys. | Heuristic classification, not safe binary-key round-trip certification. |
| `DatabaseWorkspace.close()` | Closes owned database, then clears knownKeys; borrowed database stays open. | Does not clear collection map or mark session closed. If database.close throws, knownKeys clearing is skipped. No explicit idempotence enforced here. |

CollectionMetadata.decode and cursor/database methods supply their own validation
and ownership rules. Workspace retains returned arrays in its temporary rows/index;
it does not add independent defensive copies at that boundary. Reload obtains a
cursor view, but later editing performs fresh reads/writes rather than retaining a
transactional snapshot spanning user interaction.

## Rendering and Key Interpretation

| Function | Behavior | Ambiguities |
| --- | --- | --- |
| `DatabaseWorkspace.displayKey(key)` | For >=19 bytes beginning 0x40, reads big-endian collection UUID, skips two-byte key version, renders remaining user bytes under collection/UUID/. Otherwise renders bytes directly. | Version omitted from label. Marker/length test is not full TypedKeyEnvelope decode. Different versions or plain strings can share display labels. |
| `DatabaseWorkspace.isTypedKey(key)` | Tests minimum typed prefix length and first byte 0x40. | Does not validate collection, key version or codec. |
| `DatabaseWorkspace.encodeUserKeyLike(template, userKey)` | Exactly 16 template user-key bytes implies UUID: parse user text and emit two big-endian longs; otherwise requires nonempty text and emits UTF-8. | A 16-byte text/binary key is mistaken for UUID-shaped key. Does not consult CollectionMetadata key codec. UUID parse error wrapped with collection-specific message. |
| `DatabaseWorkspace.metadataForKey(key)` | Typed marker => extracts big-endian UUID and looks up loaded collection metadata; plain key => null. | Ignores key version and descriptor binding beyond collection ID. |
| `DatabaseWorkspace.displayValue(value, descriptor)` | AETV-looking bytes delegated to UniversalTypedValue.display; IllegalArgumentException falls back to generic byte rendering. | Other exception types propagate. A display fallback is not successful corruption verification or editability. |
| `DatabaseWorkspace.displayBytes(bytes)` | Exactly 16 bytes always rendered as UUID. Otherwise strict UTF-8 decode, accepted only if no ISO controls; fallback lowercase hex. | Binary hex has no explicit marker; plain text can look identical. Sixteen-byte ordinary text rendered as UUID unless handled through typed-value helper. |
| `DatabaseWorkspace.Row.group()` | Prefix before final slash; no slash => (root). | Slash grouping is presentation, not collection ownership. Leading/trailing slash can yield empty components. |
| `DatabaseWorkspace.Row.field()` | Suffix after final slash, otherwise full key. | No row constructor validation; null key fails on dereference. |

PhysicalRow is a private key/value array record with generated members only. Row
holds display strings and physical value byte count, not original key bytes or a
version token. No promise of reversible rendering should be inferred from these
labels.

## Swing Table Model

Source: [WorkspaceTableModel.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/WorkspaceTableModel.java).

| Function | Behavior | Bounds/threading |
| --- | --- | --- |
| `WorkspaceTableModel.replaceRows(replacement)` | Immutable list copy then fireTableDataChanged. | Null list/element rejected by copy; caller owns Swing event-dispatch-thread discipline. |
| `WorkspaceTableModel.row(modelIndex)` | Returns row at model index. | Caller must convert sorted/filtered view index first; list bounds enforced. |
| `WorkspaceTableModel.getRowCount()` | Current row-list size. | No database refresh. |
| `WorkspaceTableModel.getColumnCount()` | Four columns. | Fixed Group, Field, Value, Value bytes layout. |
| `WorkspaceTableModel.getColumnName(column)` | Indexes static column names. | Array bounds enforced. |
| `WorkspaceTableModel.getColumnClass(column)` | Integer for column 3, String otherwise. | Does not reject out-of-range columns here. |
| `WorkspaceTableModel.getValueAt(row, column)` | Returns group, field, value or byte count. | Row bounds enforced; invalid column throws IndexOutOfBoundsException. Inherited model is not directly editable. |

## RPC Inspector Functions

Source: [RpcFrameInspector.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/RpcFrameInspector.java).

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `RpcFrameInspector.RpcFrameInspector()` | Builds type REQUEST/RESPONSE, stream/code/timeout spinners, UTF-8 payload and editable wire hex; installs encode/decode/corrupt listeners and summary. | Synchronous Swing actions, no socket. Default stream 1, code 1, timeout 5000 ms. |
| `RpcFrameInspector.titled(title, content)` | Wraps scroll pane in titled BorderLayout panel. | Presentation only. |
| `RpcFrameInspector.encode()` | UTF-8 payload; one BEGIN/END frame, equal fragment/total lengths, zero offset/reserved fields, request-only timeout, random request UUID. Delegates RpcFrameCodecV1.encode, shows lowercase hex/length. | Codec/header enforce validity and size; runtime failure shown as warning. Random UUID means repeated encoding differs. No compression/fragmented streaming or operation-body encoding. |
| `RpcFrameInspector.decode()` | Removes regex whitespace, parses hex and delegates decoder. Sets payload via UTF-8 replacement decoding, type/stream/code/timeout controls and checksum summary. | Binary payload displayed lossily as text; encode after decode reconstructs header defaults/new UUID, not exact original bytes. Partial control updates can precede a later exception. |
| `RpcFrameInspector.corrupt()` | Removes whitespace; if at least one byte of text, changes final pair to 01 when it ends in 00, otherwise 00. Updates summary. | Does not validate hex or recompute CRC. This is a byte-edit demonstration, not fault injection against a running service. |
| `RpcFrameInspector.showError(failure)` | Modal warning dialog using exception message. | No stack trace/structured error receipt. |

See [RPC frame functions](RPC-FRAME-FUNCTIONS.md) for exact codec coverage. The
inspector's summary does not replace those structural checks or prove authenticated
transport. Only REQUEST/RESPONSE are available in its selection control.

## Replication Inspector Functions

Source: [ReplicationInspector.java](../../modules/aether-workbench/src/main/java/io/aetherdb/workbench/ReplicationInspector.java).

| Function | Behavior | Boundaries |
| --- | --- | --- |
| `ReplicationInspector.ReplicationInspector()` | Builds text key/value, previous sequence spinner 0..Long.MAX_VALUE-1, editable command hex and encode/decode buttons. | Local demonstration of one command, not consensus node. |
| `ReplicationInspector.encode()` | Creates closeable one-PUT WriteBatch, plans one sequence from previous value, creates ReplicatedWriteCommandV1 with random UUID, encodes and displays hex/sequence/hash summary. | No database write, log append, force or replication. Runtime failures shown as warning. Same text produces new command identity each encode. |
| `ReplicationInspector.decode()` | Whitespace-stripped hex decode, selects first operation, sets key/value using UTF-8, sets previous sequence to first-1, displays operation count and sequence range. | Does not render all operations or distinguish first-operation kind in controls; malformed text/codec failures caught. Binary bytes can lose fidelity; reencode always produces one PUT, not decoded multi-operation command. |
| `ReplicationInspector.showError(failure)` | Shows exception message in modal warning. | No persistence or error report artifact. |

See [replicated formats](REPLICATION-FORMAT-FUNCTIONS.md) for validation delegated
to command codec and [replication contracts](REPLICATION-CONTRACT-FUNCTIONS.md) for
sequence planning. A hash-valid command is not committed/applied state.

## Tests and Verification Scope

[DatabaseWorkspaceTest](../../modules/aether-workbench/src/test/java/io/aetherdb/workbench/DatabaseWorkspaceTest.java)
contains 12 in-memory tests covering plain edits/rename collision, UTF-8 length,
borrowed ownership, slash labels, typed display/edit/add, record payload edits,
descriptor field names and one dialog blank-form helper. It does not cover
display collisions, ignored write outcomes, binary plain-key renames, external
writers, full-scan limits, close failures or Swing inspector interaction. Codec
tests are separate from testing the inspector controls. A successful headless
Java test run is not a desktop UI screenshot or user-interaction verification.

The [record dialog reference](WORKBENCH-RECORD-DIALOG-FUNCTIONS.md) documents
editor selection, blank defaults and the unvalidated text returned to workspace
encoding. The [typed-value reference](WORKBENCH-TYPED-VALUE-FUNCTIONS.md) covers
the subsequent envelope and record conversion.
