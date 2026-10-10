# Backup Manifest and Archive Functions

[Function index](FUNCTION-INDEX.md) | [Filesystem identity](FILESYSTEM-IDENTITY-FUNCTIONS.md) | [Operations](OPERATIONS-AND-DEBUGGING.md)

This reference covers portable backup inventory and ZIP serialization in the
current checkout. Archive integrity, restore eligibility, and destination publication
are separate boundaries; see [restore functions](BACKUP-RESTORE-FUNCTIONS.md) for
preflight and restore writing.

## Sources

- [BackupManifestV1](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupManifestV1.java): inventory metadata and binary codec.
- [BackupManifestObject](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupManifestObject.java): object path/kind/length/hash model.
- [BackupObjectKind](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupObjectKind.java): durable kind IDs.
- [BackupArchiveV1](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupArchiveV1.java): ZIP writer/reader and object verification.
- [BackupArchiveContents](../../modules/aether-io/src/main/java/io/aetherdb/io/BackupArchiveContents.java): defensive in-memory contents holder.

## Inventory Object Functions

### BackupManifestObject constructor

Validates/normalizes path, requires a kind, nonnegative length, a 32-byte SHA-256
array, and required format version 1..65,535. Clones the checksum. Null encryption
metadata reference becomes empty; NUL in it is rejected. Other strings/scalars
are retained. It does not hash object data or establish that kind matches filename.

### validatePath(path), checksum(), checksumEquals(other)

`validatePath()` replaces backslashes with slashes, rejects blank/leading-slash/
trailing-slash/double-slash/NUL forms, and rejects components exactly `.` or `..`.
It does not forbid drive-like colons, platform device names, or every destination-
specific unsafe path. This is a portable object-key check, not proof of safe
filesystem extraction. `checksum()` clones the stored array; package-private
`checksumEquals()` compares contents using Arrays.equals, not a constant-time
authentication check.

### BackupObjectKind constructor, code(), fromCode(code)

The enum stores stable IDs: DATABASE_IDENTITY=1, FORMAT_OPTIONS=2,
CHECKPOINT_METADATA=3, CURRENT=4, MANIFEST=5, SSTABLE=6, WAL=7, SECURITY_METADATA=8,
RAFT_METADATA=9, BACKUP_REPORT=10. `code()` returns the ID. `fromCode()` scans values
and rejects unknown IDs; Java enum ordinal is not the serialized identity.

## Manifest Construction and Access

### BackupManifestV1 constructor

Requires nonzero backup/database UUIDs, nonnegative creation time and sequence,
positive format epoch, and Raft index/term either both absent (-1) or both
nonnegative. Cluster UUID is optional and not checked for nonzero; zero encodes
as the same sentinel as null and decodes to null.

Requires nonblank, NUL-free creator-version/hash-algorithm text and exactly SHA-256
as the algorithm. Requires a nonempty copied object list and unique normalized
object paths. Null encryption epoch array becomes empty; otherwise it is cloned
and every epoch must be positive. Epoch uniqueness/order is not enforced.
Clones a required 32-byte compatibility fingerprint.

`validateObjectInventory()` rejects duplicate path strings, not missing mandatory
object kinds or inconsistent database/Raft state. `validateText()` performs the
text checks; `requireNonzero()` checks required UUIDs. Nulls passed to their
Objects.requireNonNull paths produce NullPointerException rather than uniformly
IllegalArgumentException.

### Accessors, objectCount(), totalBytes()

`encryptionKeyEpochs()` and `compatibilityFingerprint()` return clones. The objects
accessor returns an immutable copied list of immutable object records whose hash
accessors clone. `objectCount()` returns list size. `totalBytes()` sums declared
object lengths with a long stream sum; it does not use checked addition, inspect
payloads, or measure compressed ZIP size.

## Binary Manifest Functions

### encode()

Computes a total allocation size and emits a little-endian image with a 160-byte
header: AETHBKM1, version/header length/total length, body CRC slot, three UUIDs,
creation time, epoch, Raft index/term, sequence watermark, object count/total bytes,
compatibility fingerprint, and zero reserved int.

The body stores length-prefixed UTF-8 creator version/algorithm, encryption epochs,
and object records. Each object includes path, one-byte kind, zero reserved byte,
unsigned-short required version, long length, 32-byte hash, and encryption reference.
Object/list/epoch order follows caller order; encoding is repeatable for the same
ordered model, not canonical sorting of arbitrary equivalent inventories.

Writes masked CRC32C of the body (excluding the final trailer) into header offset
16, then masked CRC32C of the entire image excluding the final four bytes into its
trailer. These checks detect byte corruption, not maliciously recomputed metadata.
There is no signature, object encryption, or disk durability barrier here.

Size arithmetic includes one multiplyExact for epoch bytes but ordinary int
additions elsewhere; no independent manifest-size cap or fully checked aggregate
allocation budget is enforced. Repeated checksum accessor calls clone hash arrays.

### decode(encoded)

Requires minimum 164 bytes, exact magic/version/header/declared total, matching
body CRC and complete-image trailer CRC. Parses UUIDs (zero cluster means absent),
counters/fingerprint, zero reserved int, strings, epoch array, and object inventory.
Requires zero object reserved bytes, recognized kinds, exact parsing end before
CRC trailer, constructor validity, and matching declared object/byte totals.

It does not verify payload hashes because payload bytes are not in this manifest.
Counts are constrained against remaining bytes, but object count uses a one-byte
minimum rather than full minimum record framing before preallocating the list.
Individual ByteBuffer reads can still raise BufferUnderflowException for malformed
framing; not every malformed image yields one normalized error type.

### String, count, and UUID helpers

| Function | Role |
| --- | --- |
| `utf8(value)` | Encodes UTF-8, with Java replacement behavior for unpaired surrogates. |
| `sized(value)` | Returns four length bytes plus payload length using int arithmetic. |
| `putString(bytes, value)` | Writes int byte length then bytes. |
| `getString(bytes, allowEmpty)` | Reads bounded length, optionally rejects empty, copies bytes, and constructs UTF-8 String with replacement on malformed input. |
| `readCount(bytes, field)` | Requires four available bytes, nonnegative count, and count no larger than remaining bytes. |
| `readElementCount(bytes, field, minimumBytesPerElement)` | Further constrains count by remaining/minimum element size. |
| `putUuid`, `getUuid` | Write/read two longs in the buffer's little-endian order. |

UTF-8 parsing is not strict canonical-text validation. The decoder borrows the
argument during parsing/checksumming; callers must keep it stable during the call.

## ZIP Archive Functions

### BackupArchiveV1 constructor and write(output, manifest, objects)

The private constructor prevents instances. `write()` requires an output, validates
all objects before creating its ZIP wrapper, writes AETHER-BACKUP-MANIFEST.v1 first,
then each object in manifest order, finishes, and closes the wrapper. Despite the
Javadoc's "left open" wording, closing ZipOutputStream closes the underlying caller
output. A failure after writing starts can leave a partial archive; there is no
rollback/publication rename or file force.

Inputs are not snapshotted against concurrent mutation between validation and
writing. A hash check followed by caller mutation is not protected by this helper.
Object paths are not independently checked against the reserved manifest entry
name; a collision can fail ZIP entry creation.

### read(input)

Requires input, reads every ZIP entry, rejects directory entries and duplicate
names, fully decompresses each entry, and stores manifest/object arrays. Closing
ZipInputStream closes the caller input, again contrary to "left open" Javadoc.
Only after reading/closing does it require the manifest, decode it, validate object
inventory/lengths/hashes, and construct defensive contents.

There is no configured compressed-size, decompressed-byte, object-count, or memory
budget in this archive reader. Unlisted data is rejected eventually, but can already
have consumed allocation during decompression. It does not extract entries to disk.
IllegalArgumentException from manifest/object validation is wrapped as IOException;
other unchecked parser/allocation failures are not universally wrapped.

### putEntry(), readAll(), validateObjects(), sha256()

`putEntry()` sets entry timestamp to zero, opens the entry, writes its full array,
and closes the entry. It does not promise cross-JDK ZIP byte identity.
`readAll()` transfers input into ByteArrayOutputStream and returns a copied array.
`validateObjects()` requires every manifest path to have nonnull bytes of the exact
declared length and SHA-256, then requires exact map-key membership with no extras.
`sha256()` returns the digest and treats missing SHA-256 as AssertionError.

These checks verify agreement with the supplied manifest, not trust in that
manifest or semantic validity of SSTable/WAL/identity objects. Restore preflight
performs further checks; no decryption occurs here.

## Contents Holder Functions

`BackupArchiveContents(manifest, objects)` requires a manifest and deep-copies the
map/arrays. It does not call archive verification: direct construction alone does
not prove "verified" contents despite the class description. `objects()` deep-
copies every payload again; `objectBytes(path)` clones one known object or rejects
an unknown path. `deepCopy()` rejects null map keys/arrays, clones all values, and
returns Map.copyOf. The manifest accessor returns the immutable manifest reference.

Large reads retain decompressed arrays and then copy them into this holder, so
peak memory exceeds just final archive-content size. Consumers should account for
accessor copies too; this is not a streaming archive interface.

## Coverage Boundary

All explicit functions/constructors in these five files are covered, with implicit
record accessors described where relevant. Backup eligibility, required object
kinds, key availability, destination paths, restore publication, and rollback
are covered separately as [preflight/writer responsibilities](BACKUP-RESTORE-FUNCTIONS.md).
No runtime fixes or backup security
certification accompany this documentation.
