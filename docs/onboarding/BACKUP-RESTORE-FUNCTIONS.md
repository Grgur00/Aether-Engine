# Backup Restore Functions

[Function index](FUNCTION-INDEX.md) | [Backup archives](BACKUP-ARCHIVE-FUNCTIONS.md) | [Filesystem identity](FILESYSTEM-IDENTITY-FUNCTIONS.md)

This reference covers restore preflight and object writing in the current checkout.
It distinguishes policy labels from actions actually implemented by these helpers.
No runtime fixes or crash-safety certification accompany this documentation.
The [CLI backup reference](CLI-BACKUP-FUNCTIONS.md) covers command parsing,
decrypt/admission ordering, report shapes and the extra checkpoint-verification drill.

## Sources and Call Chain

- [BackupRestorePreflight](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupRestorePreflight.java): target/key/version/mode checks.
- [BackupRestorePreflightOptions](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupRestorePreflightOptions.java): operator policy inputs.
- [BackupRestorePreflightReport](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupRestorePreflightReport.java): blocking failures and declared totals.
- [BackupRestoreWriter](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupRestoreWriter.java): destination writes and best-effort cleanup.
- [BackupRestoreResult](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupRestoreResult.java): returned target/report/path list.
- [BackupRestoreMode](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupRestoreMode.java): policy enum.

Archive read verifies hashes/inventory first. Preflight evaluates supplied contents
and options. Writer calls preflight again, creates directories, copies object arrays
to CREATE_NEW files, and returns a result. These functions do not start an engine,
verify SSTable semantics, or publish a staged directory with an atomic rename.
Directly constructed BackupArchiveContents is not automatically hash-verified.

## Options and Modes

### BackupRestorePreflightOptions constructor

Requires a mode and positive supportedFormatVersion. Null available key epochs
become an empty set; other sets are copied and each epoch must be positive.
Set.copyOf can reject null entries before the explicit epoch loop. Existing
database/cluster IDs are retained without requiring nonnull/nonzero values here;
the selected mode decides whether they must match backup identities.

The allowNonEmptyTarget flag permits attempting writes into populated directories.
It does not authorize overwriting existing files; the writer still uses CREATE_NEW.
Supported format version is one numeric maximum for all object kinds, not a
per-kind decoder registry or the database format epoch.

### BackupRestoreMode constants

| Mode | Preflight identity rule |
| --- | --- |
| SINGLE_NODE_PRESERVE_DATABASE_ID | Backup must have no cluster ID, and database ID must equal supplied existingDatabaseId. |
| SINGLE_NODE_NEW_DATABASE_ID | Backup must have no cluster ID; this check does not generate a new database ID. |
| CLUSTER_DISASTER_NEW_CLUSTER | Backup must have a cluster ID; this check does not create a new cluster. |
| CLUSTER_MEMBER_REPLACEMENT | Backup must have a cluster ID matching supplied existingClusterId. |

The enum has no custom methods. Mode names describe operator policy, not complete
implemented identity rewriting, membership recovery, or Raft installation.

## Preflight Functions

### BackupRestorePreflight constructor and check(contents, targetDirectory, options)

The private constructor prevents instances. `check()` requires nonnull arguments,
normalizes the target to absolute form, gathers target/key/version/mode failures,
and returns a report with manifest objectCount and totalBytes. Policy failures are
accumulated, not thrown at the first mismatch; filesystem I/O errors can still
propagate instead of becoming report entries.

It does not rehash arrays, check compatibility fingerprint/format epoch, decode
identity/options/manifest/SSTable bytes, reserve disk space, or lock the target.
Its totals come from manifest declarations, not destination measurements.

### checkTarget(target, options, failures)

For an existing target, requires a directory and (unless allowed) emptiness, using
a closed Files.list stream. For an absent target, requires its immediate parent
to exist as a directory. It does not create paths during preflight or accept a
chain of missing parents just because the later writer uses createDirectories.

These Files.exists/isDirectory checks follow links by default. The helper does
not call PathSecurityValidator, reject symlink ancestors, check writability,
estimate available bytes, or prevent check/write races. Filesystem permission and
I/O failures can arise after a passed report.

### checkKeys(manifest, options, failures)

Checks each declared encryption key epoch against the supplied available set.
It does not obtain key material, validate decryption, inspect per-object encryption
references, or transform ciphertext. Possessing an epoch number is a policy claim
in this input model, not proof of a functioning key provider.

### checkFormatVersions(manifest, options, failures)

Adds a failure for any object's required version greater than supportedFormatVersion.
It does not inspect object bytes, negotiate object-kind codecs, or compare the
manifest's format compatibility fingerprint with the running engine.

### checkRestoreMode(manifest, options, failures)

Applies the four identity rules above. It does not ensure a complete required
inventory for the chosen mode or validate Raft snapshot index/term against actual
log/snapshot bytes. A preflight pass is narrower than an openable, recovered database.

## Writer Functions

### BackupRestoreWriter constructor, restore(), restoreCheckpointLayout()

The private constructor prevents instances. Public `restore()` delegates to the
private overload with checkpointLayout false; `restoreCheckpointLayout()` passes
true. Both accept in-memory contents, destination, and policy, and both return
BackupRestoreResult only after all object write calls complete.

### restore(contents, targetDirectory, options, checkpointLayout)

Requires nonnull inputs, normalizes target, runs preflight, and throws IOException
with joined failures if blocked. Creates target directories before entering the
per-object try/catch. For each manifest object, chooses ordinary or checkpoint
mapped path, checks lexical resolution, creates parent directories, gets a cloned
object array, and calls Files.write with CREATE_NEW and WRITE. Only after that call
succeeds is the path added to the cleanup list.

All objects are copied as supplied. No ID rewrite, decryption, hash recheck, metadata
repair, manifest regeneration, post-write engine open, or sequence verification is
performed. There is no fsync/file force, directory force, staged rename, lock lease,
or special CURRENT-last ordering: write order is manifest order.

On Throwable inside the object loop, cleanup is attempted. IOException and
RuntimeException are rethrown; other Throwables (including Errors) are wrapped in
IOException. The initial target createDirectories is outside that cleanup catch.
Failure during the current Files.write can leave a partial file because its path
has not yet been recorded. Process termination also bypasses Java cleanup.

### checkpointRestorePath(object)

Requires `objects/` prefix and one nonblank flat remaining filename, with no
slash/backslash or dot/dot-dot. Accepts only these kind/name combinations:

| Kind | Restored name |
| --- | --- |
| DATABASE_IDENTITY | DB-IDENTITY |
| FORMAT_OPTIONS | FORMAT-OPTIONS |
| CHECKPOINT_METADATA | CHECKPOINT-METADATA |
| CURRENT | CURRENT |
| MANIFEST | MANIFEST- prefix and .aeman suffix |
| SSTABLE | SST- prefix and .aess suffix |

Other kinds, including WAL/security/Raft/report objects, are rejected in this
layout. Prefix/suffix tests do not parse numeric generations or validate the
object's internal format. Mapping strips objects/; it does not alter payloads.

### resolveObject(target, path)

Resolves and normalizes the path, requiring lexical startsWith(target). This guards
ordinary normalized parent escape but is not a real-path/symlink containment check.
Existing linked parent directories can redirect writes. CREATE_NEW guards against
replacing an existing final entry; it does not pin every directory component.

## Cleanup Functions

### cleanupRestored(target, restored, failure)

Deletes successfully recorded object paths in reverse order, attaching IOException
cleanup failures as suppressed exceptions. Then walks the target, orders paths
in reverse, excludes the target itself, and attempts to delete directories.
Nonempty-directory failures are ignored; an outer walk IOException is suppressed.

Directory cleanup is not limited to directories created by this restore: preexisting
empty subdirectories can be removed when a nonempty target was allowed. The target
itself remains, and a partial current object can prevent its parent cleanup. The
walk/checks are not a rollback journal or crash-recovery protocol. Cleanup may fail
and leave output requiring inspection.

## Reports and Results

### BackupRestorePreflightReport constructor and accessors

Copies a required failure list, requires passed to equal failures.isEmpty, and
requires nonnegative object/byte totals. Implicit accessors expose those values and
the immutable list. The constructor does not verify that totals match actual files
or that caller-supplied failure text corresponds to checks performed.

### BackupRestoreResult constructor and accessors

Requires target and preflight report, copies restoredObjects, and requires a passed
report. It does not normalize target or verify written objects/counts itself.
Implicit accessors return stored Path/report/immutable paths. Successful construction
is not independently durable publication or proof that an engine can open the target.

## Coverage Boundary

All explicit functions and constructors in the six linked files are covered.
Together with archive and filesystem references this covers the I/O module's
implementation classes; package documentation is not another executable API.
Engine checkpoint orchestration, config consumers, operational commands, research,
ML/Python, and distributed foundations remain broader documentation work.
