# Persistent Engine Internals

[Engine function reference](ENGINE-FUNCTIONS.md) | [Storage engine](STORAGE-ENGINE.md)

[WAL format functions](WAL-FUNCTIONS.md) covers logical groups, fragments and
segment-header validation beneath the commit and recovery paths on this page.

Source: [PersistentAetherDatabase.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/PersistentAetherDatabase.java).
Public entry points are described in the engine reference; this page follows
the private functions behind them. None of these helpers is an independent service.

## State And Locks

`CommitCoordinator.monitor` protects request election, queue and completion.
The database monitor protects public reads, visibility, group application,
planning and installation. `closeMonitor` serializes closers. The compaction
worker has its own monitor and executor.

Commit leadership releases the queue monitor before processing database state.
Compaction constructs outside the database monitor and reenters for install.
Close waits for the worker without holding the database monitor. Waiting while
holding a lock the worker needs would deadlock teardown.

Flush replaces `active` and `wal`; `tables` is the current open-reader inventory.
`versions.current()` is authoritative metadata. Merely creating a file does not
publish it into either inventory.

## Commit Coordination

### CommitRequest, PreparedCommit And CommitCoordinator.submit

`CommitRequest(WriteBatch, WriteOptions)` retains batch/options and captures the
caller trace. Mutable result/failure/done fields return the outcome to that
caller. `PreparedCommit` retains a request and assigned sequence interval after
WAL append/application; it is not itself persisted.

`submit` enqueues under its monitor. An arriving caller becomes leader when none
is active; others wait for completion or later leadership. The leader parks
200,000 ns, gathers at most 64 queued requests, calls `processCommitGroup`, marks
members done and notifies waiters. Remaining requests can elect another leader.
There is no dedicated commit thread. Interrupted waits are remembered and the
flag restored before return/throw; interruption does not cancel an uncertain write.

### requiredNativeBytes(WriteBatch, long[])

Checked-sums worst-case native insertion bytes per key/value length. Optional
single-element `logicalBytes` accumulates WAL operation/payload lengths for
tracing; the caller seeds its group-header size. Mutation accessors clone arrays,
so estimation can allocate too. This is not final SSTable size.

### processCommitGroup(List<CommitRequest>)

Runs under the database monitor. For each request, rejects an existing fence,
checks lifetime, seals and handles empty batches without append/force. Estimates
native bytes, rejects more than memtable capacity minus 256, and calls admission,
which may flush older data. Derives a checked contiguous sequence interval,
encodes a logical group and rotates WAL via flush if its estimated end crosses
threshold. Fragments at actual channel offset with the next record number.

Marks `SUBMITTED` before pre-write hook/append. Appends physical bytes, updates
record count, applies mutations, then advances `lastVisibleSequence` and records
the prepared request. Every mode except `ASYNC_WAL` requests one shared force.
Successful participants receive their own interval/count/result; even an async
participant may report `forced = true` when another required the shared barrier.

Post-submission uncertainty or force failure fences writes, makes prepared
requests indeterminate and rejects remaining requests. It does not roll back
WAL bytes. A recovery is needed to establish the authoritative outcome.
The historical hook name `WAL_AFTER_FORCE_BEFORE_VISIBILITY` is misleading:
the field has already advanced before force, but reads cannot interleave because
the monitor covers the group. Follow assignments and locks, not hook names alone.

### applyBatch, transitionFailure And failBeforeSubmission

`applyBatch(table, batch, firstSequence)` inserts puts/deletes in order, advancing
sequence per mutation. Requires native `INSERTED`; otherwise throws after WAL
submission. It does not itself advance database visibility or undo insertions.

`transitionFailure(request, failure)` maps sealed to failed and submitted to
indeterminate, retaining runtime exceptions or wrapping other causes. Records
the exception without resetting other lifecycle states.

`failBeforeSubmission(request, failure)` tries to seal an open batch and mark a
sealed batch failed. State-transition errors become suppressed on the original
rejection. It appends nothing and cannot make submitted bytes definitely absent.

### forceWal()

Calls `wal.force(false)`, measuring elapsed time even on failure. Only success
increments the successful-force counter. It is not table verification or manifest
publication; tracing attributes a shared force once, not per participant.

## Admission

### admitWrite(long, WriteOptions, long)

Flushes a nonempty memtable when required native bytes do not fit. Requests
compaction, collects pressure and maps the decision. Normal proceeds; slowdown
can park once when fail-fast is disabled and the delay fits timeout. Overflowing
timeout conversion uses `Long.MAX_VALUE`. Other cases throw outcome/reasons.
This is one evaluation/park under the group monitor, not a pressure polling loop.

### pressure(long), writeAdmissionDecision And writeAdmissionReasons

`pressure` collects positive excess bytes over targets for levels 1 through 5,
native fit, WAL size, level-zero count and filesystem usable/total space, then
calls the controller. Supplies zero immutable backlog and disk-pressure enable.
No asynchronous immutable queue is implemented merely because a setting names one.

`writeAdmissionDecision` maps normal to accepted, slowdown to rejected-before-ack
plus delay, stopped-retryable to resource-exhausted and failed to rejected-before-ack.
`admitWrite` can honor slowdown, so this mapping is not the complete algorithm.
`writeAdmissionReasons` prefixes/sorts explicit reasons or uses state as fallback.

## Flush

### flushActive Overloads, flushStage And allocateTableFileNumber

The no-argument `flushActive` supplies cause `OTHER`; the parameterized overload
returns for empty state, increments started, optionally creates a usage/limit
trace and invokes `flushActiveBody`. Completed increments only on success;
trace finalization runs even on failure. `FlushCause` distinguishes native
capacity, WAL segment and other attribution, not extra durability modes.
`flushStage` advances an optional trace. Synchronized `allocateTableFileNumber`
returns next number and checked-increments it; failed builds can consume numbers.

### flushActiveBody(Event)

Freezes active, builds sorted entries into a unique temporary table, finishes,
atomically renames and synchronizes the directory. The pre-manifest hook can
stop here with a still-unauthoritative file. Temporary names are finally removed.
Builds level-zero metadata, creates the next forced WAL and publishes a manifest
delta containing the table, persisted/assigned watermark and new required WAL.
Publication failure closes/deletes replacement WAL with suppressed cleanup errors.

After publication swaps WAL, resets record number, closes/deletes obsolete WAL,
synchronizes, opens the table, retires old memtable, allocates a replacement and
requests compaction. Removing the old WAL before publication would lose recovery
data before the table became authoritative.

## Compaction Planning

### needsCompaction(), levelBytes(int), compactionDebtBytes()

Need triggers at four level-zero files or an over-target level among 1 through 5.
Level bytes sums current manifest file sizes. Debt includes all level-zero bytes
once the count trigger fires plus positive higher-level excess. Debt is a policy
quantity, not a frozen count of bytes in one scheduled plan.

### requestCompaction(long), compactionDiagnostics(), awaitCompactionIdle()

Request is a no-op while closing, disabled, fenced or below triggers; otherwise
hands a timestamp/trace/origin wakeup to the coordinator. Traced acceptance records
debt, not a preselected plan. Diagnostics returns flags, counters, table/debt/stop
values, coordinator failure and copied history bounded to 64 records; closed debt
is zero. Await delegates with 60 seconds. Idle does not imply empty memtable or
absence of a previous failure.

### runBackgroundCompaction(Request) And planCompaction()

The runner attaches worker tracing and selects under the database monitor.
No plan returns false; a completed plan returns true so the worker replans.
Always restores/finalizes tracing. Actual plans record delay, input/output counts,
bytes, publication/completion and failure under the monitor, with bounded history.

Planning stops when closing/fenced. Prioritizes all level-zero files at the count
trigger, targeting level 1; otherwise starts from the first file of the first
over-target level among 1 through 5, targeting the next. Not exhaustive cost search.

### selectCompaction(List<Metadata>, int) And CompactionPlan Constructor

Selection repeatedly expands its key interval to include overlaps in input and
output levels until stable. Captures matching readers, immutable current version,
oldest retained snapshot (or current visible sequence), inputs and output level.
The plan constructor retains these; output counts/bytes/publication are filled
during execution. The plan is not a durable receipt or guarantee inputs stay current.

## Compaction Execution

### executeCompaction(CompactionPlan, Trace)

Materializes table entries as LSM values/tombstones, sorts, deduplicates and runs
retention with oldest snapshot and lower-level overlap predicate. Partitions
retained records at user-key boundaries. Builds/forces/renames output tables,
synchronizes, verifies additions and opens replacements outside the monitor.
Inventory and reader verification are distinct here, unlike empty-store bulk's
single-authoritative-verifier path. This is not a streaming zero-copy merge.

Reenters the database monitor and rejects fencing, removed input readers or
inputs missing in authoritative metadata. Uses *current* edit/sequence/WAL fields
for the delta, preserving intervening flushes instead of replacing with stale plan
state. Sets publication-attempted before append. Manifest uncertainty sets both
engine failure fields and preserves input/output sets for recovery.

Success swaps visible readers. All public reads/scans finish under that monitor
and cursors materialize rows, so old readers can close after swap. Before any
publication attempt, failed outputs can be removed; after uncertain attempt they
must remain. Installed old readers close in `finally`, including post-commit fault.
Normal completion deletes obsolete files and synchronizes the directory.

### deduplicateInternalEntries, partitionCompactionOutput, overlaps, isBaseLevelForKey

Deduplication requires sorted entries and collapses consecutive exact identities,
rejecting duplicate value identities with different bytes. Different versions
of one user key remain. Partitioning estimates key plus 32 bytes plus value;
splits above target only at a different user key. One key's retained versions can
make output exceed target; the target is not a hard cap.

`overlaps` tests inclusive intervals with unsigned-byte order.
`isBaseLevelForKey` checks deeper captured-version levels through 6; coverage
returns false so retention cannot expose an older deeper value by dropping its
tombstone. `compactionStage` only advances a non-null optional trace.

## Recovery

### initializeIdentityAndWal(Path), validateIdentityPair(Path)

Initialization refuses unidentified directories containing anything but `LOCK`.
Generates identity/time, atomically writes identity/options, creates/closes WAL 1
and synchronizes. Pair validation requires options/CURRENT and matching decoded
UUIDs. Later open steps validate authoritative tables/manifest; the pair check
alone is not full-store verification.

### createWalSegment(Path, UUID, long, long, long)

Uses fresh `CREATE_NEW` read/write access; encodes identity/chain/start sequence
and timestamp, writes full header, forces metadata and positions after header.
Returns an owning channel. Failure closes/removes the new file with suppressed
cleanup errors and propagates the operation exception.

### initializeCheckpointWal(Path, UUID, VersionSet), cleanupObsoleteWalFiles(Path, long)

Checkpoint-WAL initialization creates segment 1 after the last assigned sequence
when the manifest requires no WAL, then publishes a delta requiring it while
preserving watermarks/inventory. Failed publication cleans up; success closes and
synchronizes, and normal open reopens. Obsolete cleanup requires the required
segment and deletes every other matching fixed-width WAL filename, not only
earlier numbers. Rejects unsafe obsolete symlink/non-file paths and synchronizes
deletions. Manifest authority wins over the largest filename.

### recoverWal, decodeGroup, applyDecoded And WalRecovery

Recovery validates header identity, materializes the physical tail, reassembles
groups and tracks valid physical end/count. Skips groups fully below persisted
watermark; rejects crossing groups or sequence gaps. Applies accepted groups
without reserving new sequences, returning the valid end/count/last sequence
record. Open truncates at that valid end. Framing rules govern incomplete tail;
arbitrary interior corruption must not become silent empty recovery.

`decodeGroup` converts logical `WalCorruptionException` to `IOException` retaining
cause/message. `applyDecoded` uses recorded first sequence and requires native
insertion success, otherwise throws recovery exhaustion. `WalRecovery` itself is
an in-memory record, not another persisted checkpoint.

## Configuration And Utilities

### RuntimeConfiguration Constructor, from, longValue, intValue, booleanValue

Validates native capacity, format-bounded WAL threshold, positive snapshot maximum
and non-null policies/options. `from` consumes native/WAL bytes, snapshot limit,
compaction/disk-pressure flags and immutable limit; uses default level targets
and derives pressure policy. Registry-resolved durability is uppercased with
`Locale.ROOT`; admission timeout/fail-fast use defaults. The primitive helpers
resolve registered defaults/supplied strings and parse. Registry presence alone
does not establish consumption by this runtime.

### ensureOpen, validateKey, createMemTable

Lifetime rejects closed/closing. Key validation rejects null or more than
`MAX_KEY_BYTES`, allowing empty. Memtable creation uses shared budget/capacity,
identity/number name and UUID-bit XOR seed; it creates no WAL or manifest.

### atomicWrite, syncDirectory, writeFully, readRange, little

Atomic write validates managed target, writes a unique fresh temporary file fully,
forces metadata, moves atomically and synchronizes; finally deletes temporary name.
Directory sync suppresses access-denied/unsupported-operation exceptions but not
other I/O errors. A call is not proof every platform performed directory fsync.
Full write loops to buffer exhaustion. Range read allocates exact bytes and uses
positional reads, rejecting premature EOF. `little` wraps without copying and
sets little-endian order; currently unused in this class.

### merge And closeSuppressed

Merge retains first failure or adopts next when none, attaching a distinct next
failure as suppressed. Suppressed-close ignores null resources or attaches
cleanup errors to the original failure. Neither erases the original evidence.

## Fault Context And Test Probes

`walCrashContext` carries record/offset/logical/physical counts;
`tableCrashContext` carries file/level/size/count/sequences;
`compactionCrashContext` carries output level/addition/deletion counts;
`writeSequenceCrashContext` carries sequence range/count; `forceCrashContext`
carries participant count and outer interval. They build hook attributes, not
disk barriers. `walForceCountForTesting` reads successful force count under lock.
`configuredMaximumSnapshotsForTesting`, `configuredWalSegmentBytesForTesting`,
`configuredImmutableMemtableStopForTesting`, `configuredDefaultDurabilityForTesting`
validate/derive runtime configuration and expose consumed values; do not mutate it.

## CompactionCoordinator Functions

Source: [CompactionCoordinator.java](../../modules/aether-engine/src/main/java/io/aetherdb/engine/CompactionCoordinator.java).
Constructor retains `Work`; executor factory creates one `aether-compaction`
daemon thread. `Request` carries times/trace/origin. `Work.run` returns true for
a performed plan needing replan, false to stop its planning loop.

`requestCompaction` synchronously rejects stopping, keeps the first pending
wakeup and schedules `drain` when inactive. It coalesces, not queues plans.
`drain` takes/clears pending under monitor and runs work outside it until false
or stopping. Success clears coordinator failure and checks more wakeups. No
pending/stopping clears active and notifies. Any throwable records failure,
discards pending, clears active and exits; no automatic retry loop. A later event
is necessary, and an engine manifest fence may prevent rescheduling.

`stopping` and `backgroundFailure` are synchronized getters. `state` returns
running/idle when open or stopping/closed after stop depending on active work.
Idle can coexist with failure. Coordinator failure differs from engine fencing.
`awaitIdle(Duration)` uses a monotonic timed wait, propagates interruption and
throws on timeout; it neither resubmits nor flushes or clears failures.

`close` sets stopping/clears pending, shuts down without interrupting construction
or publication, and waits 60 seconds. Timeout throws while resources remain
owned. Interruption restores the flag and throws the same ownership warning.
Database resources must not close while the worker can still use them.
