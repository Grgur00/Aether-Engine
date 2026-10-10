# Encryption, Envelopes, and Key Wrapping Functions

[Function index](FUNCTION-INDEX.md) | [Security](SECURITY-FUNCTIONS.md) | [Backup archives](BACKUP-ARCHIVE-FUNCTIONS.md) | [Backup restore](BACKUP-RESTORE-FUNCTIONS.md)

Source: [aether-crypto](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto).
All **25 explicit declarations across seven implementation files** are covered
here, including private helpers and the package-private deterministic test path.
Implicit record accessors/equality are outside that count.

## Ownership Architecture

```text
caller key + plaintext + chosen AAD -> AetherAead -> AeadEnvelope
  -> little-endian encoded envelope -> caller persistence/transport
backup ID + normalized path -> backup AAD -> encrypted backup object + metadata
local wrapping key + epoch + purpose -> wrapped bytes -> caller key storage
```

This module contains encryption primitives and metadata carriers. It does not
automatically encrypt WAL/SSTable files, select a key by epoch, rotate keys,
authorize callers, authenticate RPC connections, or provide a remote key manager.
Persistence, key selection, access control and nonce-use policy belong to callers.
Read the actual integration before inferring that an engine path is encrypted.

Encryption uses AES/GCM/NoPadding with a 32-byte key, 12-byte random nonce and
128-bit tag. Each operation creates a Cipher. A shared SecureRandom supplies
public-path nonces; there is no durable nonce ledger, collision check or per-key
operation counter here. The deterministic nonce helper is for tests, not a
production nonce-allocation mechanism.

## Envelope Functions

Source: [AeadEnvelope.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/AeadEnvelope.java).

| Function | Behavior and boundaries |
| --- | --- |
| `AeadEnvelope.AeadEnvelope(version, keyEpoch, nonce, ciphertext)` | Requires version exactly 1, positive epoch, nonnull arrays, nonce exactly 12 bytes and ciphertext at least 16 bytes for the tag. Clones both arrays. Unsupported values/lengths raise IllegalArgumentException; null arrays raise NullPointerException. Does not authenticate the ciphertext or impose a maximum ciphertext size. |
| `AeadEnvelope.nonce()` | Returns a new nonce clone. |
| `AeadEnvelope.ciphertext()` | Returns a new ciphertext/tag clone. |
| `AeadEnvelope.encode()` | Allocates one little-endian byte array containing header, nonce and ciphertext. Uses internal arrays rather than clone-returning accessors. No checksum, signature or additional authentication is added by encoding. Allocation-size addition uses ordinary int arithmetic. |
| `AeadEnvelope.decode(encoded)` | Rejects null, fewer than 52 bytes, incorrect six-byte magic, nonce length other than 12, ciphertext shorter than 16 or a total-length mismatch. Reads version as unsigned short and epoch as long, allocates/copies nonce and ciphertext, then delegates version/epoch validation to the constructor, which clones again. Parsing alone does not verify a GCM tag. |

### Envelope Wire Layout

All multi-byte fields are little-endian, unlike the general Java client protocol.

| Offset | Field |
| --- | --- |
| 0 | six ASCII bytes AEENC1 |
| 6 | version, unsigned 2-byte value |
| 8 | key epoch, 8 bytes |
| 16 | nonce length, 4 bytes |
| 20 | ciphertext length including tag, 4 bytes |
| 24 | nonce, 12 bytes |
| 36 | ciphertext and tag, at least 16 bytes |

Decode requires exact length, so trailing bytes are rejected. There is no
streaming parser or module-level input-size cap. Its remaining-size comparison
uses nonceLength + ciphertextLength with ordinary int addition; the accepted
nonce length and positive ciphertext minimum precede that check, but this is not
a general resource-admission policy. Record-generated equality/hashCode compare
array identity rather than contents; use byte-content comparisons in tests.

## AES-GCM Functions

Sources: [AetherAead.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/AetherAead.java),
[AetherDecryptionException.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/AetherDecryptionException.java).

| Function | Behavior and boundaries |
| --- | --- |
| `AetherAead.AetherAead()` | Private empty constructor; the helper exposes static operations. |
| `AetherAead.encrypt(key, keyEpoch, plaintext, aad)` | Validates a 32-byte key and nonnull plaintext, fills a new 12-byte nonce with SecureRandom, then delegates to the deterministic helper. Empty plaintext is allowed. Epoch validation happens later in AeadEnvelope after cipher work. Null and empty AAD both omit updateAAD. |
| `AetherAead.encryptWithNonceForTest(key, keyEpoch, nonce, plaintext, aad)` | Package-private explicit-nonce path. Validates key and nonnull nonce/plaintext, initializes AES/GCM with 128 tag bits, optionally supplies AAD, and wraps doFinal output in an envelope. Envelope construction enforces nonce length/epoch after encryption; provider errors are caught as GeneralSecurityException and wrapped in IllegalStateException. Explicit argument/record validation exceptions propagate separately. |
| `AetherAead.decrypt(key, envelope, aad)` | Validates key and nonnull envelope, obtains cloned nonce/ciphertext, initializes AES/GCM, applies nonempty AAD and returns newly produced plaintext. GeneralSecurityException, including authentication-tag failure and provider initialization failures, becomes AetherDecryptionException. Does not select a key or compare an expected epoch. |
| `AetherAead.validateKey(key)` | Rejects null or a length other than 32 with IllegalArgumentException. Does not validate provenance, entropy or key-use history. |
| `AetherDecryptionException.AetherDecryptionException(message, cause)` | Delegates to RuntimeException; preserves the supplied cause, which may be null. No extra classification, logging or sanitization. |

Ciphertext/tag authentication depends on the chosen key, nonce and caller AAD.
The helper does **not** automatically add envelope version or keyEpoch to AAD.
Changing a valid positive envelope epoch while retaining nonce/ciphertext does
not itself change this helper's authentication input. Metadata binding must be
established by the higher-level protocol; the field is not a key resolver.

Caller-owned plaintext/key/AAD buffers are not explicitly cleared. Envelope
arrays are cloned, but transient provider key material, ciphertext clones and
returned plaintext do not have a module-wide destruction lifecycle. The helper
does not protect against concurrent caller mutation of input arrays.

## Backup Object Functions

Sources: [BackupObjectAead.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/BackupObjectAead.java),
[EncryptedBackupObject.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/EncryptedBackupObject.java).

| Function | Behavior and boundaries |
| --- | --- |
| `BackupObjectAead.BackupObjectAead()` | Private empty constructor. Defines metadata prefix aether-backup-object-v1 and algorithm label AES-256-GCM. |
| `BackupObjectAead.encrypt(backupId, objectPath, key, keyEpoch, plaintext)` | Normalizes/validates path, builds backup-specific AAD, calls random-nonce encryption, then returns metadataReference(epoch) and encoded envelope in EncryptedBackupObject. Neither writes an archive nor updates a manifest. Null backup ID fails in aad; key/plaintext/epoch errors follow delegated validation. |
| `BackupObjectAead.decrypt(backupId, objectPath, key, encodedEnvelope)` | Normalizes path, decodes the envelope, derives the same AAD and decrypts with the caller-supplied key. Does not accept or validate a manifest metadata reference or an expected epoch. Malformed envelope/path failures differ from tag/authentication failures. |
| `BackupObjectAead.metadataReference(keyEpoch)` | Requires positive epoch and returns aether-backup-object-v1:alg=AES-256-GCM:key_epoch= followed by the decimal epoch. Does not parse references, retrieve a key or authenticate the string. |
| `BackupObjectAead.aad(backupId, objectPath)` | Requires nonnull UUID; UTF-8 encodes prefix, NUL, UUID string, NUL, path. Binds object bytes to backup identity and the supplied normalized path. Key epoch and algorithm metadata string are not included. |
| `BackupObjectAead.normalizePath(objectPath)` | Requires nonnull path, converts backslashes to slashes, rejects blank input, leading/trailing slash, double slash, NUL and path components exactly `.` or `..`. Returns normalized text without stripping whitespace. Does not resolve a filesystem path, reject drive-letter syntax or perform symlink containment checks. |
| `EncryptedBackupObject.EncryptedBackupObject(encryptionMetadataReference, encodedEnvelope)` | Requires nonnull reference/bytes, rejects blank or NUL-containing reference, and clones bytes. Does not parse the reference, decode the envelope, require nonempty bytes or check reference/envelope epoch agreement. |
| `EncryptedBackupObject.encodedEnvelope()` | Returns a fresh encoded-byte clone. Generated encryptionMetadataReference() returns the immutable String. |

The normalization rule makes backslash and slash spellings equivalent for AAD.
The . / .. component rejection is literal; it is not a portable archive extraction
policy. Filesystem ownership and extraction rules belong to the
[backup/archive implementation](BACKUP-ARCHIVE-FUNCTIONS.md). Moving an encrypted
object to another normalized path or backup ID changes AAD and fails authenticated
decryption with the original key. Metadata references remain separate manifest
data and must be checked by integration code where required.

## Local Key Wrapper Functions

Sources: [LocalKeyWrapper.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/LocalKeyWrapper.java),
[WrappedKey.java](../../modules/aether-crypto/src/main/java/io/aetherdb/crypto/WrappedKey.java).

| Function | Behavior and boundaries |
| --- | --- |
| `LocalKeyWrapper.LocalKeyWrapper(wrappingKeyId, wrappingKey)` | Rejects null/blank ID or null/non-32-byte key with IllegalArgumentException. Keeps the ID spelling unchanged and clones the key into wrapper ownership. Does not reject NUL in the ID or load a keystore. |
| `LocalKeyWrapper.wrap(keyEpoch, plaintextKey, purpose)` | Requires nonnull plaintextKey, derives purpose/epoch AAD and encrypts under the stored wrapping key with a random nonce. Returns WrappedKey carrying the wrapper ID and requested epoch. Does not require plaintextKey to be 32 bytes: any nonnull byte sequence, including empty, is accepted by encryption. |
| `LocalKeyWrapper.unwrap(wrapped, purpose)` | Requires nonnull record; exact wrapping-key ID mismatch immediately raises AetherDecryptionException with null cause. Otherwise decrypts its envelope using AAD derived from the outer wrapped keyEpoch and requested purpose. Does not verify inner envelope epoch equals outer epoch or validate unwrapped key length. |
| `LocalKeyWrapper.destroy()` | Fills the one wrapper-owned wrappingKey array with zero bytes. No destroyed flag or synchronization: subsequent operations still run using the zeroed 32-byte array. Does not clear the original caller array, provider copies, existing wrapped values or returned plaintext. Repeated calls simply fill again. |
| `LocalKeyWrapper.aad(keyEpoch, purpose)` | Requires nonnull/nonblank purpose, otherwise IllegalArgumentException. UTF-8 encodes aether-key-wrap:v1:, decimal epoch, colon and purpose without stripping it. Does not itself validate positive epoch; that is enforced by envelope/record construction. Wrapping-key ID is not included in AAD. |
| `WrappedKey.WrappedKey(wrappingKeyId, keyEpoch, envelope)` | Requires nonnull/nonblank ID, positive outer epoch and nonnull envelope, all via IllegalArgumentException. Keeps the immutable envelope reference. Does not require its epoch to match the outer epoch or its ciphertext to represent a particular plaintext-key length. |

Outer epoch and purpose are authenticated through wrapper AAD. Wrapping-key ID
is checked locally before decryption, not authenticated by that AAD format. These
checks do not establish a centralized key registry or rotation protocol. The
wrapper is not AutoCloseable and has no persistent storage; callers must arrange
its lifetime. destroy is a local buffer overwrite, not proof of complete process
memory erasure or an enforced post-destruction state.

## Verification Scope

Compiler-tree documentation coverage inventories all seven files. Existing tests
exercise envelope round trips, AAD/ciphertext mismatch, corruption helpers, key
length rejection, backup ID/path binding and unsafe relative components, plus
key wrapping/purpose/ID failures. They do not exhaustively check zeroization,
post-destroy use, inner/outer epoch mismatch, mutable caller-buffer races, all
path forms, resource bounds or metadata substitution. The reference records
current code behavior; it does not certify cryptographic deployment safety.
Runtime encryption and frozen research implementations remain unchanged.
