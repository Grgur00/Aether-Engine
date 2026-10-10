# Filesystem Ownership and Identity Functions

[Function index](FUNCTION-INDEX.md) | [Persistent internals](PERSISTENT-INTERNALS.md) | [Manifest authority](MANIFEST-VERSION-FUNCTIONS.md)

This reference covers database locking, managed-path checks, database identity,
format options, and checkpoint metadata in the current checkout. See the
[archive](BACKUP-ARCHIVE-FUNCTIONS.md) and [restore](BACKUP-RESTORE-FUNCTIONS.md)
references for backup functions.

## Sources and Ownership

- [DatabaseLock](../../modules/aether-io/src/main/java/io/aetherdb/io/DatabaseLock.java): operating-system lock lease.
- [PathSecurityValidator](../../modules/aether-io/src/main/java/io/aetherdb/io/PathSecurityValidator.java): flat managed namespace and path checks.
- [DatabaseIdentityV1](../../modules/aether-io/src/main/java/io/aetherdb/io/DatabaseIdentityV1.java): database identity bytes.
- [FormatOptionsV1](../../modules/aether-io/src/main/java/io/aetherdb/io/FormatOptionsV1.java): format vector and compatibility fingerprint.
- [CheckpointMetadataV1](../../modules/aether-io/src/main/java/io/aetherdb/io/CheckpointMetadataV1.java): checkpoint publication metadata bytes.

The engine coordinates when files are written, forced, and published. These codecs
produce/validate byte arrays; encoding alone performs no disk I/O. A metadata CRC
does not verify SSTable contents, reconstruct a manifest, or establish durability.

## Managed Paths

### PathSecurityValidator constructor and isManaged(name)

The private constructor prevents instances. `isManaged()` recognizes exact names
DB-IDENTITY, FORMAT-OPTIONS, LOCK, CURRENT, and CHECKPOINT, plus prefixes MANIFEST-,
WAL-, SST-, DB-, FORMAT-, and CHECKPOINT. It is a broad namespace classification,
not a parser proving a valid numeric filename/version. A matching unrelated file
is subject to managed-file validation too.

### validateRoot(root, strict)

Converts root to absolute normalized form, rejects a symbolic-link root when strict,
and requires a directory with NOFOLLOW_LINKS. Lists immediate children and, for
each managed name, rejects symlinks and nonregular files without following the
final child link. Returns the normalized root and closes the directory stream.

It does not create the directory, validate unmanaged children, recursively inspect
subdirectories, or canonicalize/check every ancestor with `toRealPath()`. Even with
strict false the NOFOLLOW directory test normally rejects a symlink root. The
listing is materialized via `toList()`, not bounded streaming validation.

These are checks at a moment in time. Another actor can replace an entry between
validation and later use; callers must not infer race-free directory-descriptor
operations or sandbox containment from this helper alone.

### managed(root, name)

Requires a managed name with no slash/backslash, resolves it under absolute
normalized root, and checks the normalized parent equals that root. Returns a
lexically confined flat path. It does not check file existence/type, acquire locks,
or resolve symlinks. Filesystem safety and lexical filename safety are different
responsibilities.

## Lock Lease

### DatabaseLock.acquire(databaseRoot)

Strictly validates the existing root, opens/creates LOCK for writing, and uses
nonblocking `tryLock()`. OverlappingFileLockException becomes the same unavailable
outcome as a null lock: the channel closes and IOException reports an already-held
database lock. A successful acquisition constructs a lease retaining channel and
FileLock. IOException/runtime failures close an open channel before rethrowing.

Opening LOCK does not independently request NOFOLLOW_LINKS, so the earlier path
check does not eliminate a replacement race. There is no timeout/retry loop, lock
file contents protocol, or lease expiry. Existence of LOCK alone does not imply
another process owns it; the OS lock is authoritative for this acquisition.

### DatabaseLock(channel, lock) and close()

The private constructor stores the acquired resources. `close()` releases the
FileLock and closes the channel in finally. The LOCK file remains. There is no
closed flag guaranteeing repeated close succeeds, and cleanup exceptions can
replace an earlier release/acquisition failure rather than being systematically
attached as suppressed exceptions.

## DB-IDENTITY Functions

### DatabaseIdentityV1(databaseId, creationEpochMillis, creatorMajor, creatorMinor)

Requires a nonnull, nonzero UUID and nonnegative creation time and creator version
components. Implicit record accessors return those immutable/scalar values.

### encode()

Allocates exactly 128 zero-initialized bytes in little-endian layout: AETHDBI1 magic,
version 1, length 128, zero flags, UUID halves, creation milliseconds, format epoch
1, and packed creator major/minor. Bytes 56..123 remain zero. Stores masked CRC32C
of bytes 0..123 at offset 124. UUID halves are little-endian longs here, not the
network-order UUID representation used in typed scalar codecs.

### decode(encoded)

Requires exact length, magic/version/length/zero flags, epoch 1, zero reserved bytes,
and matching CRC. Reconstructs the UUID/time and unpacks creator version, then
invokes constructor validation. Packed high-bit version components become negative
ints and are rejected rather than exposed as unsigned Java version values.

The decoder does not preserve the input array, verify other database files, or
check wall-clock plausibility of a nonnegative creation timestamp.

## FORMAT-OPTIONS Functions

### FormatOptionsV1(databaseId, creationEpochMillis) and encode()

The constructor requires nonzero UUID and nonnegative time. `encode()` allocates
4096 bytes, emits AETHFMT1/version 1/header length 512/total length 4096, UUID,
the fixed 88-byte format vector at offsets 32..119, time at 120, and compatibility
fingerprint at 128..159. Reserved header bytes and the 3584-byte tail remain zero.
CRC at 508 covers bytes 0..507, not the tail; tail integrity is enforced by the
decoder's zero-byte checks.

### putFormatVector(bytes) and verifyFormatVector(bytes)

`putFormatVector()` writes hardcoded epochs, versions, flags, sizes, levels, and
other format-affecting constants into the supplied buffer. It does not read live
engine configuration. `verifyFormatVector()` generates the expected 88-byte vector,
reads input bytes 32..119, and requires exact equality. Unknown vectors are rejected,
not negotiated or upgraded by this codec.

### compatibilityFingerprint()

Builds little-endian bytes from ASCII AETHER-COMPAT-V1 followed by the format vector,
then returns a new SHA-256 digest of the used portion. Missing SHA-256 becomes
AssertionError. UUID and creation time are excluded: databases with different
identities but the same vector share this fingerprint.

This identifies that hardcoded vector, not the complete checkout, tuned SSTable
target, model configuration, or all runtime options. It is not a signature or a
replacement for experiment provenance.

### decode(encoded)

Requires 4096 bytes and valid header, verifies exact vector, reads time/fingerprint,
checks zero bytes 160..507 and 512..4095, verifies header CRC, constructs options,
and compares stored/recomputed SHA-256 using MessageDigest.isEqual. It does not
compare identity/time against DB-IDENTITY; the orchestrating caller must establish
cross-file agreement.

## CHECKPOINT Metadata Functions

### CheckpointMetadataV1 constructor and compatibilityFingerprint()

Requires nonnull UUID, nonnegative sequence/time/table count/byte count, positive
source view generation and source/checkpoint manifest numbers, and a 32-byte
fingerprint. Unlike DB-IDENTITY/FORMAT-OPTIONS, it does not reject a zero UUID.
Copies the fingerprint on construction and clones it on accessor calls. Other
record accessors expose immutable/scalar fields.

### encode()

Allocates exactly 256 bytes. Writes AETHCHK1/version 1/length 256/zero flags, UUID,
checkpoint sequence, creation time, source view generation, two manifest numbers,
SSTable count/total bytes, and fingerprint into little-endian framing. Bytes
120..251 remain zero; CRC at 252 covers bytes 0..251.

### decode(encoded) and little(value)

`decode()` requires exact length and checks CRC before interpreting header/fields.
It validates magic/version/length/flags, reads fields and fingerprint, requires
zero reserved bytes, then invokes constructor validation (including another
fingerprint copy). Private `little()` wraps the array in a little-endian ByteBuffer;
it does not copy storage.

The decoder does not compare the fingerprint with current FormatOptions, prove
the advertised sequence is the largest stored sequence, count actual SSTables,
or verify manifest provenance. Those are inventory/orchestration checks beyond
this fixed-format codec.

## Coverage Boundary

All explicit constructors, methods, and private helpers in these five files are
covered. Borrowed input arrays must remain stable during decoding; no decoder
provides protection against concurrent mutation of its argument. Backup archive,
preflight, and restore writing are covered in the linked companion references;
configuration and broader operations tooling remain. This page does not modify runtime behavior or
claim platform-specific filesystem durability from metadata validation alone.
