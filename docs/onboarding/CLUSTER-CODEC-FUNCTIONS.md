# Cluster Identity and Configuration Codec Functions

[Function index](FUNCTION-INDEX.md) | [Membership and quorum](CLUSTER-MEMBERSHIP-FUNCTIONS.md) | [Raft storage](RAFT-STORAGE-FUNCTIONS.md) | [Format catalog](FORMAT-CATALOG-FUNCTIONS.md)

Source: [aether-cluster-codec](../../modules/aether-cluster-codec/src/main/java/io/aetherdb/cluster/codec).
All **53 explicit declarations across four files** are covered here, including
private helpers. These are byte-array codecs, not filesystem publication or a
membership-change service. Read the membership reference for constructor rules.

## Encoding Architecture

```text
identity values -> fixed 256-byte identity records
stable V1 -> 160-byte header + aligned member/endpoint records
stable V2 -> 192-byte header + reused V1 member bytes
joint V1 -> 256-byte header + encoded old stable + encoded target V2
```

All numeric fields use little-endian order. UUIDs are written as most-significant
long followed by least-significant long, each little-endian, not RFC network-order
UUID bytes. SHA-256 and masked CRC32C provide unkeyed integrity, not authenticity.
No codec verifies real log ancestry, proposer authorization, certificate identity,
membership durability or consensus commitment. Arrays returned by encoders are new;
decoders wrap caller input without taking an initial immutable snapshot. Callers
must not mutate input concurrently with validation/parsing.

Stable and joint encoders accept an all-zero supplied configuration hash as a
request to compute the hash; nonzero supplied hashes must match. Encoding does not
update the original object. Decode the result to obtain a value with populated
hash before embedding it in a joint configuration. Joint header embedded-hash
fields come from the original old/target objects, not the newly encoded arrays.
Zero nested hashes can therefore encode successfully but fail joint decoding.

## Identity Byte Layouts

Offsets below are zero-based, end-exclusive ranges. Both identities are 256 bytes.

| Range | Cluster identity | Node identity |
| --- | --- | --- |
| 0..8 | ASCII AETHCLI1 | ASCII AETHNDI1 |
| 8..10 / 10..12 / 12..16 | version=1 / size=256 / zero flags | Same common header |
| 16..32 | Cluster UUID | Cluster UUID |
| 32..48 | Created i64 at 32, constant i64=1 at 40 | Node UUID |
| 48..56 | Second constant i64=1 | Created i64 |
| 56..72 | Bootstrap configuration UUID | Role u8 at 56; zero 57..64; generation i64 at 64 |
| 72..104 | Bootstrap configuration hash | Computed node hash |
| 104..136 | Compatibility fingerprint | Zero reserved bytes |
| 136..252 | Zero reserved bytes | Zero reserved bytes |
| 252..256 | Masked CRC32C over 0..252 | Same CRC coverage |

Node SHA-256 is over exactly 49 bytes: cluster UUID, node UUID, role byte,
created i64, generation i64 in that order. The physical node record puts creation
before role; nodeHash deliberately uses a separate semantic field order. There
is no domain prefix or keyed authentication. Cluster bootstrap hash/fingerprint
are supplied values checked for length by the record, not recomputed here.

## Identity Functions

Source: [IdentityCodecV1.java](../../modules/aether-cluster-codec/src/main/java/io/aetherdb/cluster/codec/IdentityCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `IdentityCodecV1.IdentityCodecV1()` | Private empty utility constructor. |
| `IdentityCodecV1.encodeCluster(v)` | Allocates common cluster header, writes UUID/timestamp, two constants=1, bootstrap UUID/hash/fingerprint, then CRC. Unwritten reserved bytes stay zero. Null value raises NullPointerException. No bootstrap verification or file write. |
| `IdentityCodecV1.decodeCluster(in)` | Validates exact size/header/CRC, reads fields, requires both constants=1 and zero 136..252; constructs ClusterIdentity for identifier/timestamp/hash-length checks and defensive ownership. |
| `IdentityCodecV1.encodeNode(v)` | Writes common node header, UUIDs, timestamp, role byte, generation at 64, computed nodeHash at 72, then CRC. Zero-initialized array supplies padding. |
| `IdentityCodecV1.decodeNode(in)` | Validates header/CRC, resolves unsigned role through MemberRole.fromCode, requires zero 57..64 and 104..252, constructs NodeIdentity, then compares stored/computed hash with MessageDigest.isEqual. Generation must be exactly 1 through constructor. |
| `IdentityCodecV1.base(magic)` | Allocates zero-filled 256 bytes and writes ASCII magic, version short=1, size short=256, flags int=0. Internal fixed literals supply expected eight-byte magic; helper itself does not validate arbitrary magic length. |
| `IdentityCodecV1.validate(in, magic)` | Rejects null/wrong size/CRC first, then exact magic/version/size/flags. Returns buffer wrapping caller bytes. Does not check payload or all reserved ranges. |
| `IdentityCodecV1.nodeHash(v)` | Builds exact 49-byte little-endian semantic identity buffer and SHA-256 digest. Missing SHA provider becomes IllegalStateException. Does not verify a certificate or ClusterMember.identityHash. |
| `IdentityCodecV1.finish(out)` | Writes masked CRC32C of first 252 bytes at 252. |
| `IdentityCodecV1.le(b)` | Package helper wraps array with little-endian ByteBuffer; no copy or validation. |
| `IdentityCodecV1.putUuid(b, u)` | Writes UUID MSB then LSB longs using buffer order. |
| `IdentityCodecV1.getUuid(b)` | Reads two longs into UUID; no zero-UUID check. |
| `IdentityCodecV1.get(b, n)` | Allocates n bytes and reads them, advancing position. Negative size/short input use normal allocation/buffer exceptions. |
| `IdentityCodecV1.zero(b, from, to)` | Requires each byte in end-exclusive range to equal zero; does not validate range bounds separately. |
| `IdentityCodecV1.invalid()` | Creates IllegalArgumentException with identity-encoding message; callers throw it. |

## Stable Header Layouts

| Field | V1 offset/size | V2 offset/size |
| --- | --- | --- |
| Magic, version, header size | 0/4 AECF, 4/2=1, 6/2=160 | 0/4 AEC2, 4/2=2, 6/2=192 |
| Kind, auxiliary byte, reserved short | 8/1=1, 9/1=0, 10/2=0 | Same |
| Total byte length | 12/4 | 12/4 |
| Cluster UUID, state version, configuration UUID | 16/16, 32/8, 40/16 | Same |
| Previous hash | 56/32 | 56/32 |
| Completed transition UUID | Absent | 88/16 |
| Voters, staged, total members, member bytes | 88/4, 92/4, 96/4, 100/4 | 104/4, 108/4, 112/4, 116/4 |
| Creation timestamp | 104/8 | 120/8 |
| Changed members, reserved int | Absent | 128/4, 132/4=0 |
| Configuration SHA-256 | 112/32 | 136/32 |
| Zero reserved bytes | 144..156 | 168..188 |
| Header masked CRC32C | 156/4 over 0..156 | 188/4 over 0..188 |
| Member body begins | 160 | 192 |

Configuration SHA covers the entire record with its hash and CRC fields replaced
by zero. Header CRC excludes the member body; SHA includes it. Neither validates
previousHash against a predecessor. Decoders require memberBytes=actual body size,
count=voters+staged, actual voter count matching header, and no trailing member
bytes. API constructors enforce 1..31 voters, at most 64 staged, unique members
and full endpoint values. Those caps are checked after parsing, not before loops.

## Member and Endpoint Layouts

Member header is 96 bytes: node UUID 0/16; role 16/1; member flags 17/1;
endpoint count 18/2; record length 20/4; generation 24/8; added index 32/8;
added term 40/8; UTF-8 name length 48/8; identity hash 56/32; reserved zero
88/8. Name bytes follow, then zero padding to eight-byte alignment, then endpoint
records. Length includes header/name/padding/all endpoints.

Endpoint header is 24 bytes: scheme 0/1, address type 1/1, flags 2/2, port
4/2, priority 6/2, address length 8/4, record length 12/4, reserved zero 16/8.
Address bytes follow; zero padding rounds the total to eight-byte alignment.
Role/scheme/type and endpoint short fields decode unsigned. Member flags narrow
to one byte on encode even though the API accepts any int; round-trip may change
flags. Scheme/type decoding uses enum position code-1, depending on current enum
ordering rather than searching their explicit code fields.

## Stable V1 Functions

Source: [StableConfigurationCodecV1.java](../../modules/aether-cluster-codec/src/main/java/io/aetherdb/cluster/codec/StableConfigurationCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `StableConfigurationCodecV1.StableConfigurationCodecV1()` | Private empty utility constructor. |
| `StableConfigurationCodecV1.encode(value)` | Sums member lengths, addExact for header+body allocation, writes header/member records, computes hash, accepts zero supplied hash or matching digest, fills hash/header CRC. Accepts StableConfiguration interface; trusts member ordering, counts and accessor consistency of custom implementations. Aggregate stream sum itself is ordinary int arithmetic. |
| `StableConfigurationCodecV1.decode(in)` | Validates minimum size, header constants/total length, reserved bytes, header CRC and full-record SHA. Parses count members, rejects trailing bytes and voter mismatch, then constructs V1 for value constraints. Does not pre-cap count before iteration or explicitly reject noncanonical member/endpoint ordering. |
| `StableConfigurationCodecV1.putMember(b, m)` | Writes fixed member header, narrowed flags, UTF-8 name, zero alignment padding and endpoints; requires bytes written equal calculated memberLength, otherwise IllegalStateException. |
| `StableConfigurationCodecV1.getMember(b)` | Reads fixed fields, requires reserved long=0, name length 0..128, member length at least 96 and within remaining-buffer bound; reads name, validates padding, parses endpoint count, checks exact consumed length and constructs ClusterMember. UTF-8 conversion replaces malformed sequences rather than using a strict decoder. Endpoints are not parsed in a member-limited buffer; length equality is checked afterward. |
| `StableConfigurationCodecV1.putEndpoint(b, e)` | Writes fixed fields, aligned length, reserved zero, address and padding. Repeated address() calls obtain clones. |
| `StableConfigurationCodecV1.getEndpoint(b)` | Reads unsigned codes/shorts, requires reserved zero, positive address length, aligned record-length equality and buffer bound; allocates address, verifies zero padding, resolves enums and constructs endpoint. Constructor checks representation length/flags/port, not binary DNS grammar or wildcard address. |
| `StableConfigurationCodecV1.enumAt(values, code)` | Requires code 1..values.length, returns values[code-1]; unknown codes use invalid(). |
| `StableConfigurationCodecV1.memberLength(m)` | Aligns 96+UTF-8-name length, adds aligned 24+address length for each endpoint using Math.addExact. |
| `StableConfigurationCodecV1.aligned(n)` | Returns (n+7)&~7; unchecked addition, no negative/overflow guard. |
| `StableConfigurationCodecV1.pad8(b)` | Writes zero until absolute buffer position is divisible by eight. |
| `StableConfigurationCodecV1.skipZeroPad(b)` | Reads until absolute position is eight-aligned; nonzero byte raises invalid(). |
| `StableConfigurationCodecV1.hash(in, h1, h2, c1, c2)` | Clones complete record, zeroes hash/CRC ranges, returns SHA-256. Invalid ranges use normal array exceptions; missing SHA provider becomes IllegalStateException. |
| `StableConfigurationCodecV1.allZero(v)` | True if every byte is zero, including an empty array; internal valid values supply 32-byte hashes. Null fails naturally. |
| `StableConfigurationCodecV1.le(in)` | Wraps caller array little-endian, without copy. |
| `StableConfigurationCodecV1.invalid()` | Creates stable-V1 IllegalArgumentException. Not every malformed-buffer exception is converted to this type. |

API constructors sort decoded members/endpoints but stored hash still reflects
original wire bytes. A recomputed-integrity unsorted input can consequently yield
a normalized object whose re-encoding fails supplied-hash comparison. Malformed
UTF-8 replacement can also alter bytes; resulting name may pass or fail the API
128-byte UTF-8 cap. Do not infer canonical-wire acceptance merely from method names.
Truncated fixed headers can raise BufferUnderflowException. Length additions,
alignment and count arithmetic are not uniformly overflow-checked; this is not a
uniform bounded-allocation parser for arbitrary hostile input.

## Stable V2 Functions

Source: [StableConfigurationCodecV2.java](../../modules/aether-cluster-codec/src/main/java/io/aetherdb/cluster/codec/StableConfigurationCodecV2.java).

| Function | Behavior and boundaries |
| --- | --- |
| `StableConfigurationCodecV2.StableConfigurationCodecV2()` | Private empty utility constructor. |
| `StableConfigurationCodecV2.encode(v)` | Builds V1 value with zero hash and encodes it to obtain member bytes; allocates V2, writes transition/count/change header, copies member body, computes V2 SHA and checks supplied hash, then fills SHA/header CRC. Intermediate V1 encoding is extra allocation/hashing, not durable publication. |
| `StableConfigurationCodecV2.decode(in)` | Validates V2 header, reserved fields, body size/count relation, SHA/CRC; builds synthetic V1 record with copied member bytes and newly calculated V1 SHA/CRC, decodes via V1, then constructs V2 with original V2 digest. Constructor checks changed count 0..16 and nonnull transition, but does not compare a real transition. |
| `StableConfigurationCodecV2.hash(b, ranges)` | Package alias forwarding to sha with zeroed ranges. |
| `StableConfigurationCodecV2.sha(b, ranges)` | Clones bytes, zeroes each start/end pair, hashes complete clone. Odd range count or invalid endpoints fail through ordinary array/index checks; no separate range validation. Missing SHA provider becomes IllegalStateException. |
| `StableConfigurationCodecV2.le(b)` | Wraps array little-endian. |
| `StableConfigurationCodecV2.putUuid(b, u)` | Writes UUID MSB then LSB longs. |
| `StableConfigurationCodecV2.getUuid(b)` | Reads two longs into UUID. |
| `StableConfigurationCodecV2.get(b, n)` | Allocates/copies n bytes and advances buffer. |
| `StableConfigurationCodecV2.zero(b, s, e)` | Requires end-exclusive reserved range zero; no separate bounds validation. |
| `StableConfigurationCodecV2.allZero(b)` | Scans supplied hash for all-zero sentinel; empty is also true, null fails naturally. |
| `StableConfigurationCodecV2.invalid()` | Creates stable-V2 IllegalArgumentException. |

## Joint Header Layout

| Field | Offset/size |
| --- | --- |
| Magic/version/header size | 0/4 AECJ, 4/2=1, 6/2=256 |
| Kind/auxiliary/reserved/total length | 8/1=2, 9/1=1, 10/2=0, 12/4 |
| Cluster UUID/joint state version/transition UUID | 16/16, 32/8, 40/16 |
| Old/target versions, old/target byte lengths | 56/8, 64/8, 72/4, 76/4 |
| Old/target configuration hashes | 80/32, 112/32 |
| Old/target voter counts, intersection count, changed count | 144/4, 148/4, 152/4, 156/4 |
| Proposer UUID/creation timestamp | 160/16, 176/8 |
| Joint SHA/reserved/CRC | 184/32, zero 216..252, 252/4 |
| Body | Old stable bytes at 256, target V2 immediately after |

Joint SHA covers whole record with 184..216 and 252..256 zeroed. CRC covers
only 0..252. Nested records retain their own integrity fields; whole joint SHA
also covers those bytes. Header lengths must sum to actual input length, but the
int additions and old/target version increments are unchecked.

## Joint Functions

Source: [JointConfigurationCodecV1.java](../../modules/aether-cluster-codec/src/main/java/io/aetherdb/cluster/codec/JointConfigurationCodecV1.java).

| Function | Behavior and boundaries |
| --- | --- |
| `JointConfigurationCodecV1.JointConfigurationCodecV1()` | Private empty utility constructor. |
| `JointConfigurationCodecV1.encode(v)` | Encodes old stable and target V2; writes header from object accessors, voter/intersection/changed counts, proposer/time; copies embedded records, computes joint SHA, checks zero-or-matching supplied hash and fills SHA/CRC. Does not replace original embedded hashes with encoded-record hashes. Allocation length addition is unchecked. |
| `JointConfigurationCodecV1.decode(in)` | Validates header/reserved/lengths, version N/N+1/N+2 relation, header changed count 1..16, SHA/CRC; slices and decodes nested records; checks cluster IDs, versions, voter/intersection counts and embedded hashes against header, then constructs joint value. Does not compare header changed count to target.changedMemberCount, bind target completedTransitionId to joint transition, validate proposer membership or verify history. |
| `JointConfigurationCodecV1.encodeStable(s)` | Uses V2 codec for actual StableConfigurationV2 instance; otherwise V1 interface codec. |
| `JointConfigurationCodecV1.decodeStable(b)` | If length>5 and byte 4 equals 2, chooses V2; otherwise V1. Dispatch uses low version byte, not full header validation; selected decoder validates magic/version. |
| `JointConfigurationCodecV1.le(b)` | Wraps caller bytes little-endian. |
| `JointConfigurationCodecV1.put(b, u)` | Writes UUID MSB then LSB longs. |
| `JointConfigurationCodecV1.get(b)` | Reads UUID from two longs. |
| `JointConfigurationCodecV1.bytes(b, n)` | Allocates and reads n bytes. |
| `JointConfigurationCodecV1.invalid()` | Creates joint-V1 IllegalArgumentException; malformed slicing/buffer failures are not uniformly converted. |

Encoding accepts target changed count=0 through the API, while joint decoding
requires header count>=1. A recomputed-integrity header count can differ from the
target's count and still pass because each is checked independently, not compared.
Nested previous hashes are not checked for chain continuity. Passing decoding
proves the implemented consistency checks, not an authorized committed transition.

## Verification Scope

[ClusterCodecTest.java](../../modules/aether-cluster-codec/src/test/java/io/aetherdb/cluster/codec/ClusterCodecTest.java)
has two tests: identity size/round-trip and one unrecomputed-CRC corruption;
stable V1/V2/joint round-trip with populated nested hashes and header-size checks.
It does not exhaust fields/padding, malformed lengths/counts/UTF-8, supplied-hash
sentinels, ordering canonicality, flag narrowing or transition metadata mismatches.

```powershell
.\gradlew.bat --no-daemon :modules:aether-cluster-codec:test --rerun
.venv\Scripts\python.exe -m pytest scripts/tests/test_contributor_docs.py -q
```

Compiler inventory checks guard declaration omissions; they do not prove semantic
accuracy or runtime safety. Format publication, authorization and quorum rules
must be reviewed in their separate owning modules before service integration.
