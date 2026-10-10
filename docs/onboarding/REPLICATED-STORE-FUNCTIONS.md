# Replicated Log Storage and Recovery Functions

[Function index](FUNCTION-INDEX.md) | [Replication contracts](REPLICATION-CONTRACT-FUNCTIONS.md) | [Persisted formats](REPLICATION-FORMAT-FUNCTIONS.md)

Source: [ReplicatedLogStoreV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedLogStoreV1.java).
This reference covers **43 explicit declarations in the complete file**, including
private recovery/I/O helpers and the nested Location record. Together with the
contract and format references, all seven implementation files in
aether-replicated-log have explicit-declaration coverage: 43 store, 43 format,
and three planner declarations. That is not an integrated Raft service certification.

## Ownership and Runtime Architecture

```text
open -> create/validate root -> exclusive DatabaseLock -> identity -> recover
append -> validate/encode entire input -> append records -> optional force
read -> in-memory Location -> fresh file channel -> exact decode + metadata check
truncate -> guard supplied commit/applied coordinates -> delete/truncate/force
close -> mark closed -> force/close active channel -> release lock
```

The store owns one directory lock and one active read/write channel. A TreeMap
retains one Location per entry: path, segment, byte offset/length, coordinates,
state sequence and copied entry hash. It does not retain decoded payloads or a
bounded cache. Recovery rebuilds this map from every complete entry, so retained
log length determines index memory and recovery work.

All public instance methods are synchronized on this store. Calls serialize
within the instance, including reads and I/O; the directory lock establishes
process exclusivity. Private helpers rely on their caller's lifecycle/monitor.
No RPC, quorum, election, state-machine apply, snapshot installation, or commit
index persistence is performed here. Supplied commit/applied numbers are caller
claims, not internally maintained consensus state.

## Open and Metadata Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogStoreV1.open(requested, clusterId, nodeId)` | Normalizes absolute path, creates directories, validates root with PathSecurityValidator, acquires DatabaseLock, opens/publishes identity, constructs store, then recovers. Transfers lock ownership before recovery. Catches Throwable, attempts store/lock cleanup with suppressed failures, and wraps original problem in IllegalStateException. Null arguments and parser exceptions can therefore be wrapped too. Does not undo directories or already published files on failure. |
| `ReplicatedLogStoreV1.ReplicatedLogStoreV1(root, durableIdentity, lock)` | Private constructor stores ownership and creates immutable API identity from durable UUIDs. Does not open an active segment or run recovery itself. |
| `ReplicatedLogStoreV1.identity()` | Requires not closed, then returns immutable identity. Does not recheck disk or reject a previously failed writer. |
| `ReplicatedLogStoreV1.firstIndex()` | Requires open; TreeMap first key or zero when empty. No prefix-compaction API is implemented here. |
| `ReplicatedLogStoreV1.lastIndex()` | Requires open; final retained key or zero. Can include entries not yet forced. |
| `ReplicatedLogStoreV1.lastTerm()` | Requires open; final cached Location term or zero. Not the current election term. |
| `ReplicatedLogStoreV1.lastStateSequence()` | Requires open; cached sequence after last retained/appended entry. Not proof that mutations were applied. |
| `ReplicatedLogStoreV1.durableIndex()` | Requires open; cached force/recovery boundary. No persisted force-index receipt or quorum acknowledgment is stored. |

Metadata remains readable after a write-path failure because ensureOpen does not
check the stored failure. The map may then represent only the prefix published
in memory before the failure, not every byte partially written to disk.

## Append, Rotation, and Force Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogStoreV1.append(entries)` | Synchronized entry to appendInternal with force=false. Rotation may still force a previous segment; absence of explicit force does not mean no bytes became durable. |
| `ReplicatedLogStoreV1.appendAndForce(entries)` | Synchronized entry to appendInternal with force=true. A successful return follows forceThrough of final appended index; does not establish consensus commitment. |
| `ReplicatedLogStoreV1.appendInternal(entries, force)` | Requires writable; rejects null/empty list, copies list (null elements cause NullPointerException), validates continuity and encodes every record before I/O. Holds all encoded records in memory. Then appends each and optionally forces. Throwable during this I/O phase latches failure and becomes IllegalStateException; validation/encoding errors before the try block do not latch failure. No rollback of an already written batch prefix. |
| `ReplicatedLogStoreV1.appendOne(entry, record)` | If nonempty active segment plus record exceeds 128 MiB target, rotates first. Rejects resulting size above 256 MiB hard limit with IOException. Writes record positionally at prior size, then publishes Location and updates sequence. Does not itself force. Partial write before failure is not inserted into map. |
| `ReplicatedLogStoreV1.rotate()` | Forces old active channel, sets durableIndex to current last index, closes channel, and creates next segment linked to last index/term/hash. Post-increments nextSegmentNumber before creation finishes. Failure can leave old channel closed or a partial new file; outer append latches failure. No transactionally published segment inventory. |
| `ReplicatedLogStoreV1.createSegment(number, firstIndex, previousIndex, previousTerm, previousHash)` | Creates canonical path with CREATE_NEW, writes full 4096-byte header, forces file, then forces directory. Only then publishes active channel/path/number. Finally closes a not-transferred channel. Failed creation does not delete its file; a partial header can make later open fail. |
| `ReplicatedLogStoreV1.forceThrough(requestedIndex)` | Requires writable, accepts 0..lastIndex, returns if requested<=durableIndex. Otherwise forces entire active file, sets durableIndex to current last index (possibly beyond request), then fires RAFT_APPEND_AFTER_LOG_PERSIST_BEFORE_REPLY with requested/durable attributes. IOException latches failure and wraps. Other hook exceptions propagate directly when called standalone; appendAndForce's outer catch latches/wraps them. |
| `ReplicatedLogStoreV1.appendPersistContext(requestedIndex, durableIndex)` | Builds CrashContext with unsigned decimal requested_index and durable_index values. Provides diagnostic context, not a durable receipt. |
| `ReplicatedLogStoreV1.validateNext(entry, expectedIndex, previousTerm, previousStateSequence, previousHash)` | Requires nonnull entry, exact next index, term>=previous term, and predecessor hash content match. COMMAND derives inclusive count with Math.toIntExact then validates next sequence range via planner (1..10,000 operations). Non-COMMAND must have sequenceStart=0 and preserve prior state sequence. Throws IllegalArgumentException/ArithmeticException as appropriate. Entry codec separately validates command body/hash. |

Prevalidation avoids writing an invalid later entry from the same input list.
It does not make a multi-entry append atomic against I/O failure or process death.
Several full record/payload copies can coexist during encoding. There is no
batch-size or aggregate-memory cap in appendInternal beyond per-record limits.
Index increments and next-segment post-increment are ordinary long arithmetic,
not checked allocation counters; later format/continuity validation still applies.

| Boundary | What it establishes |
| --- | --- |
| Record fully written, Location inserted | Entry is visible to synchronized reads in this instance. Not necessarily forced. |
| Rotation force | Prior segment's entries become reflected in durableIndex before creating a new segment. |
| Explicit force | Whole active tail forced; durableIndex may exceed requested index. |
| Persist-before-reply crash hook | Force and durableIndex update have already occurred. Hook failure does not roll them back. |
| Recovery success | Complete validated retained prefix rebuilt and reported as durable. Recovery does not recover a separately recorded consensus commit/applied boundary. |

## Read Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogStoreV1.read(requestedIndex)` | Requires open and retained Location; missing index raises IllegalArgumentException. Reads/decode checks from fresh channel. IOException becomes IllegalStateException but does not latch writer failure; unchecked codec corruption errors propagate. |
| `ReplicatedLogStoreV1.readRange(startInclusive, endExclusive, byteLimit, entryLimit)` | Requires start>=1, end>=start, byteLimit>=1, entryLimit>=1. Iterates retained TreeMap subrange; gaps outside retained keys do not themselves throw. Equal endpoints return empty. First available record is returned even if larger than budget, then stops; later records stop before crossing byte budget or entry count. Accounts complete encoded record length, not payload length. Returns List.copyOf; read IOException wraps without latching. |
| `ReplicatedLogStoreV1.termAt(requestedIndex)` | Requires open/retained; returns cached Location term without rereading file. Disk corruption after open is not detected by this accessor. |
| `ReplicatedLogStoreV1.entryHashAt(requestedIndex)` | Requires open/retained; clones cached hash. Does not reverify disk or actual predecessor. |
| `ReplicatedLogStoreV1.readLocation(location)` | Opens location.path read-only per call, reads exact stored range, fully decodes record, then compares decoded index/term/hash against map. Metadata mismatch raises IOException. Channel closes on success/failure. Not a mapped read or cached payload. |
| `ReplicatedLogStoreV1.requiredLocation(requestedIndex)` | TreeMap lookup; absent key raises IllegalArgumentException naming requested index. Private helper does not independently check lifecycle. |
| `ReplicatedLogStoreV1.location(path, segment, offset, length, entry)` | Captures coordinates, state and defensive entry hash into a new Location (which copies hash again). No file existence validation. |
| `ReplicatedLogStoreV1.Location.Location(path, segmentNumber, offset, length, index, term, stateSequenceAfter, entryHash)` | Private compact constructor clones hash. No independent coordinate/path/length checks; construction relies on verified caller metadata. |
| `ReplicatedLogStoreV1.Location.entryHash()` | Defensive hash clone; other generated accessors expose immutable path/scalars. Outer store can also read private record fields directly. |

The concrete first-entry budget rule is stronger than the interface's wording
about a fitting entry: readRange(1, 3, 1, 10) can return one record much larger
than one byte. Budget controls subsequent accumulation, not strict first-record
admission or peak decode allocation.

## Recovery and Discovery Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogStoreV1.recover()` | Discovers ordered segments; if none, creates initial linked-empty header and sets next number=2. Otherwise begins expected index=1, term/state=0, zero predecessor hash. Validates each full segment header against durable identity/file number and accumulated predecessor. Reads records, validates codec and continuity, and builds map. Only an incomplete final tail is truncated/forced; complete corruption is rejected. Rejects empty nonfinal segment. Opens final segment active, sets next number to checked max+1, durableIndex=last retained and sequence=accumulated state. |
| `ReplicatedLogStoreV1.discoverSegments()` | Lists directory into a temporary list. Ignores identity and matching identity temp names; collects exact RLOG-20-digit.aerlog names; rejects malformed RLOG- names except matching segment temp names. Unrelated names and matching temps are ignored, not cleaned. Rejects >1,000,000 collected segments after discovery, sorts lexically, returns immutable list. Does not demand contiguous file numbers or bound all directory entries before materializing listing. |
| `ReplicatedLogStoreV1.validatedRecordLength(header)` | Requires exact 192 bytes and AERE prefix, reads declared int length at offset 8, checks 200..64 MiB and 8-byte alignment, and verifies header CRC over 0..167 against offset 168. Full version/reserved/payload semantics remain with entry decode. Runs before deciding whether a declared final record is incomplete. |
| `ReplicatedLogStoreV1.segmentNumber(path)` | Parses filename substring 5..24 as signed long. Relies on discovery naming shape; all-zero number or out-of-long decimal can still fail subsequent validation/parsing. No checked exception conversion here. |

Recovery starts at index 1 and a zero chain. This implementation has no accepted
snapshot prefix base that would let it reopen a retained log beginning later.
Segment numbers can have gaps if headers/entry continuity still agree; filenames
are not the entry index. Recovery does not explicitly reject oversized preexisting
segment files against the hard segment cap, although each record length is bounded.

| Tail encountered | Recovery action |
| --- | --- |
| Segment shorter than 4096-byte header | Reject, even if final; never repairs a partial segment header. |
| Fewer than 192 bytes remain after valid entries | Final segment: truncate at entry boundary and force. Interior segment: reject. |
| Validated 192-byte entry header declares more bytes than remain | Final segment: truncate/force. Interior segment: reject. |
| Full header with bad prefix/length/header CRC | Reject, even if final. Not treated as harmless incomplete append. |
| Complete entry with bad payload/trailer/hash/continuity | Reject; does not discard complete corruption. |
| Zero entries in a nonfinal segment | Reject. Final empty header is accepted. |

Reopen reports the valid recovered prefix as durable; it does not distinguish
which complete entries had an explicit force acknowledgment before the prior
process stopped. Caller must not infer consensus commitment from recovered bytes.

## Suffix Truncation and Close Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogStoreV1.truncateSuffix(fromIndex, commitIndex, appliedIndex)` | Requires writable; rejects from<1 or >last+1, negative progress, and from<=either protected boundary. Valid last+1 is a no-op. Looks up first removed record, collects later paths represented in removed Locations, closes active channel, deletes later files, truncates containing file at record offset and forces it, clears map suffix, forces directory, reopens containing file active and resets durableIndex/state to retained tail. Throwable inside I/O phase latches/wraps failure; no rollback journal. Does not independently verify supplied commit/applied truth or ordering. |
| `ReplicatedLogStoreV1.close()` | Idempotent flag check; marks closed before I/O. If active open, force then close, then separately attempts lock release and aggregates failures. Wraps any collected failure. If force throws, active.close in the same try is skipped; subsequent close returns immediately because flag is already set. Thus cleanup can leave an active channel after force failure despite releasing lock. Does not refresh durableIndex, which is inaccessible through public methods after close. |

Truncation deletes later files **before** shortening the containing file. Crash
atomicity across those steps is not supplied by a manifest or transaction record.
An empty active segment not represented by any Location is not in laterPaths;
truncation therefore does not discover/delete every later segment by directory
inventory. nextSegmentNumber is not rewound when reusing a containing segment.
These details require failure/reopen testing, not an assumption that every guarded
truncate is crash-atomic. No runtime changes are made by documenting them.

## Identity, Raw Access, and I/O Helpers

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogStoreV1.openIdentity(root, clusterId, nodeId)` | Existing identity: reads whole file, decodes exact format, requires UUID match. New identity: uses current time, unique tmp name, CREATE_NEW write and file force, closes, requires ATOMIC_MOVE to final path, then directory force. Finally deletes tmp if present. No non-atomic fallback. Publication/directory-force failure can leave final identity visible; finally cleanup failure can replace an earlier exception. |
| `ReplicatedLogStoreV1.lastIndexRaw()` | Last cached key or zero, without lifecycle check. |
| `ReplicatedLogStoreV1.lastTermRaw()` | Last cached term or zero, without lifecycle check. |
| `ReplicatedLogStoreV1.lastHashRaw()` | Fresh 32-byte zero array when empty; otherwise cloned cached tail hash. |
| `ReplicatedLogStoreV1.ensureOpen()` | Throws IllegalStateException if closed; does not check latched write failure. |
| `ReplicatedLogStoreV1.ensureWritable()` | Calls ensureOpen, then wraps latched failure in IllegalStateException. No recovery/reset method clears failure in place. |
| `ReplicatedLogStoreV1.writeFully(channel, bytes, offset)` | Positional write loop advances supplied buffer and offset until consumed. Negative result raises EOFException. Does not force; a repeated zero-progress result has no backoff or explicit iteration bound. |
| `ReplicatedLogStoreV1.readFully(channel, offset, length)` | Allocates exact-length array, positional read loop, negative result raises EOFException. No independent length cap; verified callers bound record reads. Repeated zero-progress result has no backoff/bound. |
| `ReplicatedLogStoreV1.syncDirectory(directory)` | Opens directory as read-only FileChannel and force(true). IOException propagates. Requires filesystem/platform support; no Windows-specific bypass or best-effort fallback. |
| `ReplicatedLogStoreV1.failure(message, cause)` | Constructs IllegalStateException with cause; not logging, latching, or cleanup itself. |
| `ReplicatedLogStoreV1.closeSuppressed(closeable, failure)` | No-op for null; otherwise attempts close and appends Throwable as suppressed to original failure. Used by open cleanup; does not replace original cause. |

## Tests and Verification Boundaries

[ReplicatedLogStoreV1Test.java](../../modules/aether-replicated-log/src/test/java/io/aetherdb/replication/log/ReplicatedLogStoreV1Test.java)
contains seven tests: append/range/force/reopen, two golden fixtures, guarded
truncation/replacement, persist-before-reply hook, final short-tail repair versus
complete corruption, and different-node identity rejection. The hook test creates
a store reflectively with a raw active file to isolate forcing; it does not prove
normal identity/header publication. The other format suite contributes seven tests.

Missing dedicated cases include multi-segment rotation/truncation, directory-force
and close-force failures, partial multi-entry append I/O, empty unindexed active
segments, interior incomplete tails and the full discovery error matrix. Local
directory channel support determines whether normal-open tests can execute on this
Windows host; a passing codec/hook test is not substitute evidence for those paths.
Raft commitment, state-machine application and crash-atomic truncation remain
separate verification responsibilities.

Fresh full-module verification on 2026-10-09 ran 14 tests: **10 passed, four
failed, none skipped**. All four failures were AccessDeniedException from opening
a directory channel in syncDirectory during initial openIdentity publication.
They affect normal append/reopen, guarded truncation, tail repair, and node-identity
tests before those assertions are reached. The seven format tests, two golden
fixture tests and reflective force-hook test passed. This Windows result does
not verify successful normal-open storage behavior on a supporting filesystem.
