# Manifest Publication And Versions

[Persistent internals](PERSISTENT-INTERNALS.md) | [SSTable verification](SSTABLE-READ-FUNCTIONS.md)

[Manifest wire format functions](MANIFEST-CODEC-FUNCTIONS.md) describes the
pointer, header and record codecs used by these publication paths.

This reference describes the local development checkout, including streaming
inventory verification and owner-bound verified additions. Published `main` may
not yet expose all these functions.

## Authority And Lifetimes

`Version` is an immutable inventory and set of recovery counters. It does not
own table readers or file handles. `VersionSet` owns the append-only manifest
writer and current version. `CURRENT` selects a manifest generation; individual
manifest deltas append to that selected file without rewriting `CURRENT`.

Building or forcing an SSTable does not make it live. The normal delta path first
validates the candidate inventory transition and added files, then appends and
forces a complete record before replacing the in-memory version. A crash after
force but before that replacement can recover the new record on restart.
That ambiguity must be handled by the engine; an append exception is not proof
that no durable state changed.

| Path | Table verification | Mutates storage |
|---|---|---|
| `create` | Snapshot additions before publishing `CURRENT`. | Creates/forces manifest and publishes pointer. |
| `recover` | Terminal live inventory before cleanup. | Can repair incomplete tail and delete obsolete tables. |
| `inspect` | Terminal live inventory. | No manifest truncation or table cleanup. |
| `inspectMetadata` | No referenced-table verification. | No manifest truncation or table cleanup. |
| `inspectManifest` | Candidate's terminal inventory. | Does not consult or change `CURRENT`. |
| `logAndApply(delta)` | Only new additions; existing live files are not reverified here. | Appends/forces record, then swaps current version. |

Inspection still reads files and validates the root through shared path-security
code. Its non-mutating guarantee here concerns manifest repair/publication and
table deletion, not an assertion about every external filesystem side effect.

## VersionSet Construction And Initial Publication

Source: [VersionSet.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/VersionSet.java).

### Private `VersionSet(...)`

Stores validated root/identity, selected manifest path, append writer and current
immutable version. Construction itself performs no verification or publication.
The owner must keep the instance and its writer lifetime coordinated with the
engine's database lock; this class does not acquire that lock independently.

### `create(root, databaseId, manifestGeneration, snapshot, creationEpochMillis)`

Requires a snapshot edit numbered one, positive manifest generation and a next-file
counter beyond that generation. It validates the root, derives a managed canonical
manifest path and refuses an existing target or `CURRENT`.

It encodes header and snapshot, exclusively writes a uniquely named temporary
manifest, forces it, atomically moves it to the canonical name and syncs the
directory. It then verifies snapshot additions, publishes `CURRENT`, opens the
manifest append writer and constructs the version set. The temporary path is
deleted in `finally`.

The order matters: the canonical manifest can remain if inventory verification
or pointer publication fails. Cleanup is not a transaction rollback of every
already created file. Header creation time is diagnostic, not a logical commit
sequence. `Version.fromSnapshot` supplies inventory-transition validation.

### `publishCurrent(root, databaseId, generation)`

Writes encoded pointer bytes into a unique `CURRENT.tmp-*` file, forces and closes
it, atomically moves it to `CURRENT`, hits the pointer-before-directory-sync crash
point and syncs the directory. `finally` deletes the temporary path if it remains.
This selects a manifest; it does not append edits or verify DATA content itself.

## Recovery And Inspection

### `recover(root, databaseId)`

Validates root and `CURRENT`, resolves its canonical managed manifest and reads
the entire manifest into memory. It requires a complete header with matching
database identity and generation, then replays complete records starting after
the 4 KiB header region.

Record numbers must be contiguous from one. The first complete record must be a
snapshot; its allocation/sequence diagnostics must match the header. Later edits
must form valid deltas. Invalid version transitions become
`ManifestCorruptionException` with their original cause.

A tail shorter than a physical record header, or a length-valid physical header
declaring more bytes than remain, ends replay as incomplete. The preliminary
header probe checks length only; other fields wait for full decode. A complete header
with an invalid declared length,
checksum mismatch or invalid complete edit fails instead of being silently cut
off. A file without any complete snapshot is rejected.

For an incomplete tail, recovery writes and forces a full backup of original
bytes, hits the repair crash point, truncates the manifest to the complete-record
boundary, forces the repaired file and syncs the directory. It then verifies the
terminal live inventory, removes obsolete tables and opens the append writer.
Repair can already have happened before a later table-verification failure.

### `inspect(root, databaseId)` And `inspectMetadata(root, databaseId)`

`inspect` resolves `CURRENT` and delegates to `inspectResolved` with referenced
table verification enabled. `inspectMetadata` uses the same pointer/replay route
but disables those table reads, supporting explicit forensic workflows where
damaged tables are handled separately. Metadata inspection still requires valid
pointer, header, complete record checksums and version transitions.

Neither truncates an incomplete tail or performs obsolete-table cleanup. They
return terminal complete-record metadata plus tail accounting, not an open writer.

### `inspectManifest(root, databaseId, manifest)`

Requires a filename matching `MANIFEST-` plus twenty decimal digits and `.aeman`,
resolves it beneath the validated root and requires the caller's normalized
absolute path to equal that candidate. It parses the generation and inspects
with table verification enabled, without reading or updating `CURRENT`.
Canonical spelling alone does not make a candidate authoritative.

### `inspectResolved(...)`

Shares the replay rules above: matching header identity/generation, contiguous
records, initial snapshot and valid transitions. It records the consumed boundary,
optionally verifies live files and returns `ManifestInspection`. It leaves trailing
incomplete bytes intact. Whole-file `readAllBytes` and per-record copies make this
a materializing metadata replay, not a streaming manifest reader.

### `backupManifestBeforeRepair(...)` And `repairContext(...)`

`backupManifestBeforeRepair` exclusively writes and forces all original bytes to
a sibling `.repair-backup-*` file, then returns its path. It does not itself sync
the directory or delete the backup. `repairContext` supplies manifest/backup names,
valid end offset and original byte count to crash instrumentation; it makes no
repair decision.

## Delta Publication Functions

### `logAndApply(delta)`

Synchronized on the version set: checks open state, computes `current.apply(delta)`,
verifies the additions and delegates to `appendAndPublish`. Deletions remove files
from the candidate inventory; this method does not delete their physical files.
Verification failure before append leaves the in-memory version unchanged.

### `appendAndPublish(delta, candidate)`

Encodes a physical record, loops writes to the append writer, calls `force(true)`,
hits the manifest-after-append-before-current crash point and assigns `current`.
Returns the new immutable version. It does not rewrite `CURRENT`, trim an uncertain
tail on failure or permanently fence its own writer after an I/O failure. Its
engine owner must handle indeterminate publication and unsafe subsequent reuse.

### `logAndApplyMeasured(delta, timings)`

The synchronized bulk diagnostic path implements the same candidate, verification,
encode, append, force and in-memory install order directly. It fills `candidateNs`,
`inventoryVerificationNs`, `encodeNs`, `writeNs`, `forceNs` and `installNs` as each
stage finishes. A failing stage can leave a partial timings map.

It hits bulk before/after-verification and append/force crash points, increments
the inventory-call trace and surrounds stages with optional coarse JFR events.
The inventory interval includes its adjacent crash instrumentation, not only the
verifier body. The regular `logAndApply` path does not increment that specific
measured-boundary counter. No timing field relaxes verification or durability.

### `VerifiedAdditions`, `verifyAdditions(files)` And Token-Based `logAndApply`

Private `VerifiedAdditions(owner, files)` stores owner identity and an immutable
list of already verified metadata. `verifyAdditions` freezes the supplied list,
verifies its files outside the publication monitor and returns the owner-bound
token. It does not itself call `ensureOpen` or reserve an edit number.

Synchronized `logAndApply(delta, verified)` checks open state, requires the token's
owner to be this exact version set and its list to equal `delta.additions()`, then
validates the current delta transition and appends without another inventory pass.
Files must remain immutable and exclusively owned between verification and use.
The token is not a content hash, file handle or single-use capability.

`ManifestFileMetadata` uses generated record equality for this list comparison:
its array fields compare by identity, not byte content. An independently rebuilt
but content-equivalent list need not match. `contentEquals` is a separate API and
is not called by this token check.

## Inventory, Cleanup And Resource Helpers

### `verifyInventory(root, databaseId, files)`

For each descriptor, derives its canonical SSTable name under a managed path,
requires a regular non-symlink file with exact recorded size, then invokes
`SSTableVerifier.verify`. Every referenced addition gets a full authoritative
streaming pass; merely finding files is not enough. There is no retained decoded
reader cache or protection against external file mutation built into this helper.

### `cleanupObsoleteTables(root, version)`

Builds the live canonical filename set and scans root entries. It deletes canonical
SSTables not in that set and canonical `.aess.tmp-` files with 32 lowercase hex
suffixes. Matching paths must be regular files and not symlinks; unsafe paths fail
cleanup instead of being followed. Other names, including repair backups, are left
alone. A directory sync follows if deletion occurred. Recovery invokes this only
after verifying live inventory.

### `current()`, `databaseId()`, `manifestPath()` And `close()`

Synchronized `current` checks open state and returns the current immutable version.
`databaseId` and `manifestPath` return immutable stored references without an open
check. Synchronized `close` is idempotent after its first invocation: marks closed,
forces the writer and then closes it. There is no `finally` around that force;
if force throws, the following close does not execute and subsequent calls return
because the flag is already set. Resource cleanup callers must account for that
existing failure behavior.

### `sstableName`, `atomicMove`, `syncDirectory`, `writeFully`, `ensureOpen` And `manifestContext`

- `sstableName(fileNumber)` requires a positive number and formats `SST-%020d.aess` with `Locale.ROOT`.
- `atomicMove(source, target)` requests `ATOMIC_MOVE` with no non-atomic fallback.
- `syncDirectory(directory)` opens/forces the directory, suppressing `AccessDeniedException` and `UnsupportedOperationException`. It does not establish identical directory durability on every platform; other I/O errors propagate.
- `writeFully(channel, bytes)` writes until the buffer is consumed; it has no explicit timeout or zero-progress bound.
- `ensureOpen()` rejects a closed version set.
- `manifestContext(edit)` constructs crash attributes for edit number, next file number and sequence/watermark counters; it does not alter the edit.

## Immutable Version Functions

Source: [Version.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/Version.java).

### `Version(...)`, `fromSnapshot(...)` And `apply(delta)`

Private construction stores immutable level lists and counters copied from the
edit. `fromSnapshot` requires snapshot kind and a positive generation, then calls
`applyTo` without a base. It does not itself require edit number one; VersionSet's
initial publication/replay paths enforce that sequencing.

Instance `apply` requires delta kind and calls `applyTo` against this version,
preserving generation. It returns a new object; no current inventory is mutated.

### `applyTo(base, edit, generation)`

Copies live descriptors into a file-number map, applies deletions only when they
name an existing file at the stated level, then rejects additions reusing a live
number. Against a base, it requires the next contiguous edit and nondecreasing
allocation, assigned-sequence, persisted-watermark and minimum-WAL counters.

It rebuilds seven immutable level lists. L0 sorts descending by file number and
may overlap. L1 through L6 sort by smallest user key and undergo non-overlap
validation. This function does not open files, assign new file numbers or verify
that metadata sequence extrema correspond to every stored entry.

### `validateNonOverlapping(level)`

Requires the prior file's largest user key to be strictly less than the next
file's smallest user key. Equal endpoints are rejected, not treated as disjoint.
User-bound accessors decode and copy keys, so sorting/validation is not an
allocation-free range comparison over encoded bound arrays.

### Inventory And Counter Accessors

`files(level)` requires level zero through six and returns its immutable list.
`allFiles()` builds an immutable flattened list in level order. `nextFileNumber()`,
`lastAssignedSequence()`, `persistedSequenceWatermark()`, `minimumWalFileNumber()`,
`manifestGeneration()` and `manifestEditNumber()` return scalar metadata. They
neither reserve counters nor prove the physical state of files at call time.

## Edit And Descriptor Contracts

Sources: [ManifestEdit](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestEdit.java),
[ManifestFileMetadata](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestFileMetadata.java),
[ManifestDeletion](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestDeletion.java)
and [ManifestInspection](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/manifest/ManifestInspection.java).

### `ManifestEdit(...)`

Requires kind, positive edit/allocation counters, nonnegative assigned/watermark
sequences with watermark no greater than assigned, and nonnegative minimum WAL.
Deltas require minimum WAL greater than zero; snapshots may use zero and cannot
contain deletions. Addition/deletion lists become immutable copies.

It rejects duplicate added numbers, additions at or beyond the next-file counter,
duplicate deletions and a number both added and deleted in one edit. It does not
check a deletion against a live version; `Version.applyTo` does. Generated accessors
return immutable lists and scalars. `Kind` distinguishes `SNAPSHOT` from `DELTA`.

### `ManifestFileMetadata(...)` And Key Accessors

Requires positive number/size/counts, level zero through six and ordered positive
sequence bounds. It decodes both internal-key bounds, checks semantic key order
and requires each bound key's sequence within the recorded sequence extrema.
It clones the encoded bounds. This is stricter than `TableFileMetadata`'s basic
field constructor but still not full table verification.

`smallestInternalKey()` and `largestInternalKey()` return clones. `smallestUserKey()`
and `largestUserKey()` decode the stored keys and obtain copied user portions.
`contentEquals(other)` explicitly compares every scalar and key-array content,
returning false for null. It does not override generated `equals` or `hashCode`.

### `ManifestDeletion(...)` And `ManifestInspection(...)`

Deletion construction validates positive number and level zero through six;
the generated accessors identify a removal request, not evidence it was applied.
Inspection construction requires path/header/version, positive complete-record
count, at least a header region of physical bytes and a nonnegative tail no larger
than total bytes. It does not independently replay or verify those observations.
Generated accessors expose immutable references and scalar accounting.

## Test Starting Point

[ManifestFormatV1Test](../../modules/aether-sstable/src/test/java/io/aetherdb/sstable/manifest/ManifestFormatV1Test.java)
is the format/publication starting point. Crash hooks describe instrumented
boundaries, not proof that every failure mode was exercised. The documentation
function-name inventory catches omissions but does not certify recovery semantics.
