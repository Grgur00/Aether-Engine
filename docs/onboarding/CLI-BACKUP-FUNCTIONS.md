# CLI Backup and Restore Functions

[Function index](FUNCTION-INDEX.md) | [CLI checkpoint](CLI-CHECKPOINT-FUNCTIONS.md) | [Archive format](BACKUP-ARCHIVE-FUNCTIONS.md) | [Restore writer](BACKUP-RESTORE-FUNCTIONS.md) | [Crypto](CRYPTO-FUNCTIONS.md)

Source: [AetherCli.java](../../modules/aether-tools/src/main/java/io/aetherdb/tools/AetherCli.java).
This reference covers **32 explicit declarations** in the backup command paths,
renderers, option/key/report types and local encryption/hash helpers. Archive
format, AEAD envelope and restore-writer contracts are separate linked references.
This CLI orchestrates embedded database checkpoints; it is not a live distributed
snapshot or complete backup of application-owned external artifact segments.

## Four Command Boundaries

| Command | Ordered work | Success/failure evidence |
| --- | --- | --- |
| backup-create | Validate checkpoint/archive options; lock and verify checkpoint; materialize object bytes; unlock; optional encryption; admission; CREATE_NEW archive write; archive reread. | Status 0 after format reread; admission rejection reports status 2 before archive write. No temp-rename or archive fsync in wrapper. |
| backup-restore-preflight | Parse options; read archive; run preflight on supplied key-epoch availability and target/options; render. | Status 0 or 2 without writing target. Availability declaration is not successful decryption with actual key material. |
| backup-restore | Parse/read; decrypt; admission on plaintext contents; delegate checkpoint-layout writer; render. | Status 0 after writer returns; no extra CLI checkpoint verification pass. Exceptions use dispatcher error mapping. |
| backup-restore-drill | Parse/read; preflight encrypted contents; decrypt; admission; writer; lock restored target; checkpoint verification; report. | Preflight/admission or caught post-restore verification failures return 2. Read/decrypt/writer errors can abort without a drill report. |

Admission uses manifest bytes/object count after content construction/decryption,
not a streaming memory reservation. Create may allocate both plaintext/encrypted
objects before rejection; restore reads/decrypts before rejection. A successful
admission does not prove bounded process memory or device free space. All current
command calls pass draining=false; the helper can accept a different value.

## Command and Content Functions

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.backupCreate(arguments)` | Requires checkpoint/archive paths; validates existing root and absent normalized archive; parses admission and optional encryption. Locks checkpoint while checkpointBackupContents verifies/reads, then unlocks. Encrypts if requested, rejects admission with text/JSON status 2, requires existing archive parent, writes CREATE_NEW archive and rereads it for format validity. Prints UUID/object/byte receipt and returns 0. No automatic partial-archive removal, force or atomic rename. |
| `AetherCli.checkpointBackupContents(checkpoint)` | Verifies WAL-free checkpoint then decodes identity/options/metadata and inspects manifest again. Reads DB-IDENTITY, FORMAT-OPTIONS, CHECKPOINT-METADATA, CURRENT, current manifest and all live SSTables under objects/ names. Builds per-object kind/length/SHA-256/format-1 descriptors, empty encryption refs and new backup UUID/time. Manifest preserves database UUID/fingerprint/checkpoint sequence; cluster null, Raft index/term -1, no key epochs. Returns materialized contents; caller owns lock. |
| `AetherCli.encryptBackupContents(contents, key)` | Encrypts every manifest object using BackupObjectAead with backup UUID/path, cloned key and epoch. Replaces bytes with envelopes and descriptors with envelope length/hash/reference; preserves kind/required format and manifest identity/provenance fields. Manifest key-epoch list becomes this one epoch. Allocates new content map/manifest, not in-place streaming encryption. |
| `AetherCli.decryptBackupContents(contents, keys)` | If all encryption refs empty, returns original contents. Otherwise processes every object: keeps plaintext objects, parses epoch from encrypted references, requires matching key, decrypts authenticated envelope using UUID/path. Rebuilds plaintext descriptors/hashes with empty refs and empty manifest epoch list, preserving other fields. Missing key/authentication failures propagate before restore target writes. |
| `AetherCli.keyEpoch(metadataReference)` | Uses last :key_epoch= marker, rejects absence and parses entire remaining suffix as long. Does not validate full metadata grammar or positivity here; malformed numeric suffix rejects. |
| `AetherCli.putBackupObject(objects, root, sourceName, objectPath)` | Reads root.resolve(sourceName) fully and stores under objectPath, replacing existing mapping if any. No helper-specific containment, filename or duplicate rejection; current caller supplies controlled names and holds source lock. |
| `AetherCli.backupKind(objectPath)` | Classifies basename exactly for identity/options/checkpoint/CURRENT, by MANIFEST-/SST- prefix otherwise; rejects unsupported basename. Prefix alone is not canonical filename validation. |
| `AetherCli.sha256(bytes)` | MessageDigest using manifest HASH_SHA256 over entire array. Missing algorithm becomes AssertionError, not mapped normal CLI invalid/I/O status. |
| `AetherCli.backupRestorePreflight(arguments)` | Parses shared restore arguments, reads BackupArchiveV1, calls BackupRestorePreflight.check without decrypting, prints report and returns passed ? 0 : 2. Declared epochs can satisfy availability check without proving key bytes/authentication. |
| `AetherCli.backupRestore(arguments)` | Parses/reads archive, decrypts using supplied key map, evaluates admission on resulting plaintext manifest. Rejection prints admission-specific shape/status 2; accepted delegates restoreCheckpointLayout with parsed options, renders result and returns 0. Does not separately acquire target lock or run verifyCheckpointDirectory; writer owns its write/preflight contract. |
| `AetherCli.backupRestoreDrill(arguments)` | Timer starts after parsing, before archive read. Checks original archive preflight first; rejection constructs failed report. Decrypts and evaluates admission, reporting failure similarly. Delegates restore writer, then locks target and verifies checkpoint; catches only IllegalArgumentException/IOException from that post-write block into failure list. Builds completed report and returns 0/2. Does not remove restored target after failed verification or test engine reads/writes. |

## Rendering and Admission Functions

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.printBackupPreflightText(contents, report, arguments)` | Prints PASS/FAIL, backup/database/cluster IDs, mode and object/byte totals, followed by failure strings when failing. Renderer does not validate or mutate target. |
| `AetherCli.printBackupPreflightJson(contents, report, arguments)` | Emits mode, passed, restoreMode, backup/database IDs, nullable clusterId, objectCount/totalBytes and escaped failures. UUID/enum fields are controlled; report totals are preflight totals. |
| `AetherCli.printBackupRestoreText(contents, result, arguments)` | Prints COMPLETE, backup ID, mode, target, preflight object/byte totals and restored object count. COMPLETE describes returned writer result, not extra checkpoint/engine verification. |
| `AetherCli.printBackupRestoreJson(contents, result, arguments)` | Emits backup-restore mode, completed:true, restoreMode/backupId, escaped target, preflight object/byte totals and restored count. No independent validation, failure array or checkpointVerified field. |
| `AetherCli.printBackupRestoreDrillText(report)` | Prints PASS/FAIL, IDs/mode/target, counts, checkpoint verified flag, elapsed Duration and each failure. Does not execute recovery test. |
| `AetherCli.printBackupRestoreDrillJson(report)` | Emits passed, restoreMode, backup/database IDs, escaped target, object/byte/restored counts, checkpointVerified, elapsedNanos and escaped failures. Represents report fields, not arbitrary uncaught read/decrypt/writer failures. |
| `AetherCli.backupAdmission(contents, options, draining)` | Evaluates static ADMISSION controller with options policy, zero measured BACKUP_OPERATION_BYTES/OBJECT_COUNT usage and requested manifest totalBytes/objectCount plus draining flag. No disk/heap sampling, reservation lease or accumulated concurrent usage tracking in wrapper. |
| `AetherCli.printBackupAdmissionText(mode, contents, admission)` | Prints FAIL, command mode, backup ID, manifest totals, outcome and reasons. Intended for rejected decisions; literal FAIL even if miscalled with accepted decision. |
| `AetherCli.printBackupAdmissionJson(mode, contents, admission)` | Emits supplied mode, admitted:false, outcome, backup ID, manifest totals and escaped failure reasons. Mode interpolated directly; current callers supply fixed command strings. Shape differs from normal create/restore success response. |

Encrypted manifest totals count envelope bytes. Normal restore admission/report
totals count rebuilt plaintext bytes. Drill early preflight failure reports
encrypted preflight totals, while completed drill reports writer plaintext totals.
Do not treat these as identical logical-size or physical-write measurements.
Renderer success does not establish durable archive publication or output delivery;
the PrintStreams are not checked for output errors.

## Options, Keys and Reports

| Declaration | Behavior and failure boundary |
| --- | --- |
| `AetherCli.BackupPreflightArguments.parse(arguments)` | Requires archive/target after command. Defaults SINGLE_NODE_NEW_DATABASE_ID, supported format 1; first --mode/--supported-format-version and optional existing UUID fields use requiredValue. Scans from index 3 for repeated --available-key-epoch and --key-epoch immediately followed by --key-hex; actual keys also add available epochs. Later same epoch replaces prior key. Normalizes paths, detects allow-non-empty/json membership and parses admission options. Does not reject all unknown tokens or repeated first-index options; epoch/format constraints partly delegated. |
| `AetherCli.BackupPreflightArguments.options()` | Constructs BackupRestorePreflightOptions from mode, target occupancy allowance, supported format, available epochs and existing IDs. Actual decryption keys/admission limits/JSON flag are not supplied to preflight options. |
| `AetherCli.BackupAdmissionOptions.BackupAdmissionOptions(hardBytes, hardObjects)` | Requires both limits positive. Does not validate actual archive or reserve resources. |
| `AetherCli.BackupAdmissionOptions.parse(values)` | Defaults bytes Long.MAX_VALUE/4 and objects Integer.MAX_VALUE; first --backup-hard-bytes/--backup-hard-objects parsed as long, then constructor validation. Unknown options not rejected here. |
| `AetherCli.BackupAdmissionOptions.policy()` | Builds limits for operation bytes/object count with soft, hard and resume all equal to their respective configured limit. Delegates semantics to AdmissionController. |
| `AetherCli.BackupEncryptionKey.BackupEncryptionKey(epoch, key)` | Requires positive epoch and non-null 32-byte key, clones array before storage. No keystore retrieval, zeroization or persistence. |
| `AetherCli.BackupEncryptionKey.key()` | Defensive clone on every accessor; encryption loop can allocate one per object. Caller cannot mutate stored array through this accessor. |
| `AetherCli.BackupEncryptionKey.create(values)` | Neither encrypt flag returns null; only one rejects; both parse long epoch and 64-char key using parseHexKey then validated constructor. Uses first occurrences, not a vault or interactive secret prompt. |
| `AetherCli.parseHexKey(value)` | Requires 64 characters, HexFormat parses into 32 bytes; invalid hex is wrapped with backup-key-must-be-hex message. Null input fails before explicit length validation. |
| `AetherCli.BackupRestoreDrillReport.BackupRestoreDrillReport(passed, backupId, databaseId, restoreMode, target, objectCount, totalBytes, restoredObjects, verified, failures, elapsedNanos)` | Requires IDs/mode/target/failures non-null, copies failure list, enforces passed equals failure-list emptiness, and nonnegative counts/time. Does not independently enforce verified equals passed, file existence or count parity. |
| `AetherCli.BackupRestoreDrillReport.failed(contents, arguments, preflight, failure, startedNanos)` | Prepends supplied failure and includes preflight failures; sets passed/verified false, restored count zero, original preflight totals and elapsed since start. Used before writes on preflight/admission failure; not a general catcher for thrown failures. |
| `AetherCli.BackupRestoreDrillReport.completed(contents, arguments, result, failures, startedNanos)` | Uses result's preflight totals/restored count, sets passed and verified to failures empty, preserves supplied IDs/mode/target and elapsed. Does not inspect files itself; trusts caller's completed writer and verification failure list. |

Records receive implicit accessors/equality/hash/toString. BackupEncryptionKey's
explicit constructor/accessor clone key bytes, but byte arrays are not erased after
use and record equality is not a constant-time secret comparison. Its implicit
toString does not print array contents, but --key-hex/--encrypt-key-hex values are
command arguments: shell history/process inspection can expose them. Keep real
keys out of shared command logs and review operational handling separately.

BackupPreflightArguments has no explicit defensive-copy constructor for its
available-key set, key map or arrays; parser-local mutable collections are passed
to it. BackupRestorePreflightOptions owns its own validation/copy behavior. Merely
declaring a record is not deep immutability.

## Restore Evidence and Failure Limits

Create verifies the checkpoint before capture and format-rereads the archive, but
does not perform a restore drill or decrypt its newly written encrypted contents
as part of that reread. Checkpoint source lock ends before archive write; captured
bytes remain in memory. Archive output uses CREATE_NEW, with no wrapper fsync,
rename or rollback; write/reread failure can leave a path that prevents retry.

Preflight validates supplied availability declarations; actual decrypt authenticates
objects. Default new-database restore mode and identity rewriting are owned by
BackupRestoreWriter, not a blind directory copy. Drill's post-write checkpoint
verification is separate from writer completion and does not open the engine for
application CRUD or check externally referenced payloads. Failures after target
writes preserve that target, and lock reacquisition can fail if another owner
intervenes. Not all errors become a structured drill failure: read, decrypt,
writer and unexpected runtime errors can escape to dispatcher/uncaught failures.

Archive capture includes only checkpoint identity/options/metadata/CURRENT/manifest
and live SSTables. It does not discover training-cache segments, application files,
cluster runtime state or remote replicas. A successful embedded restore drill is
not proof of complete training artifact or production disaster recovery.

## Verification Evidence

AetherCliTest source assertions cover archive objects and existing archive
rejection, create/restore admission rejection before output/target writes, plaintext
checkpoint roundtrip, encrypted epoch/reference capture, missing actual key rejection,
encrypted restore verification, successful encrypted drill and no-write preflight
failure. These were read, not executed in this documentation batch. They do not
establish exhaustive platform/power-loss behavior, bounded memory, every restore
mode, secret handling or all partial-output cases.

Compiler-tree checks match all 32 scoped declaration rows. No real key material,
archive, checkpoint or target database was used to author this guide.
