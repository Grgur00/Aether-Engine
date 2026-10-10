# Replicated Log Format and Codec Functions

[Function index](FUNCTION-INDEX.md) | [Replication contracts](REPLICATION-CONTRACT-FUNCTIONS.md) | [Concrete store](REPLICATED-STORE-FUNCTIONS.md) | [WAL formats](WAL-FUNCTIONS.md)

This reference covers **43 explicit declarations in five complete files**:
[ReplicatedLogFormatV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedLogFormatV1.java),
[ReplicatedLogIdentityV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedLogIdentityV1.java),
[ReplicatedLogSegmentHeaderV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedLogSegmentHeaderV1.java),
[ReplicatedWriteCommandV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedWriteCommandV1.java),
and [ReplicatedLogEntryCodecV1.java](../../modules/aether-replicated-log/src/main/java/io/aetherdb/replication/log/ReplicatedLogEntryCodecV1.java).
Private helpers and nested operation/type methods are included. Generated record
and enum methods are not counted. Concrete file I/O/recovery remains separate.

## Format Architecture and Integrity

```text
WriteBatch -> ordered operations -> AEBT body
  -> AECM envelope (body SHA-256 + envelope CRC32C)
  -> AERE log record (payload SHA-256 + entry hash + header/record CRC32C)
segment: 4096-byte header region + aligned records
directory: RLOG-IDENTITY binds cluster/node + compatibility vector
```

These are distinct from the local engine WAL formats. All multibyte fields below
use **little-endian** order. UUIDs are encoded as most-significant then
least-significant 64-bit halves, each little-endian, not conventional UUID network
byte order. Checksums are masked CRC32C via MaskedCrc32c; hashes use SHA-256.
Neither is a signature, keyed authentication, consensus proof, or encryption.

The entry hash includes the supplied predecessor hash. A single-record parser
can verify that relationship inside the record but cannot prove that this hash
matches the actual preceding retained entry. Store recovery/append must compare
neighbors and establish index/term/state continuity. Likewise segment-header
decoding validates its claimed boundary, not the existence of that predecessor.

## Size and Name Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogFormatV1.ReplicatedLogFormatV1()` | Private empty utility constructor; no mutable format registry. |
| `ReplicatedLogFormatV1.segmentName(number)` | Requires number>=1, otherwise IllegalArgumentException. Formats RLOG- followed by a zero-padded 20-digit decimal number and .aerlog. Formats a name only; does not create, validate ownership of, or open a file. |
| `ReplicatedLogFormatV1.recordLength(payloadBytes)` | Rejects negatives. Adds 192-byte header and 8-byte trailer with Math.addExact, then 0..7 bytes of padding to reach an 8-byte multiple. Int overflow raises ArithmeticException; an aligned result above 67,108,864 raises IllegalArgumentException. Empty payload yields 200 bytes. |

Constants: meaningful segment header=192 bytes, full header region=4096;
entry header=192, trailer=8, alignment=8; target segment=134,217,728 bytes
(128 MiB), hard segment=268,435,456 (256 MiB), maximum record=67,108,864
(64 MiB). Rotation is implemented by the store, not these constants. The largest
payload that fits is 67,108,664 bytes, distinct from the 32 MiB command-body cap.

## Persistent Identity Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogIdentityV1.ReplicatedLogIdentityV1(clusterId, nodeId, creationEpochMillis)` | Rejects null/all-zero UUIDs and negative creation time with IllegalArgumentException. Does not compare with configured identities, authenticate a node, or persist the record. |
| `ReplicatedLogIdentityV1.encode()` | Allocates exactly 256 zero-filled bytes, writes the fixed vector/fingerprint/time, and stores masked CRC32C over bytes 0..251 at offset 252. Does not write or force a file. |
| `ReplicatedLogIdentityV1.decode(encoded)` | Requires exactly 256 nonnull bytes. Checks magic/version/length/zero flags, exact supported format vector, zero reserved bytes, CRC and fingerprint with MessageDigest.isEqual. Constructs the record to validate UUIDs/time. Mismatch raises IllegalArgumentException. No external expected identity is passed. |
| `ReplicatedLogIdentityV1.compatibilityFingerprint()` | Builds the fixed little-endian vector prefixed by ASCII AETHER-RLOG-COMPAT-V1: version, target/hard/record limits, header/region/alignment sizes, then seven short values of 1. Hashes only the used vector prefix, not unused allocation tail. Returns a fresh 32-byte SHA-256; missing algorithm raises AssertionError. It fingerprints format compatibility, not source revision or log contents. |
| `ReplicatedLogIdentityV1.isZero(id)` | Treats null or both UUID halves zero as invalid identity. |
| `ReplicatedLogIdentityV1.putUuid(bytes, id)` | Writes most-significant then least-significant halves using buffer order. Relies on caller capacity and validated ID. |
| `ReplicatedLogIdentityV1.getUuid(bytes)` | Reads two longs in that same order and constructs a UUID; does not itself reject zero. |

| Identity offset | Bytes and meaning |
| --- | --- |
| 0..15 | AETHRLI1 magic (8), version=1 (2), length=256 (2), flags=0 (4). |
| 16..47 | Cluster UUID (16), node UUID (16). |
| 48..95 | Version=1 and target/hard/record limits (four longs); header=192, region=4096, alignment=8 (three ints); two short format values=1. |
| 96..135 | Compatibility fingerprint (32), creation epoch millis (8). |
| 136..251 | Reserved zero bytes, checked individually. |
| 252..255 | Masked CRC32C of bytes 0..251. |

## Segment Header Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogSegmentHeaderV1.ReplicatedLogSegmentHeaderV1(clusterId, nodeId, segmentNumber, firstIndex, previousIndex, previousTerm, previousEntryHash, creationEpochMillis)` | Requires nonnull/nonzero IDs, segment/first>=1, previousIndex=firstIndex-1, nonnegative term/time, and exactly 32 hash bytes. For previousIndex=0 requires term=0 and all-zero hash; for a positive predecessor requires term>=1. Copies hash. Invalid inputs raise IllegalArgumentException. Does not look up the claimed predecessor or require nonzero hash for a positive predecessor. |
| `ReplicatedLogSegmentHeaderV1.previousEntryHash()` | Returns a fresh clone. |
| `ReplicatedLogSegmentHeaderV1.equals(other)` | Identity fast path, then compares all UUID/scalar fields and hash contents. Null/other type returns false. |
| `ReplicatedLogSegmentHeaderV1.hashCode()` | Combines Objects.hash of metadata with Arrays.hashCode of the predecessor hash; a collection hash, not a cryptographic digest. |
| `ReplicatedLogSegmentHeaderV1.encodeRegion()` | Allocates zero-filled 4096-byte region, writes meaningful header, fixed size/version vector, time and CRC over first 188 bytes. Returns bytes only: despite its comment, it does not force storage. |
| `ReplicatedLogSegmentHeaderV1.decodeRegion(region, clusterId, nodeId, segmentNumber)` | Requires exact 4096 bytes. Checks header magic/version/size/flags, expected cluster/node/segment, fixed size/version vector, reserved bytes 152..187, zero tail 192..4095, and CRC at 188. Constructor then checks the internal boundary/time. Expected null IDs simply fail equality checks. Does not compare against the actual preceding file. |
| `ReplicatedLogSegmentHeaderV1.allZero(bytes)` | Returns false at first nonzero byte, otherwise true. Private caller supplies a nonnull hash. |
| `ReplicatedLogSegmentHeaderV1.putUuid(bytes, id)` | Writes UUID halves with caller buffer order/capacity. |
| `ReplicatedLogSegmentHeaderV1.getUuid(bytes)` | Reads UUID halves; semantic identity validation remains with decode/constructor. |

| Header-region offset | Bytes and meaning |
| --- | --- |
| 0..15 | AETHRSG1 magic (8), version=1 (2), header length=192 (2), flags=0 (4). |
| 16..47 | Cluster and node UUIDs. |
| 48..79 | Segment number, first index, previous index, previous term (four longs). |
| 80..111 | Previous entry hash (32). |
| 112..151 | Target and hard segment bytes (two longs), maximum record/header bytes (two ints), version=1 (long), creation time (long). |
| 152..191 | Reserved zero bytes (36), masked CRC32C over 0..187 (4). |
| 192..4095 | Entire remaining header-region tail must be zero. |

## Command and Operation Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedWriteCommandV1.ReplicatedWriteCommandV1(commandId, sequences, operations)` | Requires nonzero/nonnull ID, nonnull range/list, 1..10,000 operations, and inclusive range count equal to list size. Invalid fields raise IllegalArgumentException; oversized range can raise ArithmeticException from operationCount. List.copyOf freezes membership and rejects null elements with NullPointerException. Does not validate ordinal continuity, key/value caps or DELETE emptiness of manually constructed operations. |
| `ReplicatedWriteCommandV1.fromBatch(commandId, sequences, batch)` | Traverses batch.mutations in order, converts Put/Delete to copied Operation values, numbers ordinals from zero, supplies empty delete value, then constructs the command. Unsupported mutation raises IllegalArgumentException. Null batch causes NullPointerException. Does not submit/seal/close the batch, allocate sequence ranges, or deduplicate command IDs. |
| `ReplicatedWriteCommandV1.encode()` | Encodes body, allocates envelope+body, hashes body, writes fixed envelope fields and zero session/reserved fields, stores CRC over envelope bytes 0..119, then copies body. Does not apply the command. |
| `ReplicatedWriteCommandV1.decode(payload)` | Requires length between 160 and 128+32 MiB. Checks envelope magic/version/header/kind/flags/format, zero session fields, exact body length/format/reserved values, envelope CRC/tail, and copied-body SHA. Decodes operations, constructs positive ordered range and command to validate ID/count. Most malformed data raises IllegalArgumentException; allocation/count caveats below apply. |
| `ReplicatedWriteCommandV1.encodeBody()` | Totals header+operation/key/value bytes and key/value counts; rejects body above 32 MiB, writes AEBT header then supplied type/length/ordinal/key/value in order. Reads owned operation arrays directly, avoiding accessor clones. It does not normalize bad ordinals or enforce decoder key/value/DELETE rules. Per-operation length addition is an int expression before the long Math.addExact, so that helper alone is not a universal overflow guard for extreme manually constructed arrays. |
| `ReplicatedWriteCommandV1.decodeBody(body, expectedCount)` | Validates AEBT header/version/size/length/count, then allocates ArrayList(expectedCount). For each operation checks remaining 16-byte header, zero flags/reserved, known type, key<=65,536, value<=16 MiB, nonnegative lengths, empty DELETE value, ordinal exactly loop position, and remaining data. Reads arrays then Operation copies them again. Rejects trailing bytes or aggregate key/value totals mismatch. |
| `ReplicatedWriteCommandV1.sha256(bytes)` | Fresh SHA-256 digest of supplied bytes; unavailable algorithm becomes AssertionError. |
| `ReplicatedWriteCommandV1.isZero(id)` | Null or all-zero UUID check. |
| `ReplicatedWriteCommandV1.putUuid(bytes, id)` | Writes UUID most/least halves using buffer order. |
| `ReplicatedWriteCommandV1.getUuid(bytes)` | Reads UUID halves without semantic validation. |
| `ReplicatedWriteCommandV1.Type.Type(code)` | Stores private enum code: PUT=1 or DELETE=2. |
| `ReplicatedWriteCommandV1.Type.fromCode(code)` | Resolves exactly 1 or 2; other values raise IllegalArgumentException. |
| `ReplicatedWriteCommandV1.Operation.Operation(type, key, value, ordinal)` | Requires nonnull type/key/value and ordinal>=0, otherwise IllegalArgumentException. Clones both arrays. Does not enforce maximum lengths, contiguous ordinal, or empty delete value. |
| `ReplicatedWriteCommandV1.Operation.key()` | Fresh defensive key clone. |
| `ReplicatedWriteCommandV1.Operation.value()` | Fresh defensive value clone, including empty delete arrays. |
| `ReplicatedWriteCommandV1.Operation.equals(other)` | Compares type, ordinal, key and value contents; identity fast path, false for null/another type. |
| `ReplicatedWriteCommandV1.Operation.hashCode()` | Multiplier-31 combination of type, key/value content hashes and ordinal. Not a wire hash. |

Generated command equality uses UUID/range/list equality; list element equality
uses the explicit content-based Operation.equals. operations() returns the
immutable copied list, whose elements defensively own their bytes.

Decode allocates the operation list from **untrusted declared count before the
command constructor enforces 1..10,000**. Negative capacity raises
IllegalArgumentException; a huge positive count can request excessive allocation
and fail with OutOfMemoryError before the later semantic limit. Body length and
hash checks are not a count-allocation guard. Direct constructors/encode can also
produce a command the decoder rejects (bad ordinal, DELETE value, key/value cap).
These are documented existing boundaries, not fixed by this guide.

| Command envelope offset | Bytes and meaning |
| --- | --- |
| 0..15 | AECM (4), version=1 (2), header=128 (2), kind=1 (1), flags=0 (1), reserved=0 (2), format=1 (4). |
| 16..55 | Command UUID (16), three unsupported session longs=0 (24). |
| 56..87 | First/last sequence (two longs), operation count/body length/body format=1/reserved=0 (four ints). |
| 88..127 | Body SHA-256 (32), envelope CRC over 0..119 (4), zero tail (4). |
| 128 onward | Exact AEBT body; no command-envelope alignment padding. |

AEBT body offsets: magic at 0, version short at 4, header-length short=32 at 6,
total body length int at 8, count int at 12, key-byte total long at 16, value-byte
total long at 24. Each operation starts with type byte, zero flags byte, zero
reserved short, key length int, value length int, ordinal int (16 bytes), then
exact key and value. Totals exclude headers. Empty keys are accepted by decoding.

## Entry Codec Functions

| Function | Behavior and boundaries |
| --- | --- |
| `ReplicatedLogEntryCodecV1.ReplicatedLogEntryCodecV1()` | Private empty utility constructor. |
| `ReplicatedLogEntryCodecV1.create(type, payloadFormatVersion, index, term, commandId, sequenceStart, stateSequenceAfter, previousEntryHash, payload)` | Clones nonnull payload, calculates payload SHA and entry hash, constructs immutable entry (copying again), then checks COMMAND consistency. Null payload raises IllegalArgumentException. Invalid hash inputs fail before record validation; no predecessor lookup or next-index reservation. |
| `ReplicatedLogEntryCodecV1.encode(entry)` | Rejects null, validates COMMAND body, clones/hashes payload and recalculates entry hash; mismatch raises IllegalArgumentException. Calculates aligned length, allocates record, writes header/CRC/payload/zero padding/trailer magic, then allocates a checksum-coverage array for trailer CRC. Does not write/force storage. |
| `ReplicatedLogEntryCodecV1.decode(record)` | Requires exact aligned record length 200..64 MiB. Checks AERE/version/header/declared length/type/zero flags/reserved, header CRC, exact payload-to-record length, all zero padding, AEND trailer and record CRC. Copies payload, verifies payload SHA and recalculated entry hash, constructs record, then validates COMMAND body agreement. No neighbor is supplied, so the reported hash-chain mismatch concerns this record's own hash calculation. |
| `ReplicatedLogEntryCodecV1.entryHash(type, payloadVersion, index, term, commandId, sequenceStart, stateAfter, payloadLength, payloadHash, previousHash)` | Requires nonnull type/UUID and two exact 32-byte hashes. Hashes ASCII AETHER-RLOG-ENTRY-V1 followed by previous hash, type byte, zero flags byte, payload-version short, index/term/UUID halves/sequence-start/state-after (six longs), payload length int, payload hash. Does not hash raw payload here, validate positive coordinates, or look up a predecessor. |
| `ReplicatedLogEntryCodecV1.validateCommandConsistency(entry)` | Returns immediately for non-COMMAND. Otherwise fully decodes a defensive payload copy and requires command ID, first sequence and last sequence equal outer entry metadata. Mismatch raises IllegalArgumentException; does not apply or deduplicate the command. |
| `ReplicatedLogEntryCodecV1.sha256(bytes)` | Fresh SHA-256 digest; missing algorithm becomes AssertionError. |

| Entry offset | Bytes and meaning |
| --- | --- |
| 0..15 | AERE (4), version=1 (2), header=192 (2), complete aligned record length (4), type (1), flags=0 (1), payload-version unsigned short (2). |
| 16..63 | Index, term, UUID halves, sequenceStart, stateSequenceAfter (six longs). |
| 64..167 | Payload length int, zero reserved int, payload SHA (32), previous entry hash (32), current entry hash (32). |
| 168..191 | Header CRC over 0..167 (4), zero reserved bytes (20). |
| 192 onward | Exact payload, then 0..7 zero alignment bytes. |
| Last 8 bytes | Record CRC (4), AEND magic (4). CRC covers everything before trailer plus AEND, excluding its own four bytes. |

Both hash calculation and encoding narrow payloadFormatVersion to a short.
ReplicatedLogEntry's general constructor only requires nonnegative int versions;
CONFIGURATION can therefore carry a value above 65,535 that is truncated on wire
and decodes to a different version. COMMAND=1 and NOOP/BARRIER=0 avoid this case.
Codec validation does not supply missing membership-configuration semantics.

Entry encode/decode and command consistency allocate whole-record/payload/body
copies, checksum scratch arrays, and per-operation arrays. These are not streaming
or bounded-scratch parsers. Size limits bound normal records, not every allocation
from malformed metadata. No zero-copy or low-allocation claim follows from them.

## Tests and Remaining Work

[ReplicationFormatTest.java](../../modules/aether-replicated-log/src/test/java/io/aetherdb/replication/log/ReplicationFormatTest.java)
has seven tests: identity exact-size/round-trip/corruption, ordered batch/ranges,
planner discontinuity, header-region boundary/tail corruption, aligned entry
round-trip and predecessor hash carriage, selected entry corruptions, and shared
corruption-mutator integration. It does not exhaust each parser field, allocation
limit, constructor/decoder asymmetry, version truncation, or maximum-size case.

These functions do not supply durable append, segment rotation, suffix repair,
directory force, locking, or Raft commitment. The concrete replicated-log store
is the next reference. Runtime and frozen experiment implementations are unchanged.
