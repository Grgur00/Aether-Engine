# Cluster Membership and Quorum Functions

[Function index](FUNCTION-INDEX.md) | [Cluster codecs](CLUSTER-CODEC-FUNCTIONS.md) | [Raft progress](RAFT-CORE-FUNCTIONS.md) | [Replication contracts](REPLICATION-CONTRACT-FUNCTIONS.md) | [Security](SECURITY-FUNCTIONS.md)

Sources: [cluster API](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api)
and [cluster core](../../modules/aether-cluster-core/src/main/java/io/aetherdb/cluster/core).
This reference covers **73 explicit declarations across all 12 API/core files**:
68 in the API and five in DualMajority. Generated record accessors and enum
helpers are outside that count; their ownership and equality rules are included.

## Architectural Boundaries

```text
cluster/bootstrap identity + node identity
    -> member values with advertised endpoints
    -> stable configuration (sorted members, voter set)
    -> joint configuration (old stable + target stable)
    -> caller supplies acknowledgments or durable indexes
    -> independent old/new quorum calculations
```

These modules contain values and calculations, not node discovery, a join service,
configuration-log publication, membership-change authorization, catch-up transport,
an election loop, or state-machine application. Wire codecs live separately in
aether-cluster-codec. Constructors accept supplied hashes; they do not recompute
them, authenticate the sender, or verify a real log/history. A TLS scheme value
does not open or secure a connection.

Stable configurations expose immutable lists/sets and defensive hash copies.
JointConfigurationV1 retains the old StableConfiguration interface object rather
than copying it: a custom mutable implementation can violate the immutability
contract. Callers must establish membership validity and trustworthy durable
acknowledgments before using DualMajority. Its return value is not a disk force,
commit publication, or applied-state update.

## Configuration Interfaces

Sources: [ClusterConfiguration.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/ClusterConfiguration.java),
[StableConfiguration.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/StableConfiguration.java),
[JointConfiguration.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/JointConfiguration.java).

| Function | Contract and boundary |
| --- | --- |
| `ClusterConfiguration.clusterId()` | Abstract owning-cluster UUID accessor; interface performs no validation. |
| `ClusterConfiguration.stateVersion()` | Abstract configuration-state version; monotonicity is an intended contract, not an interface implementation. |
| `ClusterConfiguration.voters()` | Abstract immutable participant set. Joint participants are a union, not permission to use one union-majority threshold. |
| `ClusterConfiguration.hash()` | Abstract defensive canonical-hash accessor; no hashing algorithm or validation here. |
| `StableConfiguration.configurationId()` | Abstract stable-configuration identity. |
| `StableConfiguration.previousHash()` | Abstract preceding-configuration hash accessor; does not verify chain continuity. |
| `StableConfiguration.members()` | Abstract immutable member-list accessor. |
| `StableConfiguration.creationEpochMillis()` | Abstract epoch-millisecond timestamp accessor, not a clock or ordering check. |
| `JointConfiguration.transitionId()` | Abstract transition identity. |
| `JointConfiguration.oldConfiguration()` | Abstract outgoing StableConfiguration accessor. |
| `JointConfiguration.targetConfiguration()` | Abstract incoming StableConfigurationV2 accessor. |
| `JointConfiguration.proposerNodeId()` | Abstract proposer identity; does not authorize or check membership. |
| `JointConfiguration.creationEpochMillis()` | Abstract transition timestamp accessor. |
| `JointConfiguration.voters()` | Default method copies old voters into HashSet, adds target voters, returns Set.copyOf. Rebuilds an immutable union on each call. Does not calculate independent majorities. Invalid custom implementations can cause null failures. |

## Endpoint Functions

Source: [ClusterEndpoint.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/ClusterEndpoint.java).

| Function | Behavior and validation |
| --- | --- |
| `ClusterEndpoint.Scheme.Scheme(c)` | Stores wire code: AETHER_TLS=1, AETHER_TCP_DEVELOPMENT=2. These are labels, not transport implementations. |
| `ClusterEndpoint.Scheme.code()` | Returns stored scheme code. |
| `ClusterEndpoint.AddressType.AddressType(c)` | Stores address code: IPV4=1, IPV6=2, DNS=3. |
| `ClusterEndpoint.AddressType.code()` | Returns stored representation code. |
| `ClusterEndpoint.of(scheme, host, port, priority, flags)` | Rejects null/blank host and exact wildcard strings 0.0.0.0, ::, *. Strips enclosing brackets; colon-containing candidates use InetAddress and IPV6; four decimal components use explicit IPv4 parsing, rejecting components above 255. Otherwise uses IDN.toASCII with STD3 rules, Locale.ROOT lowercase, strips trailing dots, and emits US-ASCII DNS bytes. Delegates remaining validation to constructor. Does not trim input or check reachability. Invalid IP lookup becomes IllegalArgumentException; oversized decimal components can raise NumberFormatException. |
| `ClusterEndpoint.ClusterEndpoint(scheme, type, address, port, priority, flags)` | Requires nonnull fields; port 1..65535, priority 0..65535; nonzero flags restricted to INTERNAL_RPC=1 and CLIENT_RPC=2. Requires IPv4 length 4, IPv6 length 16, DNS length 1..255. Clones address. Binary DNS content is not checked for ASCII, lowercase, grammar or canonical form; all-zero IP addresses are not rejected here. |
| `ClusterEndpoint.scheme()` | Returns stored scheme enum. |
| `ClusterEndpoint.addressType()` | Returns stored address representation enum. |
| `ClusterEndpoint.address()` | Returns a fresh address-byte clone. |
| `ClusterEndpoint.port()` | Returns stored port. |
| `ClusterEndpoint.priority()` | Returns stored sorting priority; smaller sorts first. |
| `ClusterEndpoint.flags()` | Returns traffic-class bitmask. |
| `ClusterEndpoint.compareTo(other)` | Delegates to COMPARATOR: priority, scheme code, address-type code, unsigned address bytes, port, then flags. Address comparison obtains defensive clones. Null comparison is not supported. |
| `ClusterEndpoint.equals(o)` | True only for an endpoint whose full comparator result is zero. Same host/port with different priority, scheme or flags is not equal. |
| `ClusterEndpoint.hashCode()` | Combines both enums, address content, port, priority and flags consistently with full-field equality. |

Wildcard rejection precedes bracket stripping: `[::]` and expanded zero IPv6
spellings are not rejected by the exact-string guard. Do not treat of as complete
advertisability/security validation. An IPv4-mapped IPv6 spelling may yield four
bytes from InetAddress and fail the constructor's IPV6 length check. Comparator
ordering is canonical value ordering, not route selection or connection fallback.

## Identity and Member Functions

Sources: [ClusterIdentity.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/ClusterIdentity.java),
[NodeIdentity.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/NodeIdentity.java),
[ClusterMember.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/ClusterMember.java),
[MemberRole.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/MemberRole.java).

| Function | Behavior and boundary |
| --- | --- |
| `ClusterIdentity.ClusterIdentity(clusterId, creationEpochMillis, bootstrapConfigurationId, bootstrapConfigurationHash, compatibilityFingerprint)` | Requires nonnull/nonzero cluster and bootstrap UUIDs, nonnegative timestamp, and two nonnull 32-byte arrays. Clones both arrays. Does not validate bootstrap contents, compute hashes or persist trust anchors. Invalid fields raise IllegalArgumentException. |
| `ClusterIdentity.bootstrapConfigurationHash()` | Returns defensive bootstrap-hash clone. |
| `ClusterIdentity.compatibilityFingerprint()` | Returns defensive format-fingerprint clone; does not compare against the local catalog. |
| `NodeIdentity.NodeIdentity(clusterId, nodeId, creationEpochMillis, initialRole, generation)` | Requires nonnull/nonzero UUIDs, timestamp>=0, nonnull role, and generation exactly 1. Invalid input raises IllegalArgumentException. No certificate, disk identity or current-membership check. |
| `MemberRole.MemberRole(code)` | Stores VOTER=1 or STAGED_NONVOTER=2. Role describes membership intent; no replication/election behavior is activated. |
| `MemberRole.code()` | Returns durable role code. |
| `MemberRole.fromCode(code)` | Scans enum values for exact code match; unknown code raises IllegalArgumentException. |
| `ClusterMember.ClusterMember(nodeId, role, flags, generation, addedAtIndex, addedAtTerm, name, identityHash, endpoints)` | Requires nonzero/nonnull node UUID and role; generation/index/term>=1; nonnull name at most 128 UTF-8 bytes; nonnull 32-byte identity hash, cloned. Sorts endpoint stream into unmodifiable list; null list becomes empty and is rejected; requires 1..8 and rejects adjacent full-value duplicates. Empty/blank name is accepted. Member flags are not validated; history/hash are not checked against identity or log. Null list elements are not uniformly rejected by a dedicated guard and may cause NullPointerException. |
| `ClusterMember.identityHash()` | Returns defensive clone, without recomputing the node identity hash. |

Generated scalar/UUID/enum accessors return immutable values. ClusterMember's
generated endpoints accessor returns its stored unmodifiable list of immutable
endpoint objects. ClusterIdentity and ClusterMember generated record equality
uses array identity, not digest contents, despite their defensive copying.
Separately constructed byte-identical records need not compare equal. NodeIdentity
has no arrays and normal record value equality. Positive member history does not
prove the member was added by a committed entry; initial NodeIdentity generation
is stricter than ClusterMember's any-positive generation.

## Stable Configuration V1 Functions

Source: [StableConfigurationV1.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/StableConfigurationV1.java).

| Function | Behavior and boundary |
| --- | --- |
| `StableConfigurationV1.StableConfigurationV1(clusterId, version, configurationId, previousHash, members, created, hash)` | Requires nonnull/nonzero cluster/configuration UUIDs, version>=1, created>=0, and two nonnull 32-byte hashes. Sorts members by unsigned UUID; rejects duplicate node UUIDs and full endpoint values across nodes. Requires 1..31 voters and at most 64 staged members. Stores immutable sorted members and unmodifiable voter-only UUID set; clones both hashes. Null member list/elements can raise NullPointerException. Does not compute hash, validate prior state/version progression, authenticate identities or check history against a log. |
| `StableConfigurationV1.compareUuid(a, b)` | Compares unsigned most-significant 64 bits, then unsigned least-significant 64 bits. Not UUID's signed-long natural ordering. Null arguments are unsupported. |
| `StableConfigurationV1.clusterId()` | Returns owning UUID. |
| `StableConfigurationV1.stateVersion()` | Returns stored positive version; no monotonic tracking. |
| `StableConfigurationV1.configurationId()` | Returns stable identity. |
| `StableConfigurationV1.previousHash()` | Returns fresh preceding-hash clone. |
| `StableConfigurationV1.members()` | Returns stored immutable UUID-sorted list, including staged nonvoters. |
| `StableConfigurationV1.creationEpochMillis()` | Returns stored nonnegative timestamp. |
| `StableConfigurationV1.voters()` | Returns stored immutable voter-only set; staged members do not count. Set iteration order is not the canonical member order. |
| `StableConfigurationV1.hash()` | Returns fresh configuration-hash clone. |

Cross-node endpoint uniqueness uses full endpoint equality, not address/port alone;
different flags/priority can leave the same network destination accepted. Voter
set construction filters each ID by scanning the member list, rather than doing
a single-pass role partition. Configuration classes do not override equals or
hashCode: they retain object-identity equality, not hash/content equality.

## Stable Configuration V2 Functions

Source: [StableConfigurationV2.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/StableConfigurationV2.java).

| Function | Behavior and boundary |
| --- | --- |
| `StableConfigurationV2.StableConfigurationV2(clusterId, version, configurationId, previousHash, completedTransitionId, members, created, changedMembers, hash)` | Constructs the validated V1 base first, then requires nonnull transition UUID and changedMembers 0..16. Zero transition UUID is accepted. Does not calculate actual member changes, verify a completed transition or check transition/hash-chain consistency. |
| `StableConfigurationV2.completedTransitionId()` | Returns supplied transition identity. |
| `StableConfigurationV2.changedMemberCount()` | Returns supplied bounded count, not a recomputed diff. |
| `StableConfigurationV2.clusterId()` | Delegates to base owning UUID. |
| `StableConfigurationV2.stateVersion()` | Delegates to base stored version. |
| `StableConfigurationV2.configurationId()` | Delegates to base stable identity. |
| `StableConfigurationV2.previousHash()` | Delegates to base defensive clone. |
| `StableConfigurationV2.members()` | Delegates to immutable base member list. |
| `StableConfigurationV2.creationEpochMillis()` | Delegates to base timestamp. |
| `StableConfigurationV2.voters()` | Delegates to immutable base voter-only set. |
| `StableConfigurationV2.hash()` | Delegates to base defensive clone. |

## Joint Configuration V1 Functions

Source: [JointConfigurationV1.java](../../modules/aether-cluster-api/src/main/java/io/aetherdb/cluster/api/JointConfigurationV1.java).

| Function | Behavior and boundary |
| --- | --- |
| `JointConfigurationV1.JointConfigurationV1(transition, old, target, proposer, created, hash)` | Requires nonnull/nonzero transition/proposer IDs, nonnull old/target, equal cluster IDs, target version=old version+2, timestamp>=0, nonnull 32-byte hash, and nonempty old/target voter intersection. Stores configuration references and clones hash. Version addition is unchecked. Does not authorize proposer, bind target completedTransitionId to transition, calculate changed members or verify previousHash/hash chain. |
| `JointConfigurationV1.clusterId()` | Delegates to old configuration's cluster UUID. |
| `JointConfigurationV1.stateVersion()` | Returns old version+1 with unchecked long addition. |
| `JointConfigurationV1.transitionId()` | Returns stored nonzero transition UUID. |
| `JointConfigurationV1.oldConfiguration()` | Returns retained outgoing interface object, not a deep copy. |
| `JointConfigurationV1.targetConfiguration()` | Returns retained incoming immutable V2 object. |
| `JointConfigurationV1.proposerNodeId()` | Returns stored proposer UUID; membership was not checked. |
| `JointConfigurationV1.creationEpochMillis()` | Returns stored transition timestamp. |
| `JointConfigurationV1.hash()` | Returns defensive supplied-hash clone. |

The voters method is inherited from JointConfiguration. Version relationship is
old N, joint N+1, target N+2, but these values alone do not prove the joint entry
was committed before the target became authoritative. The nonempty intersection
constructor rule does not substitute for independent majorities during transition.

## Dual Majority Functions

Source: [DualMajority.java](../../modules/aether-cluster-core/src/main/java/io/aetherdb/cluster/core/DualMajority.java).

| Function | Behavior and boundary |
| --- | --- |
| `DualMajority.DualMajority()` | Private empty utility constructor. |
| `DualMajority.hasMajority(voters, acknowledgements)` | Requires nonnull sets and nonempty voters; counts voter IDs contained in acknowledgments and compares with voters.size()/2+1. Outsider acknowledgments are ignored; Set identity semantics deduplicate IDs. Null UUID elements are not rejected and can count when present in both sets. Does not authenticate acknowledgment or test durability. |
| `DualMajority.hasJointMajority(oldVoters, newVoters, acknowledgements)` | ANDs two hasMajority calls; each side uses its own denominator. Short-circuit means newVoters validation is skipped when old majority fails. No membership intersection/history validation. |
| `DualMajority.committedIndex(oldVoters, newVoters, durableIndexes)` | Returns minimum of each side's majorityIndex. Computes a quorum-supported index only: no current-term restriction, monotonic commit state, log/hash check, force or application. Both sides are evaluated unless the first throws. |
| `DualMajority.majorityIndex(voters, indexes)` | Private helper rejects empty voter set, maps each voter to reported index or zero if missing, sorts ascending, selects position length-(length/2+1). Missing voters remain in the denominator. Allocates/sorts one long array per side. Null sets/maps or a present null index can raise NullPointerException; negative indexes are not rejected. |

For old `{a,b,c}` and target `{b,c,d}`, acknowledgments `{a,b}` satisfy old
majority but fail target majority. `{b,c}` satisfy both. A majority of the union
is not equivalent to the two required majorities. With durable indexes a=9,
b=8, c=7, d=6, old supports 8, target supports 7, so the joint result is 7.
Missing map entries use zero instead of shrinking the configured voter population.

## Verification and Reading Order

[DualMajorityTest.java](../../modules/aether-cluster-core/src/test/java/io/aetherdb/cluster/core/DualMajorityTest.java)
contains one test covering independent-majority rejection/acceptance and the
9/8/7/6 durable-index example. It does not cover constructor validation, endpoint
canonicalization/ownership, unknown role codes, missing/negative/null indexes,
short-circuit validation, overflow or actual durable membership publication.
No dedicated cluster API test source tree is present in this checkout.

```powershell
.\gradlew.bat --no-daemon :modules:aether-cluster-core:test --rerun
.venv\Scripts\python.exe -m pytest scripts/tests/test_contributor_docs.py -q
```

Read identity/member values first, then stable/joint configurations, then
DualMajority. Review separate wire codecs before trusting encoded hashes or
decoder acceptance. Review Raft current-term commit rules and durable log/store
ownership separately before integrating these foundations into a service.
