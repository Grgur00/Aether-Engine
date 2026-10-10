# Raft Contracts and Progress Functions

[Function index](FUNCTION-INDEX.md) | [Vote and slot codecs](RAFT-STORAGE-FUNCTIONS.md) | [Replication contracts](REPLICATION-CONTRACT-FUNCTIONS.md) | [Log store](REPLICATED-STORE-FUNCTIONS.md) | [Configuration](CONFIG-LOADING-FUNCTIONS.md)

Sources: [aether-raft-api](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api)
and [aether-raft-core](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core).
This guide covers **25 explicit declarations across all 11 implementation files**:
four declarations in six API files and 21 in five core files. Enums without explicit
methods contribute files but no declarations. Generated accessors/enum helpers are
outside the count; relevant enum values and record behavior are explained below.

## Actual Architecture

```text
vote request/response values -> separate wire codec
candidate/local log tip -> LogFreshness.compare -> decision value
leader caller supplies follower replies -> FollowerProgress
caller supplies all voter durable matches + term lookup -> RaftCommitTracker
validated configuration -> timer values + snapshot/admission predicates
```

These are independent foundations. The core module does not contain a running
election state machine, vote-granting receiver, AppendEntries handler, heartbeat
scheduler, network transport, snapshot installer, or state-machine apply loop.
Role/reason enum names describe protocol intent, not an implementation of those
operations. No helper automatically wires the replicated-log store into consensus.

FollowerProgress and RaftCommitTracker are mutable, unsynchronized objects.
Their owners must serialize mutation/access and validate replies, membership,
term/session/nonce, and durable acknowledgment truth. Neither class persists
its state. A commit-index update is not an applied-state update or storage force.

## Vote Record Functions

Sources: [VoteRequest.java](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api/VoteRequest.java),
[VoteResponse.java](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api/VoteResponse.java),
[VoteKind.java](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api/VoteKind.java).

| Function | Behavior and boundaries |
| --- | --- |
| `VoteKind.VoteKind(code)` | Enum constructor stores public final code: PRE_VOTE=1, REQUEST_VOTE=2. No receiver or term transition occurs. There is no explicit fromCode method in this enum; wire decoding is separate. |
| `VoteRequest.VoteRequest(kind, term, candidateId, sessionId, lastLogIndex, lastLogTerm, lastStateSequence, lastEntryHash, nonce, configurationVersion)` | Clones hash first, so null hash raises NullPointerException. Then requires term>0, nonce!=0, and hash length=32, otherwise IllegalArgumentException. Negative nonzero nonce is accepted. Does not check kind/UUID nulls, zero UUIDs, nonnegative tip/state/config coordinates, paired empty-log markers, or whether hash matches a log. |
| `VoteRequest.lastEntryHash()` | Returns a fresh defensive clone of the owned 32-byte array. Does not authenticate the candidate or recompute a tip hash. |
| `VoteResponse.VoteResponse(kind, granted, reason, term, responderId, sessionId, nonce, lastLogIndex, lastLogTerm, configurationVersion)` | Only checks granted equals (reason==GRANTED). A true flag with another/null reason or false with GRANTED raises IllegalArgumentException. A false flag with null reason passes. Does not validate other fields, authenticate responder, correlate session/nonce, or change local election state. |

VoteRequest's generated record equality compares the hash array by identity,
not contents. Separate requests with identical hash bytes need not compare equal;
use explicit digest comparison when needed. Its immutable scalar/UUID fields have
generated accessors. VoteResponse has no array field and uses normal record value
equality. Constructor acceptance is narrower than full protocol validation.

## Role and Reason Vocabulary

Sources: [RaftRole.java](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api/RaftRole.java),
[VoteReason.java](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api/VoteReason.java),
[AppendEntriesReason.java](../../modules/aether-raft-api/src/main/java/io/aetherdb/raft/api/AppendEntriesReason.java).

| Vocabulary | Meaning without automatic behavior |
| --- | --- |
| FOLLOWER, PRE_CANDIDATE, CANDIDATE, LEADER | Accept replication/votes; probe quorum without advancing term; solicit binding votes; replicate/drive commit. RaftRole merely identifies these roles; it does not transition them. |
| PRE_VOTE, REQUEST_VOTE | Nonbinding availability probe versus binding election request. VoteKind does not itself persist term/vote or enforce the distinction. |
| GRANTED, STALE_TERM, ALREADY_VOTED | Accepted vote, behind term, prior vote for another candidate. VoteResponse only enforces the GRANTED flag relationship. |
| LOG_NOT_UP_TO_DATE, LOG_HASH_MISMATCH, RECENT_LEADER_CONTACT | Tip ordering/hash conflict or recent leader lease contact. No lease timer or voting rule is implemented by VoteReason. |
| NOT_VOTER, CANDIDATE_NOT_MEMBER, CONFIGURATION_MISMATCH | Membership/voter/configuration rejection reasons. No membership lookup is performed by the enum. |
| NODE_NOT_ELIGIBLE, STORAGE_UNAVAILABLE, INVALID_REQUEST | Eligibility, persistence, or protocol rejection labels. They do not catch or convert failures automatically. |
| MATCHED, STALE_TERM, NOT_MEMBER, CONFIGURATION_MISMATCH | AppendEntries acceptance or term/membership/configuration rejection labels. No AppendEntries receiver is supplied here. |
| LOG_TOO_SHORT, TERM_MISMATCH, LOG_HASH_MISMATCH, STATE_SEQUENCE_MISMATCH | Prefix/coordinate/hash/sequence disagreement reasons. Store and receiver integration must establish which applies. |
| COMMITTED_CONFLICT, APPLIED_CONFLICT | Protected progress conflict; enum does not maintain or enforce those boundaries. |
| LOG_STORAGE_UNAVAILABLE, FOLLOWER_BUSY, INVALID_REQUEST, SNAPSHOT_REQUIRED | Storage/admission/protocol/snapshot-repair outcomes. No retry, admission, or snapshot installation is implemented by the label. |

Except VoteKind's explicit codes, these enums have no explicit persistent numeric
mapping. Do not infer a stable wire code from a source listing without reviewing
the codec. Enum availability does not imply that each reason has an emitting path.

## Freshness and Quorum Functions

Sources: [LogFreshness.java](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core/LogFreshness.java),
[RaftQuorum.java](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core/RaftQuorum.java).

| Function | Behavior and boundaries |
| --- | --- |
| `LogFreshness.LogFreshness()` | Private empty utility constructor. |
| `LogFreshness.compare(candidateTerm, candidateIndex, candidateHash, localTerm, localIndex, localHash)` | Compares last-log term first, then index only when terms equal. Greater returns UP_TO_DATE, smaller STALE. Only when both coordinates equal calls MessageDigest.isEqual: matching hashes return UP_TO_DATE, different return DIVERGED. Does not validate coordinate sign, paired empty markers or hash length. Unequal coordinates bypass hash comparison entirely. No disk lookup or vote grant occurs. |
| `RaftQuorum.RaftQuorum()` | Private empty utility constructor. |
| `RaftQuorum.required(voters)` | Requires voters>0, otherwise IllegalArgumentException; returns voters/2+1 using integer division. Strict majority: 1->1, 2->2, 3->2, 4->3, 5->3. No identities, deduplication, joint membership or connectivity checks. |

LogFreshness.Decision values are UP_TO_DATE, STALE, DIVERGED. A higher-term
candidate can be UP_TO_DATE even with a shorter index or a different hash; only
equal-coordinate hashes are compared. The helper compares supplied tips, not
entire logs or election terms. A caller still decides eligibility and whether
to grant a vote based on configuration, prior vote, contact lease and persistence.

## Follower Progress Functions

Source: [FollowerProgress.java](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core/FollowerProgress.java).

| Function | Behavior and boundaries |
| --- | --- |
| `FollowerProgress.FollowerProgress(leaderLastIndex)` | Sets nextIndex=Math.addExact(leaderLastIndex,1); matchIndex defaults 0, mode PROBE. Long.MAX_VALUE raises ArithmeticException. Negative leaderLastIndex is not rejected, so invalid next indexes can be constructed. Does not query leader log. |
| `FollowerProgress.matched(durableIndex)` | Ignores input below current match. Otherwise assigns matchIndex, calculates nextIndex=durableIndex+1 with addExact, then selects REPLICATE. Equal match is accepted and can reset mode. MAX_VALUE overflows after assigning matchIndex, leaving old nextIndex/mode: mutation is not exception-atomic. Does not verify reply, voter identity, leader term, or actual force. |
| `FollowerProgress.rejected(suggestedNextIndex)` | Sets nextIndex=max(matchIndex+1, suggestion) and selects PROBE. Normal inputs cannot move below known match+1, but can move forward and are not bounded by leader tip. Addition is unchecked; a MAX_VALUE match left by failed matched can wrap. Does not decrease matchIndex or interpret rejection reason. |
| `FollowerProgress.snapshotRequired()` | Sets mode SNAPSHOT_REQUIRED without resetting indexes or installing a snapshot. Later matched/rejected can change mode again; this is not terminal state. |
| `FollowerProgress.nextIndex()` | Returns current next index without locking or validation. |
| `FollowerProgress.matchIndex()` | Returns greatest accepted match under ordinary successful calls; no durable persistence. |
| `FollowerProgress.mode()` | Returns PROBE, REPLICATE or SNAPSHOT_REQUIRED. Does not send work or enforce strategy. |

Mode intent: PROBE searches for a matching prefix, REPLICATE permits pipelining
after match, SNAPSHOT_REQUIRED requests nonincremental repair. These are strategy
labels; there is no in-flight window, retry timeout, conflict-term search, session
correlation, or transfer implementation in this class.

## Commit Tracking Functions

Source: [RaftCommitTracker.java](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core/RaftCommitTracker.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RaftCommitTracker.RaftCommitTracker()` | Empty constructor; commitIndex starts at 0. No restore constructor or persistent election/commit state is read. |
| `RaftCommitTracker.commitIndex()` | Returns current in-memory commit index; no synchronization or application check. |
| `RaftCommitTracker.recalculate(durableMatches, currentTerm, termAtIndex)` | Empty collection returns prior commit without using lookup. Otherwise copies and sorts descending, takes strict-majority element using collection size, and advances only if candidate>commit and its looked-up term equals currentTerm. Updates commit before persist-independent RAFT_COMMIT_AFTER_MAJORITY_BEFORE_APPLY hook; hook exception leaves advancement in place. Lookup exception before assignment leaves prior commit. No automatic apply/storage force. |
| `RaftCommitTracker.commitContext(commitIndex, currentTerm, voters)` | Builds CrashContext with unsigned commit_index/term strings and decimal voters count. Diagnostic context only, not a stored receipt or quorum certificate. |

For matches [8,8,4], majority position is the second value, 8. If termAtIndex(8)
returns 2 while currentTerm=3, no advancement; if it returns 3, commit becomes 8.
The helper does not search lower candidates when that selected candidate has a
different term. Once committed, it never decreases commit on later smaller matches.

The caller must supply **one match for every voter, including appropriate local
durable progress**, not merely responders: collection size is the quorum's voter
count. Omitting unavailable voters shrinks the computed majority. Duplicate values
are legitimate for different voters but duplicate acknowledgments for the same
voter are not deduplicated. There are no IDs in this API to enforce that distinction.
Null collections/elements, invalid terms/indexes, or lookup behavior are not
prevalidated; ordinary collection/lookup errors propagate. This is a single-set
majority helper, not joint-consensus quorum intersection or membership management.

## Runtime Configuration Functions

Source: [RaftRuntimeConfiguration.java](../../modules/aether-raft-core/src/main/java/io/aetherdb/raft/core/RaftRuntimeConfiguration.java).

| Function | Behavior and boundaries |
| --- | --- |
| `RaftRuntimeConfiguration.RaftRuntimeConfiguration(electionTimeoutMin, electionTimeoutMax, heartbeatInterval, snapshotThresholdEntries, maxUncommittedBytes)` | Requires nonnull durations (NullPointerException), min/heartbeat positive, max>=min, min strictly greater than 2*heartbeat, threshold/byte cap positive (IllegalArgumentException). Duration multiplication/subtraction may raise ArithmeticException for extreme inputs. Direct construction does not apply registry numeric upper/lower bounds. |
| `RaftRuntimeConfiguration.defaults()` | Creates AetherConfiguration with development security profile, then calls from to resolve registry defaults and validate. Does not start timers, choose random election delay, or create a Raft node. |
| `RaftRuntimeConfiguration.from(configuration)` | Requires nonnull config, runs default AetherConfigValidator over it, resolves five settings through registry defaults, converts three millisecond longs to Duration, then invokes constructor. Broader config validation can reject fields unrelated to Raft. No dynamic reload/listener is installed. |
| `RaftRuntimeConfiguration.shouldSnapshot(committedEntriesSinceSnapshot)` | Returns input>=snapshotThresholdEntries. Negative input simply returns false; no overflow/accounting/storage checks or actual snapshot execution. |
| `RaftRuntimeConfiguration.admitsUncommittedBytes(currentUncommittedBytes, additionalBytes)` | Rejects negative inputs, then tests current<=max-additional, avoiding addition overflow. Equality admits exactly cap; additions above cap reject. Pure predicate, no reservation/account update and no protection from concurrent callers all admitting against same count. |
| `RaftRuntimeConfiguration.longValue(configuration, name)` | Looks up required registry descriptor, gets explicit/default string, parses signed long. Missing descriptor/parser errors propagate; public from validates first. |

Current registry defaults: election min=300 ms, max=600 ms, heartbeat=100 ms,
snapshot threshold=100,000 entries, uncommitted cap=256 MiB. Registry limits:
election endpoints 50..600,000 ms; heartbeat 10..60,000 ms; snapshot count
1..Long.MAX_VALUE; byte cap 1 MiB..8 GiB. Values are resolved inputs, not evidence
of an active scheduler or snapshot service. Cross-check the
[registry](../../modules/aether-config/src/main/java/io/aetherdb/config/AetherConfigRegistry.java)
when changing configuration documentation.

## Tests and Remaining Implementation

[RaftCoreTest.java](../../modules/aether-raft-core/src/test/java/io/aetherdb/raft/core/RaftCoreTest.java)
has four tests: majority/current-term gating, ignored regressing follower match,
configured timer/threshold/admission values, and commit advancement before crash
hook failure. API has no dedicated test source tree. These tests do not cover
vote constructor gaps, freshness/hash comparison, rejection/snapshot transitions,
overflow partial mutation, omitted/duplicate voters, joint consensus or elections.

Vote codecs and persistent-state slots are the next reference. Persistent format
availability will still not imply a durable-state manager or complete election
loop. Runtime and frozen experiment implementations are unchanged by these docs.
