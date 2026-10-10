# Raft Vote and State-Slot Codec Functions

[Function index](FUNCTION-INDEX.md) | [Raft core](RAFT-CORE-FUNCTIONS.md) | [Replicated log formats](REPLICATION-FORMAT-FUNCTIONS.md)

Sources: [VoteCodecV1.java](../../modules/aether-raft-storage/src/main/java/io/aetherdb/raft/storage/VoteCodecV1.java),
[RaftPersistentState.java](../../modules/aether-raft-storage/src/main/java/io/aetherdb/raft/storage/RaftPersistentState.java),
and [RaftStateSlotCodecV1.java](../../modules/aether-raft-storage/src/main/java/io/aetherdb/raft/storage/RaftStateSlotCodecV1.java).
This reference covers **17 explicit declarations across all three module files**:
nine vote codec, one state record, and seven slot codec declarations. Generated
record accessors/equality are outside the count.

## Architecture and Missing Persistence Owner

```text
VoteRequest/Response <-> exact fixed-size vote payload + masked CRC32C
RaftPersistentState + identities + fingerprint <-> 512-byte slot + CRC + SHA
```

All functions here operate on values and byte arrays. Despite the module name,
there is **no file writer, slot alternation/selection manager, force barrier,
directory ownership, highest-valid-generation chooser, or vote-before-reply
ordering implementation**. No method reads two slots or restores an election loop.
The slot generation represents a caller-supplied value; its monotonicity is not
checked against a previous state. Valid encoding alone is not durable persistence.

Vote payloads are **big-endian**. State slots are **little-endian**, as are the
replicated-log formats. UUID halves are most-significant then least-significant,
each encoded in that buffer's byte order. All CRCs use MaskedCrc32c. State SHA-256
is unkeyed integrity metadata, not a signature or proof of node authenticity;
the decoder's comment about authentication does not establish a trust boundary.

## Vote Codec Functions

| Function | Behavior and boundaries |
| --- | --- |
| `VoteCodecV1.VoteCodecV1()` | Private empty utility constructor. |
| `VoteCodecV1.encodeRequest(value)` | Allocates 128 bytes; writes AEVR magic, version/header length, kind code, zero flags/reserved, term, candidate/session UUIDs, tip coordinates/state/hash, nonce/config version, and CRC over 0..123. Reads hash through defensive accessor. Does not validate membership/log tip or strengthen record validation. Null value/kind/UUID can raise NullPointerException. |
| `VoteCodecV1.decodeRequest(in)` | Requires exact size/magic/CRC, version=1/header=128 and zero flags/reserved. Reads fields and maps kind 1/2 to PRE_VOTE/REQUEST_VOTE, **all other kinds to null**, then constructs VoteRequest. That constructor accepts null kind, so a checksum-correct unknown kind is accepted. Term>0, nonce!=0 and 32-byte hash are checked by record; remaining coordinate/identity semantics are not. |
| `VoteCodecV1.encodeResponse(value)` | Allocates 96 bytes; writes AEVP/version/header, kind code, grant byte, VoteReason.ordinal as short, metadata, zero reserved long and CRC over 0..91. No responder authentication/correlation or coordinate checks. Null value/kind/reason/UUID may cause NullPointerException; a false/null-reason record can exist but cannot encode. |
| `VoteCodecV1.decodeResponse(in)` | Checks exact size/magic/CRC/version/header, grant byte<=1, reason ordinal within values length, and zero reserved long. Unknown kind maps to null and is accepted by VoteResponse. Constructor checks grant/reason consistency; term/index/config/nonce/UUID validity and request correlation are not enforced. |
| `VoteCodecV1.require(in, size, magic, crcOffset)` | Rejects null/wrong length, wrong big-endian magic, or CRC mismatch with invalid(). Checks integrity before public decoder field validation. Caller supplies known size/offset; not a general framing parser. |
| `VoteCodecV1.putUuid(b, id)` | Writes most/least-significant longs using caller buffer order; no null/nonzero identity validation. |
| `VoteCodecV1.getUuid(b)` | Reads UUID halves and constructs value; all-zero identity is not rejected here. |
| `VoteCodecV1.invalid()` | Returns IllegalArgumentException with message invalid Raft vote body. Does not log, retry, or change election state. |

CRC detects accidental changed bytes but cannot authenticate a sender. Payload
parsing does not check receiver/cluster membership, replay, election session,
candidate freshness or lease contact. Re-encoding a decoded unknown-kind record
fails when encode dereferences null kind rather than round-tripping the unknown code.
Reason codes use **Java enum ordinals**: reordering VoteReason changes wire meaning.

## Vote Wire Layouts

| Request offset | Bytes and meaning |
| --- | --- |
| 0..11 | AEVR magic (4), version=1 (2), length=128 (2), kind=1/2 (1), flags=0 (1), reserved=0 (2). |
| 12..51 | Candidate election term (8), candidate UUID (16), session UUID (16). |
| 52..75 | Last log index, last log term, last state sequence (three longs). |
| 76..123 | Last entry hash (32), nonce (8), configuration version (8). |
| 124..127 | Big-endian masked CRC32C over bytes 0..123. |

| Response offset | Bytes and meaning |
| --- | --- |
| 0..11 | AEVP magic (4), version=1 (2), length=96 (2), kind (1), granted=0/1 (1), reason ordinal (2). |
| 12..51 | Receiver term (8), responder UUID (16), session UUID (16). |
| 52..83 | Nonce, last log index, last log term, configuration version (four longs). |
| 84..91 | Reserved zero long, explicitly checked on decode. |
| 92..95 | Big-endian masked CRC32C over bytes 0..91. |

Current reason ordinals: GRANTED=0, STALE_TERM=1, ALREADY_VOTED=2,
LOG_NOT_UP_TO_DATE=3, RECENT_LEADER_CONTACT=4, NOT_VOTER=5,
CANDIDATE_NOT_MEMBER=6, CONFIGURATION_MISMATCH=7, LOG_HASH_MISMATCH=8,
NODE_NOT_ELIGIBLE=9, STORAGE_UNAVAILABLE=10, INVALID_REQUEST=11.
The codec does not encode AppendEntriesReason or provide AppendEntries payloads.

## Persistent State and Slot Functions

| Function | Behavior and boundaries |
| --- | --- |
| `RaftPersistentState.RaftPersistentState(generation, currentTerm, votedFor)` | Requires generation>0 and term>=0, otherwise IllegalArgumentException. Normalizes null Optional to Optional.empty. Rejects any present vote at term zero; positive term with empty vote is allowed. Does not reject all-zero candidate UUID at a positive term, compare prior generations/terms, or prevent a different vote in the same term. |
| `RaftStateSlotCodecV1.RaftStateSlotCodecV1()` | Private empty utility constructor. |
| `RaftStateSlotCodecV1.encode(cluster, node, state, reason, fingerprint, epochMillis)` | Requires fingerprint length=32 and reason in 1..5, otherwise IllegalArgumentException (null fingerprint raises NullPointerException). Allocates zero-filled 512 bytes, writes identity/state/presence/vote/time/reason/fingerprint, semantic hash, and CRC over 0..507. Does not reject zero IDs or negative epoch, validate prior state, choose a slot, write, or force. Null identity/state fails on dereference. |
| `RaftStateSlotCodecV1.decode(in, cluster, node, fingerprint)` | Null input raises NullPointerException; requires exact 512 bytes and CRC. Checks magic/version/length/flags and expected UUID equality, reads generation/term/presence/vote, skips epoch and reserved int, reads fingerprint/hash and constructs state. Rejects presence>1, reason outside 1..5, fingerprint mismatch and semantic-hash mismatch. Expected fingerprint length is not independently checked, but stored 32-byte equality requires a match. No slot selection or file I/O. |
| `RaftStateSlotCodecV1.hash(c, n, s, fp)` | SHA-256 of ASCII AETHER-RAFT-STATE-V1, a 57-byte little-endian identity/state block, 16-byte canonical vote UUID, then fingerprint. Identity block writes only 49 bytes (two UUIDs, generation, term, presence); remaining **eight zero bytes are also hashed** because the entire allocated array is fed to digest. Epoch/reason/physical reserved bytes are excluded. Missing SHA-256 raises IllegalStateException. |
| `RaftStateSlotCodecV1.put(b, u)` | Writes UUID halves in caller buffer order; no identity check. |
| `RaftStateSlotCodecV1.get(b)` | Reads UUID halves; no identity check. |
| `RaftStateSlotCodecV1.invalid()` | Constructs IllegalArgumentException with message invalid RAFT-STATE slot. Constructor state-validation errors can instead propagate their own messages. |

RaftPersistentState has immutable scalar/Optional/UUID fields, so generated record
equality is value-based without an array-identity caveat. decode returns only this
state: it discards the update reason/time rather than exposing a receipt. Reason
1..5 is a numeric range in this codec, not an enum or named update transition.

## Slot Bytes and Integrity Coverage

| Slot offset | Bytes and validation |
| --- | --- |
| 0..15 | AETHRFS1 (8), version=1 (2), length=512 (2), flags=0 (4); all checked. |
| 16..47 | Cluster UUID and node UUID; compared with expected values. Nonzero identities not independently required. |
| 48..64 | Generation (8), current term (8), vote-present byte (1). Record checks generation/term; decoder checks present<=1. |
| 65..71 | Seven zero bytes emitted, skipped without a zero check. |
| 72..87 | Vote UUID. With present=1 retained in state; with present=0 ignored and hash uses canonical all-zero vote instead. |
| 88..103 | Epoch millis (8), reason (4), reserved int (4). Epoch/reserved are read and ignored; reason must be 1..5. |
| 104..167 | Compatibility fingerprint (32), semantic state SHA-256 (32). |
| 168..507 | Zero tail emitted, not individually validated. |
| 508..511 | Little-endian masked CRC32C over bytes 0..507. |

All skipped/reserved/time bytes are still CRC-covered, but the parser does not
require canonical zeros or nonnegative epoch when a CRC is recomputed. The SHA
does not cover epoch/reason or physical padding. For absent votes it hashes a zero
UUID regardless of physical vote bytes. Thus changed ignored fields plus a correct
CRC can decode successfully without changing the state SHA. That is existing
acceptance behavior, not cryptographic authentication or strict canonical parsing.

The codec accepts caller-supplied expected identities/fingerprint as its trust
inputs. There is no compatibility-fingerprint generator in this module and no
comparison against a node's configured/durable identity outside those arguments.
Concurrent mutation of caller-owned byte inputs is not synchronized by the codec.

## Verification and Remaining Boundaries

[RaftStorageFormatTest.java](../../modules/aether-raft-storage/src/test/java/io/aetherdb/raft/storage/RaftStorageFormatTest.java)
contains six tests: exact-size vote round-trip; request, response and state-slot
golden fingerprints; state-slot changed-byte rejection; shared corruption-mutator
checks for request/slot. They verify selected byte images and integrity failures,
not persistence ordering, dual-slot repair, or every protocol/constructor condition.

Unknown kinds, recomputed-CRC ignored fields, reason-ordinal compatibility,
absent-vote canonicalization, identity/epoch/null cases and state monotonicity lack
dedicated coverage in that suite. A module named storage is not evidence that
the node persists a vote before replying. Full election/replication integration,
slot publication/selection and restart invariants remain caller/system work.
Runtime and frozen experiment sources remain unchanged by this documentation.
