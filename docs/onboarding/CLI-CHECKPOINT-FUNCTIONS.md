# CLI Checkpoint Publication Functions

[Function index](FUNCTION-INDEX.md) | [CLI inspection](CLI-INSPECTION-FUNCTIONS.md) | [Identity and checkpoint metadata](FILESYSTEM-IDENTITY-FUNCTIONS.md) | [Backup restore](BACKUP-RESTORE-FUNCTIONS.md)

Source: [AetherCli.java](../../modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java).
This page covers **nine explicit declarations**: checkpoint creation, crash
context/hook, restore verification and shared copy/write/directory/cleanup helpers.
These are offline embedded database operations, not live multi-owner snapshots or
training-cache segment-file backup. Backup archives and repair/salvage are separate
CLI paths.

## Publication and Ownership

```text
validate lexical source/destination relationship and destination absence
  -> open/close source normally (recovery and flush may change source)
  -> validate source root and destination parent -> acquire source lock
  -> create random temporary sibling -> copy identity/options/live tables; force
  -> create fresh snapshot manifest/CURRENT with minimum WAL zero
  -> fault hook -> write/force checkpoint metadata -> sync temporary directory
  -> verify checkpoint -> atomic rename to destination -> sync parent -> close lock
```

The source is opened before destination-parent validation. A missing source path
can be created by normal engine open, rather than immediately rejected as an
absent checkpoint source. Existing source open/close can recover and flush; this
is not an inspect-only command. The copy lock is acquired after the initial
engine close, so another owner can intervene in that gap. Once locked, recovered
source version must have lastAssignedSequence equal to persistedSequenceWatermark;
the command rejects an unflushed boundary instead of copying its WAL.

Source/destination checks use absolute normalized paths and startsWith, not a
complete resolved-path containment proof. Destination may not equal or be
lexically inside source; parent must exist. Temporary directory is a sibling named
destination.tmp-[random UUID hex], created before the guarded inner copy block.
Underlying root/path validation and directory locks remain distinct safeguards.

Copied DB-IDENTITY and FORMAT-OPTIONS preserve the database UUID and compatibility
fingerprint. Live SSTables retain their file identities; checkpoint CURRENT points
to a newly created snapshot manifest. Source WALs, obsolete tables, auxiliary data,
training-cache segments and runtime snapshots are not copied by this function.
CheckpointMetadataV1 records source and checkpoint manifest numbers, sequence,
table count/bytes and fingerprint; source view generation is supplied the source
manifest number here, not an independently sampled in-memory version identifier.

## Function Contracts

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.checkpoint(arguments)` | Requires exactly command/source/destination. Normalizes paths, rejects equal/nested destination or existing destination, opens/closes source, validates root and existing parent, creates temporary sibling under source lock. Recovers VersionSet, requires assigned/persisted equality and copies all live tables. Chooses checkpoint manifest max(nextFileNumber, sourceManifest + 1), creates snapshot with next number one above it, unchanged sequence watermarks and minimumWal zero. Hits crash hook before metadata; writes metadata, syncs/verifies temporary, atomically renames and syncs parent. Prints success/status 0 only after lock closes. Guarded Throwable path attempts temporary cleanup; rethrows IOException/RuntimeException, wraps other Throwables in IOException. |
| `AetherCli.hitCheckpointAfterCopyBeforeMetadata(destination, temporary, sourceVersion, sourceManifest, checkpointManifest, tableBytes)` | Calls CrashPointRegistry.hit for CHECKPOINT_AFTER_COPY_BEFORE_METADATA with constructed context. Registry controls injection; helper does not halt directly or delete files. Invoked after copied tables and new manifest exist but before CHECKPOINT-METADATA publication. |
| `AetherCli.checkpointCrashContext(destination, temporary, sourceVersion, sourceManifest, checkpointManifest, tableBytes)` | Builds CrashContext map with destination/temporary basenames, unsigned source/checkpoint manifest/sequence/table-byte strings and table count. Does not include full absolute paths or verify file contents. Assumes paths have filenames and version is valid. |
| `AetherCli.restoreVerify(arguments)` | Requires exactly command/checkpoint path. Validates root, acquires database lock, calls verifyCheckpointDirectory, closes lock, then prints independently-restorable message/status 0. Does not write application data, perform an engine reopen, copy into another target or restore backup archive. Acquire failure here is ordinary IOException rather than inspect's lock-specific wrapper. |
| `AetherCli.verifyCheckpointDirectory(checkpoint)` | Decodes checkpoint metadata, identity and format options; requires UUIDs and compatibility fingerprint match. VersionSet.inspect validates manifest/live SSTables. Rejects incomplete tail, nonzero minimum WAL, unequal assigned/persisted versus checkpointSequence, mismatched live count/byte sum or any table largestSequence above boundary. Lists root and rejects canonical WAL names. Does not acquire lock itself, verify every extra file or run cross-table duplicate-identity scan. |
| `AetherCli.copyForced(source, destination)` | Files.copy without replacement option, forces destination channel with metadata and compares source/destination sizes afterward. Size equality is not a byte checksum; later checkpoint verification validates database formats/inventory. No partial-copy cleanup inside helper. Caller owns source stability and temporary lifecycle. |
| `AetherCli.writeForced(path, bytes)` | Opens CREATE_NEW/WRITE, loops until wrapped buffer consumed, then force(true); closes channel. Rejects existing file through I/O. No atomic rename or cleanup itself, no timeout/progress guard if writes repeatedly return zero. |
| `AetherCli.syncDirectory(directory)` | Opens directory READ and force(true), then closes. Assumes platform/filesystem supports directory channel sync; failure propagates. No compatibility fallback or swallowed failure. |
| `AetherCli.cleanupTemporaryDirectory(temporary, failure)` | Returns if path absent; otherwise reverse-sorts Files.walk paths and deleteIfExists each. Adds cleanup IOException as suppressed to original failure. No rollback of already deleted children, retry, secure-root check or special handling of every unchecked/security error. Successful process halt bypasses Java cleanup. |

## Verification and Failure Limits

Checkpoint verification relies on database metadata and live table verification,
not an engine-read pass over every application key. It does not compare metadata's
source/checkpoint manifest provenance fields with the current manifest generation
in this helper. It rejects canonical WAL filenames only, not every arbitrary file
ending in a WAL suffix. Extra unrelated files are not globally rejected, and
duplicate internal identities across tables are not scanned here. A verified
database checkpoint is not automatically a complete application-level backup of
external segment references.

Numbers used for the new manifest and table-byte totals are ordinary addition/sum,
not all checked arithmetic. Subsequent format/constructor checks are separate;
do not claim this wrapper proves safe arithmetic for arbitrary corrupt counters.
The allFiles byte/count assertions are about manifest inventory, not physical
allocation, compression ratio or device write traffic.

There is **no non-atomic rename fallback**. Unsupported atomic move aborts and
attempts temporary cleanup. A failure before rename normally leaves no final
destination, but a hard process halt can leave its temporary sibling. A failure
after rename, including parent sync or lock close, can leave the final checkpoint
present despite a failed command. Cleanup only targets temporary, not destination.
Do not infer destination absence from a nonzero status or blindly retry to an
existing path. Preserve evidence and verify published contents before deciding
what to remove.

The source open/close and copy-lock acquisition occur outside the inner cleanup
block; parent/path/lock/temp-create errors are not all governed by that cleanup.
Throwing fault injection inside the guarded copy path is caught; Runtime.halt or
external process termination does not execute catch/finally. Successful forced
files and directory syncs are filesystem operations, not empirical proof of every
host/power-loss scenario.

## Verification Evidence

AetherCliTest's checkpoint source assertions check creation/status, 256-byte
CHECKPOINT-METADATA, no WAL in produced fixture, restore-verify success, retained
payload and writable/reopenable engine state after first open. The crash-hook test
installs a triggering registry entry, directly calls the helper and checks the
exception identifier and one hit. It does not itself kill a process during real
copy or prove power-loss publication behavior. These tests were read, not executed
in this documentation batch; no source database or checkpoint was created.

Compiler-tree checks match all nine scoped declaration rows. Full CLI and
whole-codebase semantic coverage remain independent audits, not consequences of
this scoped inventory.
