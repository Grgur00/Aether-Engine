# CLI Inspection and Verification Functions

[Function index](FUNCTION-INDEX.md) | [Operations](OPERATIONS-AND-DEBUGGING.md) | [Manifest publication](MANIFEST-VERSION-FUNCTIONS.md) | [SSTable verification](SSTABLE-READ-FUNCTIONS.md)

Source: [AetherCli.java](../../modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java).
This page covers **11 explicit declarations**: entry/dispatch, inspect/verify
implementation, its argument parser and lock exception. It does not inventory
checkpoint, backup, diagnostics export, repair or salvage implementations; those
remaining CLI branches require separate function contracts.

## Offline Ownership and Verification Levels

```text
main -> run -> Arguments.parse -> inspect
  -> validate root -> exclusive lock (unless unsafe forensic override)
  -> readReport -> identity/options -> VersionSet.inspect -> live SSTable verifier
                -> inspectWals -> optional fragment reassembly
                -> FULL only: cross-table internal-key duplicate scan
  -> text or JSON output -> warnings determine status -> release lock
```

inspect defaults to METADATA and verify defaults to FULL. Both accept an explicit
level; the boolean verification selects output mode, **not** validation strength.
The levels do not simply mean progressively reading metadata, checksums and values:

| Level | Actual implementation checks |
| --- | --- |
| METADATA | Identity/options UUID consistency, CURRENT/manifest validation and all referenced SSTable inventory verification through VersionSet.inspect, plus headers of every canonical WAL in the root. Reads each WAL completely even though only its header is decoded. |
| CHECKSUMS | All METADATA work plus physical WAL fragment reassembly/group count. The CLI does not call the logical-group decoder here. |
| FULL | All CHECKSUMS work plus materialized SSTable entries and a global set of encoded internal keys to reject duplicates across live tables. Not a full application-payload or external segment-content check. |

VersionSet.inspect checks referenced SSTable safety/existence/size and invokes
the authoritative SSTableVerifier for each file, regardless of CLI level.
METADATA is therefore not a cheap header-only SSTable path. FULL's duplicate pass
uses ordinary SSTableReader entries, allocating keys/values plus the identity set;
it is not itself streaming or constant-space. A repeated user key at different
sequences/types is not the same encoded internal identity and is allowed by this
particular check. WAL logical identities are not added to the SSTable identity set.

The default lock serializes offline inspection against a running database owner.
--unsafe-no-lock prints a forensic read-only warning and skips lock acquisition;
it does not obtain a coherent snapshot, pause writers or guarantee stable files.
No mutation/repair is performed by the inspect/verify branch itself, but acquiring
the lock can involve lock-file handling. Do not describe it as absolutely zero
filesystem side effects or use the unsafe option as a production ownership bypass.

## Function Contracts

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.AetherCli()` | Private empty constructor for static CLI utility. |
| `AetherCli.main(arguments)` | Calls run and invokes System.exit only for nonzero status. Successful status returns normally. Uncaught runtime failures bypass the explicit status mapping. |
| `AetherCli.run(arguments)` | Empty arguments, help and --help print usage and return 0. Version aliases print hard-coded 0.2.0-dev and epoch 1. Dispatches inspection, checkpoint, backup, release, diagnostics/config/schema and repair/salvage branches. Unknown command prints stderr/usage and returns 64. Maps LockUnavailableException and IOException to stderr/status 4; IllegalArgumentException to stderr/status 3. Does not catch every RuntimeException, Errors or output failure. |
| `AetherCli.inspect(arguments, verification)` | Validates existing root, acquires DatabaseLock unless unsafeNoLock, starts timer after locking, builds report, prints selected output and returns 0 without warnings or 2 with warnings. Lock acquisition I/O is wrapped as LockUnavailableException; root/report errors propagate. Finally closes acquired lock; close failure can replace the report's intended return. Boolean selects reporting mode independently of level. |
| `AetherCli.readReport(root, level, started)` | Decodes DB-IDENTITY and FORMAT-OPTIONS and requires matching UUIDs. Calls VersionSet.inspect, reports incomplete manifest tail as warning, enumerates WALs and computes seven-level file/byte totals plus oldest/newest live table number (zero when absent). Uses addExact for level/total table byte sums. FULL invokes cross-table duplicate scan. Returns elapsed time through report construction and immutable warning copy. Does not truncate incomplete tails or export runtime snapshots. |
| `AetherCli.inspectWals(root, databaseId, minimumWal, level)` | Lists only WAL-[20 digits].aewal names, sorts lexically, parses file numbers and reads entire contents. Requires header block length, decodes header against UUID/file number. Non-METADATA levels reassemble bytes after header and count groups; METADATA groups is -1. Positive minimumWal must appear exactly among discovered file numbers. Returns immutable list; does not enforce gap-free WAL sequence, decode logical operations or filter out older-than-minimum files. |
| `AetherCli.verifyNoDuplicateInternalIdentities(root, databaseId, version)` | Opens every live SSTable against manifest metadata/UUID, traverses entries, hex-encodes each complete internal key and inserts into one HashSet. Duplicate insertion throws IllegalArgumentException. try-with-resources closes readers. Does not test application-value equality, merge winners, populate a repaired store or inspect non-live tables. Memory grows with materialized entries and distinct identities. |
| `AetherCli.printText(report, verification)` | Prints mode/level, UUID/epoch/creator/time/fingerprint, manifest counts, assigned/persisted sequences, minimum WAL, WAL file/byte counts, L0..L6 totals, oldest/newest table, elapsed duration and valid-or-warning health text. Offline snapshot data is unavailable. Inspection heading always displays METADATA even when inspect was given a stronger explicit level; verify heading uses actual report level. Renderer performs no validation. |
| `AetherCli.printJson(report, verification)` | Locale.ROOT formatted JSON object with mode, UUID, epoch, fingerprint, manifest accounting, sequence watermarks, minimum WAL, WAL file count, SSTable bytes, seven level rows, warning count and elapsedNanos. Omits warning strings, verification level, WAL group counts, WAL byte total, creator/time and snapshot state. Uses controlled UUID/hex/canonical manifest values directly, not a general JSON serializer. |
| `AetherCli.Arguments.parse(arguments, defaultLevel)` | Requires command plus database path; path is argument 1. Uses first --level occurrence, uppercases next token with Locale.ROOT and accepts METADATA/CHECKSUMS/FULL enum values. Missing/unknown level rejects. Detects --json/--unsafe-no-lock by membership anywhere in list. Does not reject all unknown options, repeated flags, extra positional values or duplicate levels; this is not a general strict option parser. |
| `AetherCli.LockUnavailableException.LockUnavailableException(message, cause)` | IOException subtype preserving message/cause from lock acquisition. Dispatch gives it a distinct lock-unavailable message but the same status 4 as other I/O errors. No automatic retry or owner eviction. |

## Reports, Failures and Implicit Types

DatabaseReport, WalInfo and Arguments are records. Their implicit constructors,
accessors, equals/hashCode/toString are generated by Java; this section distinguishes
them from explicit function inventory. DatabaseReport has no compact constructor
or defensive array accessor: levelFiles/levelBytes are mutable arrays retained by
the record. The current readReport allocates and privately consumes them, but the
record form alone is not deep immutability. WalInfo.groups is -1 when not decoded.
VerificationLevel enum implicitly supplies values/valueOf and declares no methods.

Elapsed time excludes root validation, lock acquisition, printing and lock release;
it includes manifest/SSTable/WAL checks and FULL duplicate scan. A warning-free
report does not attest to live snapshot state, all acknowledged application records,
segment payload integrity or absence of noncanonical files. Incomplete manifest
tail is the warning generated here; many corruption/I/O failures abort instead of
producing a normal JSON report. JSON warning count alone cannot explain the warning.

Exit 3 covers caught invalid-argument/corruption IllegalArgumentExceptions. Exit 4
covers caught I/O failures including some storage-corruption exceptions; do not
assume every corruption returns 3. Arithmetic overflow in addExact is an unchecked
ArithmeticException outside the mapped catch list. System.out is a PrintStream;
these renderers do not inspect its error state to certify output delivery.

## Verification Evidence

AetherCliTest contains source assertions for current topology/default inspection,
FULL JSON mode/manifest accounting, lock failure status 4 versus unsafe warning,
and incomplete-tail status 2 without truncation. Those assertions were read for
this reference, not executed in this documentation batch. The documentation check
matches the 11 named declarations against the compiler inventory; it does not
claim complete CLI coverage or run an offline command against a user's database.
