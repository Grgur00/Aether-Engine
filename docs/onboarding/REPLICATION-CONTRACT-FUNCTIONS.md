# Replication Contracts and Applied-State Functions

[Function index](FUNCTION-INDEX.md) | [Persisted formats](REPLICATION-FORMAT-FUNCTIONS.md) | [Module guide](MODULE-GUIDE.md) | [RPC contracts](RPC-API-FUNCTIONS.md)

This reference covers **34 explicit declarations across eight files**: all six
implementation files in aether-replication-api (28 declarations), AppliedState
(three), and StateSequencePlanner (three). Generated record accessors/equality
and enum helpers are outside this declaration count; their relevant behavior is
explained below. Persisted codecs, the concrete log store, and Raft algorithms
are separate implementation boundaries, not completed by this reference.

## Architecture and Coordinates

```text
caller supplies previous logical sequence + mutation count
  -> StateSequencePlanner -> inclusive StateSequenceRange
command encoding + entry codec -> ReplicatedLogEntry
  -> ReplicatedLogStore contract -> concrete local log implementation
state-machine integration applies committed commands -> AppliedState value
```

This is a responsibility map, not an automatically connected runtime pipeline.
The planner does not reserve sequences, the entry record does not authenticate
its hashes, and the applied-state record neither executes commands nor publishes
itself. Consensus commitment, storage force, and application progress are distinct.

| Coordinate | Meaning and owner |
| --- | --- |
| Log index | Position of an entry, including non-mutating entries. A nonempty LogPosition or ReplicatedLogEntry uses a positive index. |
| Term | Election/log term associated with an index. Not an MVCC sequence or a timestamp. |
| State sequence | Logical mutation coordinate. A command can consume several sequences; NOOP/BARRIER consume no command range. Cross-entry continuity needs a caller/store check. |
| Durable index | Store-reported force boundary. It is not automatically the consensus commit index or last applied index. |
| Applied index | Last applied log coordinate represented by AppliedState. Its constructor cannot establish that commands were actually applied. |

For example, plan(10, 3) returns sequences 11 through 13 for three mutations in
one command. That command occupies one log index, not three. A following NOOP
can occupy the next index while carrying stateSequenceAfter=13 and sequenceStart=0.
Records alone do not compare that NOOP against its predecessor.

## Position, Identity, and Entry Kind Functions

Sources: [LogPosition.java](../../modules/aether-replication-api/src/main/java/io/aetherdb/replication/api/LogPosition.java),
[ReplicatedLogStoreIdentity.java](../../modules/aether-replication-api/src/main/java/io/aetherdb/replication/api/ReplicatedLogStoreIdentity.java),
[ReplicatedEntryType.java](../../modules/aether-replication-api/src/main/java/io/aetherdb/replication/api/ReplicatedEntryType.java).

| Function | Behavior and boundaries |
| --- | --- |
| `LogPosition.LogPosition(index, term)` | Requires nonnegative coordinates and either both zero or both positive. Rejects mixed empty markers and negatives with IllegalArgumentException. Accepts 0/0 for the empty log. Does not compare positions, enforce term monotonicity, or query a log. |
| `ReplicatedLogStoreIdentity.ReplicatedLogStoreIdentity(clusterId, nodeId)` | Rejects null or all-zero UUIDs with IllegalArgumentException. UUIDs are immutable, so no defensive byte copying is needed. A binding value is not certificate authentication, directory ownership, or validation against a persisted identity file. |
| `ReplicatedEntryType.ReplicatedEntryType(code)` | Enum constructor stores the supplied stable integer for each declared constant. It performs no additional validation; callers cannot freely instantiate enum values. |
| `ReplicatedEntryType.code()` | Returns the persistent code: NOOP=1, COMMAND=2, CONFIGURATION=3, BARRIER=4. These are explicit codes, not Java enum ordinals. |
| `ReplicatedEntryType.fromCode(code)` | Scans the enum values for an exact code match. Unknown codes raise IllegalArgumentException containing the supplied code; no fallback or future-type passthrough. |

NOOP denotes leader-term commitment without mutation; COMMAND denotes an atomic
state-machine command; CONFIGURATION denotes membership configuration; BARRIER
denotes coordination without user mutation. These names describe entry intent,
not execution implemented by this enum.

## Decoded Entry Functions

Source: [ReplicatedLogEntry.java](../../modules/aether-replication-api/src/main/java/io/aetherdb/replication/api/ReplicatedLogEntry.java).

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogEntry.ReplicatedLogEntry(type, payloadFormatVersion, index, term, commandId, sequenceStart, stateSequenceAfter, previousEntryHash, entryHash, payload)` | Validates the general and entry-kind rules below, then copies both 32-byte hashes and the entire payload. Invalid metadata raises IllegalArgumentException before copying. Does not calculate a hash, verify a predecessor, decode the command, or establish continuity with another entry. |
| `ReplicatedLogEntry.previousEntryHash()` | Returns a fresh clone. Mutating the returned array cannot alter the stored predecessor hash. Reading this field does not verify a chain. |
| `ReplicatedLogEntry.entryHash()` | Returns a fresh clone of the supplied entry hash, without recomputing it. |
| `ReplicatedLogEntry.payload()` | Returns a fresh clone of the exact supplied bytes, including any malformed command body that passed this record's metadata checks. Cost scales with payload length on each call. |
| `ReplicatedLogEntry.equals(other)` | Identity short-circuit, then checks record type, every scalar/UUID field, and all three arrays by contents. Null or another type returns false. Equality is metadata/payload equality, not proof of validity or equivalent applied state. |
| `ReplicatedLogEntry.hashCode()` | Hashes scalar/UUID metadata using Objects.hash, then folds in each array's Arrays.hashCode with multiplier 31. Consistent with content-based equals; this Java collection hash is not the persisted cryptographic entryHash. |

General constructor rules: nonnull type and command ID; payloadFormatVersion>=0;
index and term>=1; sequenceStart and stateSequenceAfter>=0; nonnull hashes exactly
32 bytes; nonnull payload no longer than **67,108,664 bytes**. No required
relationship between the two sequence fields exists for every entry kind.

| Kind | Additional record validation |
| --- | --- |
| COMMAND | Nonzero command UUID, payload format exactly 1, sequenceStart>=1, stateSequenceAfter>=sequenceStart, and nonempty payload. Does not check that the payload's operation count matches the implied range. |
| NOOP and BARRIER | Zero command UUID, empty payload, format 0, sequenceStart=0. stateSequenceAfter remains any nonnegative value at this boundary. |
| CONFIGURATION | Zero command UUID. No extra payload-format, emptiness, or sequenceStart restriction beyond the general rules. Configuration-body semantics need another layer. |

Scalar generated accessors return immutable values. The three explicit array
accessors replace generated accessors to enforce defensive ownership. Construction
plus repeated access can create several payload copies; immutability does not
mean a borrowed zero-copy view.

## Log Store Contract Functions

Source: [ReplicatedLogStore.java](../../modules/aether-replication-api/src/main/java/io/aetherdb/replication/api/ReplicatedLogStore.java).
Every method in this table is **abstract**. The interface declares intent; it
contains no locking, bounds checking, I/O, exception conversion, recovery, or
thread-safety implementation. Read concrete behavior in
[ReplicatedLogStoreV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedLogStoreV1.java)
before relying on empty-range handling, batch atomicity, or failure recovery.

| Function | Declared responsibility and limits |
| --- | --- |
| `ReplicatedLogStore.identity()` | Returns the immutable cluster/node binding. Does not prescribe authentication or when disk identity is checked. |
| `ReplicatedLogStore.firstIndex()` | Returns first retained index, or zero for empty. Retention and prefix-removal policy are not defined here. |
| `ReplicatedLogStore.lastIndex()` | Returns final retained index, or zero for empty. Append visibility is not force or commitment. |
| `ReplicatedLogStore.lastTerm()` | Returns final retained term, or zero for empty. Does not promise a current election-term register. |
| `ReplicatedLogStore.lastStateSequence()` | Returns final assigned logical mutation sequence. It is not an application-progress accessor. |
| `ReplicatedLogStore.durableIndex()` | Returns greatest index known forced to stable storage. Does not imply quorum replication or state-machine application. |
| `ReplicatedLogStore.append(entries)` | Appends a validated contiguous sequence without forcing. The interface does not implement validation, define partial-failure rollback, or copy the list. |
| `ReplicatedLogStore.appendAndForce(entries)` | Appends and forces a validated contiguous sequence. Concrete implementation determines the I/O and failure boundary. |
| `ReplicatedLogStore.forceThrough(index)` | Forces bytes through an already appended index. Not an append request; bounds and no-op behavior belong to the implementation. |
| `ReplicatedLogStore.read(index)` | Reads one retained entry. Missing-index errors and closed-store behavior are not specified by a method body here. |
| `ReplicatedLogStore.readRange(startInclusive, endExclusive, byteLimit, entryLimit)` | Declares a half-open, bounded range and at least one fitting entry when available. Byte accounting, oversized-first-entry handling, validation, and returned-list ownership need the concrete implementation. |
| `ReplicatedLogStore.termAt(index)` | Looks up the term at a retained index; does not specify missing-index handling. |
| `ReplicatedLogStore.entryHashAt(index)` | Declares a defensive hash result at a retained index. The interface itself cannot enforce copying or integrity. |
| `ReplicatedLogStore.truncateSuffix(fromIndex, commitIndex, appliedIndex)` | Declares removal of an uncommitted/unapplied suffix beginning at a retained index. Caller supplies progress boundaries; this signature cannot verify their truth or establish consensus permission. |
| `ReplicatedLogStore.close()` | Declares forcing and release of lock/channels. Overrides AutoCloseable without checked throws. Idempotency, force failure, resource cleanup and concurrent-close behavior require implementation review. |

None of these signatures contains an automatic RPC call, retry strategy,
election algorithm, or write to the local engine WAL. The replicated log and
the engine WAL have different roles and formats.

## Sequence Range and Planner Functions

Sources: [StateSequenceRange.java](../../modules/aether-replication-api/src/main/java/io/aetherdb/replication/api/StateSequenceRange.java),
[StateSequencePlanner.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/StateSequencePlanner.java).

| Function | Behavior and boundaries |
| --- | --- |
| `StateSequenceRange.StateSequenceRange(first, last)` | Requires first>0 and last>=first, otherwise IllegalArgumentException. Range is inclusive and nonempty. No 10,000-operation cap is imposed by the record itself. |
| `StateSequenceRange.operationCount()` | Converts last-first+1 to int with Math.toIntExact. Oversized valid ranges raise ArithmeticException. Positive ordered endpoints prevent overflow of the inclusive long calculation. |
| `StateSequencePlanner.StateSequencePlanner()` | Private empty constructor prevents ordinary utility-class instantiation; no state or reservation service exists. |
| `StateSequencePlanner.plan(previousStateSequence, operationCount)` | Requires previous>=0 and count in 1..10,000, otherwise IllegalArgumentException. Uses Math.addExact for previous+1 and previous+count, raising ArithmeticException on long overflow. Returns the inclusive next range. Pure calculation: repeated calls with the same input return the same range rather than reserving unique allocations. |
| `StateSequencePlanner.validate(previousStateSequence, operationCount, proposed)` | Calculates plan and compares using StateSequenceRange value equality. A different or null proposed range raises IllegalArgumentException for noncontiguity. Invalid input and arithmetic overflow propagate from plan; does not alter the proposal or any log. |

The caller must serialize allocation against the authoritative prior sequence.
Calling plan concurrently is not a safe sequence allocator. validate checks one
proposed range against supplied coordinates, not whether those coordinates match
durable, committed, or applied state.

## Applied-State Functions

Source: [AppliedState.java](../../modules/aether-state-machine/src/main/java/io/aetherdb/replication/state/AppliedState.java).

| Function | Behavior and boundaries |
| --- | --- |
| `AppliedState.AppliedState(index, term, stateSequence, entryHash, appliedHash)` | Requires all coordinates nonnegative and both hashes nonnull/exactly 32 bytes, otherwise IllegalArgumentException. Clones both inputs. Unlike LogPosition it accepts a zero index with positive term or the reverse. No prior state is supplied, so it cannot enforce monotonicity. Does not compute either hash, apply a command, persist progress, or atomically publish a value. |
| `AppliedState.entryHash()` | Returns a fresh defensive clone of the supplied applied-entry hash. Does not query or verify the log at index. |
| `AppliedState.appliedHash()` | Returns a fresh defensive clone of the supplied resulting-state hash. Does not recompute a state digest. |

The class Javadoc describes atomically published deterministic progress and the
constructor comment says monotonic coordinates. Those are intended integration
properties, not operations performed here: the body only checks a single value.
An owner must provide synchronization/publication, prior-state comparison,
deterministic application, and any durable progress protocol.

Generated record equality compares its array fields by array identity, unlike
ReplicatedLogEntry's explicit content equality. Two separately constructed
AppliedState values with identical hash bytes need not compare equal. Defensive
accessors also mean comparing returned arrays with == is not a digest comparison;
use content comparison for that purpose. Runtime behavior is unchanged here.

## Verification and Remaining Boundaries

[ReplicationFormatTest.java](../../modules/aether-replicated-log/src/test/java/io/aetherdb/replication/log/ReplicationFormatTest.java)
contains seven tests for identity/header/entry codec round trips and corruption,
command mutation order/ranges, and follower sequence discontinuity. These provide
some planner/entry integration evidence, not exhaustive constructor coverage.
The API and state-machine modules currently have no dedicated test source trees.
In particular, AppliedState ownership/equality, planner overflow/null proposals,
and the complete kind-specific entry validation matrix lack dedicated tests in
that selected suite. Documentation inventory checks verify declaration presence,
not semantic correctness or an integrated production cluster.

Next implementation boundaries are command and entry codecs, identity/segment
formats, concrete append/force/recovery/truncation, and then Raft commitment and
application integration. None is certified by constructing these records.
