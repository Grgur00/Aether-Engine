# CLI Repair and Salvage Functions

[Function index](FUNCTION-INDEX.md) | [CLI inspection](CLI-INSPECTION-FUNCTIONS.md) | [CLI checkpoint helpers](CLI-CHECKPOINT-FUNCTIONS.md) | [WAL recovery](WAL-FUNCTIONS.md) | [Manifest publication](MANIFEST-VERSION-FUNCTIONS.md)

Source: [AetherCli.java](../../modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java).
This page covers the final **13 explicit declarations**: tail repair, CURRENT
reconstruction, salvage and its conflict/selection/publication helpers, plus usage.
Together with inspection, diagnostics, checkpoint and backup references, the five
CLI pages cover all **85 explicit source declarations**. Matching declaration
rows is not proof of recovery completeness or production safety.

## Three Different Recovery Actions

| Action | Ownership and publication | Important limit |
| --- | --- | --- |
| repair-tail | Exclusive source lock; plans eligible manifest/minimum-WAL suffixes; --yes applies truncation, backup copies enabled unless --no-backup. | Operates in place, sequentially; no all-files rollback. Does not repair arbitrary corruption or every WAL. |
| rebuild-current | Exclusive source lock; validates canonical manifest candidates; --yes writes forced temporary CURRENT, atomically replaces pointer and syncs root. | Chooses highest generation after comparing only the two newest valid terminal versions; not historical consensus among every candidate. |
| salvage | Exclusive source lock; reads metadata despite damaged tables, gathers valid entries/WAL prefixes into memory, creates a new identity and atomically publishes a new directory. | No --yes requirement; output can be incomplete even when structurally valid. It is not exact acknowledged-data recovery. |

Keep original bytes and copy evidence before destructive operations. --yes is
membership-based confirmation, not a persisted plan hash: the next invocation
recomputes the plan from current files. Tail repair and CURRENT reconstruction
reject incomplete/invalid authority where required; salvage explicitly tolerates
some damaged input and reports possible data loss. They must not be substituted
for one another merely to obtain a successful status.

## Function Contracts

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.repairTail(arguments)` | Requires root; detects --yes and --no-backup by membership, validates/locks source, decodes identity and VersionSet.inspect. Plans incomplete manifest tail and only minimum-required WAL suffix via validWalEnd. No repairs returns 0; unconfirmed plan prints and returns 2. Confirmed loop optionally forced-copies each original to timestamp/random pre-repair sibling, truncates/forces each file, syncs root and reinspects manifest/minimum WAL. Prints success/status 0 after lock close; errors can leave earlier repairs applied. |
| `AetherCli.validWalEnd(path, databaseId, segmentNumber)` | Reads full file, requires header block and validates header UUID/number, reassembles fragment payload after header. Computes estimated physical end by starting at header size and applying estimateEndOffset for each recovered group. Does not decode logical mutations or independently locate every physical fragment boundary; corruption handling follows reassemble contract. |
| `AetherCli.rebuildCurrent(arguments)` | Requires root and detects --yes; validates/locks source, decodes identity, scans canonical manifest names and inspects each independently with table validation. Ignores candidate IllegalArgumentException/IOException and requires at least one complete/no-tail candidate. Sorts by generation; highest selected, and if multiple compares only second-highest terminal version. Prints plan/status 2 unless confirmed. Writes forced random CURRENT temporary, atomically replaces existing pointer, syncs root, deletes temporary in finally and inspects published authority. No CURRENT backup in this wrapper. |
| `AetherCli.equivalentTerminalVersion(left, right)` | Requires equal next file number, assigned/persisted sequences, minimum WAL and live file count, then same-position contentEquals for allFiles. Does not compare manifest generation, historical edits, creation time or all older candidates. Assumes normalized inventory order; not an order-insensitive set comparison. |
| `AetherCli.salvage(arguments)` | Requires source/destination; first --mode accepts LATEST_STATE (default) or PRESERVE_VALID_HISTORY, detects include-unreferenced. Validates/locks source; destination absent/outside lexical root with existing parent. inspectMetadata obtains referenced tables without table verification; optional canonical unreferenced tables join candidates. Reads entries, deduplicates exact internal identity, rejects conflicting values, records table failures; gathers every canonical WAL sorted by name using prefix recovery. Sorts recovered entries, optionally selects latest per user key, publishes new directory. Status 2 for recorded skips, otherwise 0. Throwable cleanup targets temporary only; no --yes required. |
| `AetherCli.latestState(sorted)` | From internal-key-sorted entries retains first entry of each adjacent equal user key, returns immutable list. Depends on InternalKey order (user key ascending, sequence descending and type tie ordering), not maximum search over unsorted input. Retains selected tombstones rather than converting them to absence or deleting history in source. |
| `AetherCli.recoverWalForSalvage(path, databaseId, exact, skipped)` | Reads full canonical WAL and parses segment number. Short/invalid header records whole-file skipped range and returns. recoverPrefix returns valid physical records plus issue; logical groups are decoded and merged sequentially with estimated byte offsets. Conflicting exact identities abort. Logical decode corruption/argument failure reports range from current group to EOF and stops this WAL; physical prefix issue reports validEndOffset to EOF after prior groups are retained. I/O failures reading file propagate rather than always becoming skip entries. |
| `AetherCli.decodeWalGroup(encoded)` | Delegates complete logical validation to WalLogicalGroupCodec.decode, creates SSTableEntry per mutation with consecutive sequences starting firstSequence and type 2 for delete/1 for put, returns immutable list. No file writing or cross-group duplicate validation; sequence increments rely on codec range validation. |
| `AetherCli.mergeSalvageEntry(exact, entry)` | Hex-encodes complete internal key, putIfAbsent into identity map; equal identity/equal value deduplicates, different value throws SalvageConflictException. Does not resolve conflicts by source freshness or undo earlier inserts. |
| `AetherCli.publishSalvage(temporary, destination, entries, mode, candidateFiles, skipped)` | Creates temporary directory, new UUID/default identity/options and forced WAL-1 header starting max recovered sequence plus one (addExact). Nonempty entries build one SST-2 at L0, track maximum sequence and create MANIFEST-1 snapshot with assigned/persisted max, minimum WAL 1, next file 3 (2 if empty). Writes forced SALVAGE-REPORT.json, syncs temporary, VersionSet.inspect validates, atomically renames and syncs parent. No rename fallback, external payload copy or engine-read verification. Caller owns cleanup. |
| `AetherCli.writeWalHeader(path, databaseId, firstSequence, creationEpochMillis)` | Constructs WalSegmentHeader with segment 1, previous 0 and supplied identity/sequence/time, writes encoded header block through writeForced. Does not append logical operations; intended new salvage WAL only. |
| `AetherCli.usage()` | Prints static usage examples for version, inspect/verify, checkpoint, restore verification, backup, release, diagnostics, command schema, repair/rebuild and salvage. Does not list config-validate or every accepted backup/admission option. Help is not a generated/complete parser contract; run handles help aliases separately. |
| `AetherCli.SalvageConflictException.SalvageConflictException(message)` | IllegalArgumentException subtype preserving conflict message. Explicit catches rethrow it instead of treating conflicting values as skippable corruption; run maps it to invalid/corrupt status 3. No winner selection or automatic retry. |

## Authority and Mutation Limits

repair-tail initially calls ordinary VersionSet.inspect, which verifies live
SSTables. An unrelated missing/damaged referenced table can therefore block a
tail repair before truncation. Only the manifest suffix and minimum WAL are
eligible here; later WAL segments and corrupt complete records are not broadly
rewritten. Backups are per-file copies made before each truncation, not an atomic
multi-file snapshot or automatic rollback set. --no-backup disables those copies.
Post-repair validWalEnd reassembles without requiring its returned offset to equal
physical file size in an explicit comparison. Follow codec behavior, not the
success message alone, when evaluating a tail policy.

rebuild-current skips invalid candidates and checks the top two valid generations
for equivalent terminal inventory. Older incompatible candidates can remain; the
function does not establish that all generations are equivalent. A sync or final
inspection failure after atomic replacement can leave CURRENT changed despite a
failed command. Temporary deletion in finally can also throw; original CURRENT
is not preserved by this wrapper. No fallback to non-atomic replacement is made.

TailRepair is an implicit record storing path/valid/trailing lengths without an
explicit constructor. SalvageMode is an enum with generated values/valueOf and no
explicit methods. Their generated members are distinct from the 13 declaration
rows. Cross-cutting force/sync/cleanup helpers are in the checkpoint reference;
dispatch/exit mapping is in the inspection reference.

## Salvage Selection and Evidence

Metadata authority still requires valid identity, CURRENT and reconstructable
manifest; salvage does not discover authority from arbitrary bytes when those
are unreadable. Optional include-unreferenced changes the candidate set and can
recover records not in the live manifest; it is a deliberate forensic policy,
not a normal database reopen. All canonical WALs are considered, not only minimum
or newer segments, and the salvage wrapper does not establish cross-segment
sequence continuity or complete acknowledged group history.

Entries inserted before a table-reader failure remain in the identity map; table
failure reporting does not roll back its already traversed prefix. WAL prefixes
also retain earlier valid groups when later bytes fail. The skip list can describe
byte ranges or issues, not only fully skipped files, even though printed text and
skippedFiles use its size as a file count. A valid-prefix salvage can therefore
report a damaged source and still retain some of its records.

Exact duplicate keys with equal bytes deduplicate; conflicting values abort before
publication. There is no conflict winner rule. Sorting then latestState keeps
one internal entry per user key, including tombstone, whereas history mode retains
all distinct valid identities. Both modes preserve recovered sequence values but
allocate a new database UUID and default format options. They do not preserve
source compatibility settings, snapshots, original file numbering or application
artifact-segment data.

Report possibleDataLoss is based solely on recorded skip-list nonemptiness. False
does not prove every acknowledged record survived: unreferenced tables can be
excluded, undetected missing records are not counted, and latest mode deliberately
discards prior versions. Status 0 means no recorded skip issue, not exact source
equivalence. Empty recovery can publish a structurally valid empty database.

Salvage materializes full WALs, table entries and identity maps in memory and builds
one output table, without this CLI's backup-style admission policy. Resource
exhaustion is possible. Atomic rename publishes only the verified temporary layout,
but failure after rename can leave destination present; cleanup targets temporary
and does not undo destination or source changes. Process halt bypasses cleanup.

## Verification Evidence

AetherCliTest source assertions cover unconfirmed tail plans retaining bytes,
confirmed truncation/backup count and verification, rebuilding missing CURRENT
with confirmation, salvage new UUID/latest value/WAL-only recovery/history marker,
and corrupt-table skip/status 2/possibleDataLoss report. These were read, not
executed in this documentation batch. They do not prove all candidate ambiguities,
cross-WAL continuity, arbitrary process/power failures or complete data recovery.

Compiler-tree checks verify this scoped table and the exact combined 85-declaration
inventory across all five CLI references. Whole-codebase semantic/test/generated/
resource coverage still needs its separate audit; no repair/salvage command was
run against a user's data to create this documentation.
