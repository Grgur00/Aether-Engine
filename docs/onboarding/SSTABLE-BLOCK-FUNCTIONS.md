# SSTable Block Function Reference

[Storage engine](STORAGE-ENGINE.md) | [Persistent internals](PERSISTENT-INTERNALS.md)

Continue with [SSTable reads and verification](SSTABLE-READ-FUNCTIONS.md) for
channel lifetime, decoded block caches and authoritative inventory checks.

This reference follows the block codecs in the local development checkout.
It includes the streaming scanner, which may not yet exist in the published
`main` source. Source links are navigation aids, not proof of release parity.

## Architecture And Ownership

An SSTable DATA block has two layers. `RestartBlock` encodes sorted key/value
entries and a restart-offset suffix. `BlockEnvelope` wraps that payload with
format metadata and a checksum. A `BlockHandle` addresses the physical block,
including its trailer, within a file.

Each entry starts with three canonical varints: shared key-prefix length,
unshared key-suffix length and value length. The suffix and value bytes follow.
Restart entries use a zero-length shared prefix. The payload ends with
little-endian restart offsets and a restart count. Even an empty payload has
one restart offset, zero, followed by the count, one.

DATA keys are internal keys: user bytes, an eight-byte little-endian positive
sequence and a one-byte type. User keys sort unsigned lexicographically;
versions of the same key sort newest sequence first, then type ascending.
Sorting the complete encoded array lexicographically is not equivalent.

| Boundary | Ownership and cost |
|---|---|
| `InternalKey` construction | Owns a defensive user-key copy unless the private constructor receives an already owned copy. |
| `RestartBlock.Entry` | Owns defensive key and value copies; its array accessors clone again. |
| `RestartBlock.Encoder` | Copies bytes into its output stream, but retains the last input key reference for the next comparison/prefix calculation. |
| `RestartBlockScanner` | Borrows an immutable payload; reconstructs a key into reusable scratch and exposes value offset/length only. |
| `BloomFilterV1.Filter` | Borrows validated encoded filter bytes; membership calls do not copy candidate key ranges. |
| `BlockEnvelope.decode` | Checks the physical bytes, then returns a newly copied payload. |

The streaming path removes value materialization, not all allocation. Physical
read buffers, envelope payload copies, scanner objects and decoded varint results
remain. Checksumming still processes value bytes; skipping a value is not skipping
its integrity check. Metadata blocks can still use materializing decode.

## Internal Keys

Source: [InternalKey.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/InternalKey.java).

### `InternalKey(userKey, sequence, type)`

The public constructor delegates to the private four-argument constructor.
It requires a non-null user key of at most 65,536 bytes, a positive sequence
and type 1 (value) or 2 (tombstone). Empty user keys are allowed. The private
`ownsUserKey` switch avoids a second copy only when the caller already owns the
array; ordinary construction clones it.

### `encode()` And `decode(encoded)`

`encode` allocates user-key length plus nine bytes and writes the sequence and
type trailer. `decode` requires at least nine bytes, copies the user portion,
then uses the ownership-taking constructor to validate without cloning that
copy again. Invalid field values become `SSTableCorruptionException` rather
than constructor `IllegalArgumentException`.

### `compareTo(other)` And `compare(...)`

`compareTo` applies user-key ascending, sequence descending, type ascending
ordering to validated objects. The static range `compare` uses `Arrays.mismatch`
to find the first differing unsigned byte, then compares lengths when one is
a prefix. It allocates no key slices. It checks null arrays, but is not a
complete arbitrary-range validation API; callers supply valid ranges.

### `compareUserKey(encodedInternalKey, userKey)`

Compares only the encoded array's user-key portion against a raw key without
decoding an object or copying a slice. It assumes a valid internal-key layout;
the null check is not full trailer validation.

### `compareEncoded(...)`

The two-array overload compares user portions and reads sequence/type trailers
directly. It assumes validated inputs. The six-argument range overload first
validates both ranges through `sequence(array, offset, length)`, then applies
the same semantic order. The latter accepts scanner scratch with an explicit
logical length rather than treating the scratch capacity as the key length.

### `sequence(...)`, `userKey()` And `type()`

Instance `sequence()` and `type()` return scalar fields. `userKey()` clones the
owned array. Static `sequence(encoded)` checks only null/minimum length and
reads the trailer; it does not establish positive sequence or valid type.
The range overload requires length 9 through 65,545, a valid array range,
positive sequence and type 1 or 2, reporting invalid input as corruption.

### Little-Endian Helpers And `ByteSlice`

Private `readLongLittleEndian` assembles eight unsigned bytes with shifts;
`writeLongLittleEndian` stores the inverse representation. Both rely on their
caller's bounds checks.

`ByteSlice(array, offset, length)` is a lightweight borrowed record, not an
immutable byte owner. Its constructor checks null, negative offsets/lengths
and the endpoint. Generated accessors return the backing array and scalars.
The endpoint expression uses integer addition, so it should not be treated as
overflow-safe validation for arbitrary hostile ranges.

## Varints

Source: [Varint32.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/Varint32.java).

The private `Varint32()` constructor prevents utility instantiation. Despite
the format name, this implementation accepts nonnegative signed Java `int`
values, not the entire unsigned 32-bit range.

- `encode(value)` allocates the canonical one-to-five-byte encoding, using seven data bits per byte and a continuation bit.
- `encodedLength(value)` computes that byte count from leading zero bits; zero occupies one byte. Negative values are rejected.
- `write(output, value)` writes the same representation into an existing `ByteArrayOutputStream`, avoiding a temporary encoded array.
- `decode(bytes, offset, limit)` reads at most five bytes before the exclusive limit. It rejects missing termination, values above `Integer.MAX_VALUE`, and redundant terminal zero bytes in multi-byte encodings. Callers must provide valid array bounds.
- Private `corrupt()` constructs the invalid-varint exception; noncanonical encodings have a separate descriptive exception.
- `Decoded(value, bytes)` returns the parsed integer and consumed byte count through generated record accessors. It is not a view over the source bytes.

## Restart Block Encoding And Materialization

Source: [RestartBlock.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/RestartBlock.java).

### `encode(...)` And `encodedEntrySize(...)`

The private `RestartBlock()` constructor makes the outer class a utility.
`encode(entries, restartInterval)` uses unsigned lexicographic ordering. The
comparator overload supports context-specific ordering, such as internal keys.
Both feed an incremental encoder and finish it; the enclosing class accesses
entry-owned arrays directly without accessor clones.

`encodedEntrySize(previous, entry, restart)` delegates to the raw-key/value-length
overload. It totals three varints, unshared key bytes and value bytes using checked
addition. It measures an entry's body contribution, not the restart array, count
or envelope trailer. At a restart the shared prefix is zero.

### `Encoder(restartInterval, comparator)` And `add(key, value)`

Construction requires a positive interval and non-null comparator. `add` rejects
use after finish and keys that are equal to or precede the prior key under that
comparator. Every interval starts a restart, beginning with the first entry.
It writes the three lengths, key suffix and value into the body stream.

There is no per-entry `Entry` adapter. However, the bytes are copied into the
stream, and `previous = key` retains the caller's array. Do not mutate that key
before the next `add`: doing so changes ordering and prefix computation without
changing the bytes already written.

### `Encoder.finish()`

One-shot finalization appends restart offsets and count in little-endian order,
then returns a new output array. Empty input receives the canonical zero restart.
Further `add` or `finish` calls throw `IllegalStateException`.

### `decode(...)`

The default overload uses unsigned lexicographic ordering; the comparator
overload validates structural lengths and strict key order while materializing
every entry. It reconstructs each key with `Arrays.copyOf` plus suffix copying,
copies every value with `Arrays.copyOfRange`, then constructs an `Entry` that
clones both arrays again. The result is an ordinary list of owning entries.

This decoder does not enforce all scanner restart rules. Do not infer that its
successful return proves every restart offset is an actual entry boundary.

### `entryLimit(raw)`, `shared(left, right)` And `corrupt(message)`

`entryLimit` validates a minimum eight-byte payload, positive feasible restart
count, strictly increasing nonnegative offsets inside the entry region, and the
empty-block zero offset. It returns the first restart-array byte. It does not
itself match offsets to decoded entry boundaries or require zero shared prefixes
at restart entries. `shared` counts equal prefix bytes; `corrupt` constructs a
structural-error exception.

### `Entry(key, value)` And Accessors

The compact record constructor clones both arrays. `key()` and `value()` return
fresh clones on every call. `equals(other)` compares key and value content rather
than array identity; `hashCode()` combines their array hashes. These semantics
protect ownership for ordinary reads but can be expensive for large values in
a verification-only pass.

## Streaming Scanner

Source: [RestartBlockScanner.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/RestartBlockScanner.java).

### `RestartBlockScanner(raw)`

Borrows a payload already validated by the envelope layer. Construction calls
`entryLimit`, creates a little-endian view over the same array and requires the
first restart offset to be zero. It does not perform checksum verification.
The initial reusable key scratch is 128 bytes.

### `next()`

Invalidates the prior current-entry view, returns false at the entry-region end,
or parses three varints. Long arithmetic checks the reconstructed key length
and suffix/value endpoint before accessing bytes. The shared prefix cannot
exceed the preceding key length; an internal key cannot exceed 65,545 bytes.

A restart exactly at the current entry must have shared prefix zero. A subsequent
restart falling inside the entry is rejected. Scratch grows geometrically up to
the key limit, preserves shared bytes and copies only the new key suffix. The
scanner records value offset/length and advances over those bytes without an
`Entry`, value array or value clone. It does not itself validate semantic internal
key fields, global ordering, Bloom membership or table counts; its caller does.

### Accessors And `requireValid()`

`keyBuffer()` exposes borrowed scratch, valid only until the next `next()` call;
do not modify it. `keyLength()` supplies the logical key length. `valueOffset()`
and `valueLength()` identify the value within the borrowed payload, not a copied
value. All call private `requireValid()`, which rejects access before advancing
or after exhaustion. Private `corrupt(message)` creates structural exceptions.

### Stricter Corruption Rejection

The scanner intentionally rejects restart offsets inside entries, nonzero first
restart offsets and shared prefixes at restart points that the older decoder
can accept. This is stricter authoritative-verification behavior, approved for
the streaming implementation. Ordinary materializing read behavior is unchanged.
It does not change the canonical writer format or weaken checksums.

## Physical Envelopes And Handles

Sources: [BlockEnvelope.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/BlockEnvelope.java)
and [BlockHandle.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/BlockHandle.java).

### `BlockEnvelope.encode(...)` And `encodeTraced(...)`

The private constructor prevents instantiation. The two-argument overload
delegates with no finish trace. The trace overload enters `blockCopy` and restores
the previous stage in `finally`. Private `encodeTraced` validates inputs, copies
the payload into an array eight bytes larger, and appends compression 0, block
kind, version 1 and flags 0. In stage `blockChecksum` it stores masked CRC32C over
the payload plus those first four metadata bytes, as a little-endian integer.

### `BlockEnvelope.decode(physical, expectedKind)`

Requires the trailer, expected kind and canonical metadata, then compares the
stored checksum with masked CRC32C of payload plus metadata. Checksum timing
ends in `finally`, including failure. Only after validation does it copy out
the payload, recording copied-byte and copy-time diagnostics. Private
`corrupt(message)` reports format/checksum failures. No zero-copy payload view
is returned by this API.

### `BlockHandle(...)`, `encode()`, `decode(...)` And `validateWithin(...)`

Construction requires nonnegative offset and physical length of at least eight
bytes. `encode` produces exactly 16 little-endian bytes: offset, length and a
zero reserved word. `decode` requires that exact representation and validates
reserved bits and intrinsic bounds, reporting corruption rather than accepting
an arbitrary location descriptor.

`validateWithin(footerOffset)` uses checked offset-plus-length addition, requires
offset at least 4,096 and end no later than the footer. It proves body bounds,
not checksum validity, expected block kind or non-overlap with other handles.
Private `corrupt(message)` constructs these exceptions. Generated `offset()`
and `length()` accessors return scalars.

## Bloom Filter Functions

Source: [BloomFilterV1.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/filter/BloomFilterV1.java).

### `build(keys)`

The private utility constructor prevents instantiation. `build` deduplicates
keys by byte content using private `Key` records that clone their input. It
allocates byte-aligned bits at ten bits per distinct key, with a minimum 64 bits,
sets seven probes per key, and returns a 24-byte header plus bit data. The header
stores version/hash identifiers, distinct count, bit count and fixed policy.

### `validate(encoded)` And `decode(encoded)`

Private `validate` checks minimum size, version and hash identifiers, nonnegative
key count, byte-aligned bit count of at least 64, ten-bit/seven-probe policy,
reserved zero and exact payload length. It returns the bit count. It does not
prove that actual table keys were inserted. `decode` validates once, then invokes
private `Filter(encoded, bitCount)` to retain the array without copying it.
Callers must keep that array immutable.

### `mayContain(...)`

The static encoded-filter/key overload reparses the header on every call.
`Filter.mayContain(key)` delegates to its range overload, which checks the range
and invokes the private membership core without copying it or revalidating the
header. The core hashes the key, derives an odd rotated delta, and probes seven
positions using unsigned remainder. A missing bit returns false immediately;
true means possible membership, not a confirmed lookup.

The absence guarantee assumes an intact filter built for the actual keys.
Authoritative verification checks actual entries against it to reject
false-negative corruption. Structural header validation alone cannot do that.

### `add(...)`, `hash(...)`, `Key` And `corrupt()`

Private `add` sets the same seven positions used for membership. Public `hash(key)`
delegates to the private range hash: fixed seed, unsigned-byte mixing and frozen
64-bit avalanche. These constants are persistent format compatibility state;
changing them silently would invalidate existing filters.

Private `Key(bytes)` clones bytes for stable deduplication; `equals` and `hashCode`
use content equality/hash. Private `corrupt()` creates the Bloom-format exception.

## Persistent Block Kind Identifiers

[BlockKind.java](../../modules/aether-sstable/src/main/java/io/aetherdb/sstable/block/BlockKind.java)
defines the trailer's persistent kind codes: DATA=1 (internal-key/value records),
INDEX=2 (upper-bound keys and handles), FILTER=3 (user-key Bloom filter),
PROPERTIES=4 (immutable table properties), METAINDEX=5 (named metadata handles).
These explicit codes, not enum ordinals, are the on-disk compatibility boundary.

| Function | Behavior |
| --- | --- |
| `BlockKind.BlockKind(code)` | Private enum construction stores explicit integer code for each constant; no range or duplicate validation in the constructor. |
| `BlockKind.code()` | Returns that persistent code without conversion or mutation. |
| `BlockKind.fromCode(code)` | Iterates constants, returns matching explicit code, otherwise throws SSTableCorruptionException with the unknown value. Does not mask or truncate supplied integer into byte range. |

Compiler-supplied enum `values()` returns an array of constants and `valueOf(name)`
looks up the Java constant name; neither is a persistent-code decoder. Envelope
encoding casts code to the trailer byte; decoding first obtains an unsigned byte,
then resolves kind and compares it to the context-required expected kind before
checksum/copy. A valid kind code in the wrong location still fails. Adding or
renumbering constants requires format compatibility review, not just compilation.

## Verification Evidence

Read [StreamingVerifierTest](../../modules/aether-sstable/src/test/java/io/aetherdb/sstable/StreamingVerifierTest.java)
for scanner/authoritative-verifier regression cases. The documentation's compiler
tree check catches omitted declared function names, not incorrect prose or missing
behavioral test cases. Format compatibility and corruption behavior still require
the codec and verifier tests themselves.
