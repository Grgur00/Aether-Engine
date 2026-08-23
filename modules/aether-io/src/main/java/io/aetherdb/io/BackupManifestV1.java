package io.aetherdb.io;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Portable backup manifest v1 with object inventory, compatibility, and restore preflight data.
 *
 * @param backupId unique backup identity
 * @param createdUnixMillis backup creation time
 * @param aetherVersion creator version string
 * @param formatEpoch durable format epoch
 * @param databaseId source database identity
 * @param clusterId optional source cluster identity, null for single-node backups
 * @param raftLastIncludedIndex Raft snapshot index, or -1 when absent
 * @param raftLastIncludedTerm Raft snapshot term, or -1 when absent
 * @param sequenceWatermark maximum included sequence number
 * @param hashAlgorithm object hash algorithm, currently SHA-256
 * @param objects complete object inventory
 * @param encryptionKeyEpochs key epochs needed to decrypt backup objects
 * @param compatibilityFingerprint 32-byte format compatibility fingerprint
 */
public record BackupManifestV1(
        UUID backupId,
        long createdUnixMillis,
        String aetherVersion,
        long formatEpoch,
        UUID databaseId,
        UUID clusterId,
        long raftLastIncludedIndex,
        long raftLastIncludedTerm,
        long sequenceWatermark,
        String hashAlgorithm,
        List<BackupManifestObject> objects,
        long[] encryptionKeyEpochs,
        byte[] compatibilityFingerprint) {
    public static final int HEADER_BYTES = 160;
    public static final String HASH_SHA256 = "SHA-256";

    private static final byte[] MAGIC = "AETHBKM1".getBytes(StandardCharsets.US_ASCII);

    /** Validates fields and takes defensive copies. */
    public BackupManifestV1 {
        requireNonzero(backupId, "backup id");
        requireNonzero(databaseId, "database id");
        if (createdUnixMillis < 0 || formatEpoch <= 0 || sequenceWatermark < 0)
            throw new IllegalArgumentException("invalid backup manifest counters");
        if ((raftLastIncludedIndex < 0) != (raftLastIncludedTerm < 0))
            throw new IllegalArgumentException("raft index and term must both be present");
        if (raftLastIncludedIndex < -1 || raftLastIncludedTerm < -1)
            throw new IllegalArgumentException("invalid raft snapshot boundary");
        aetherVersion = validateText(aetherVersion, "aether version");
        hashAlgorithm = validateText(hashAlgorithm, "hash algorithm");
        if (!HASH_SHA256.equals(hashAlgorithm))
            throw new IllegalArgumentException("unsupported backup hash algorithm");
        if (objects == null || objects.isEmpty())
            throw new IllegalArgumentException("backup manifest requires objects");
        objects = List.copyOf(objects);
        validateObjectInventory(objects);
        encryptionKeyEpochs =
                encryptionKeyEpochs == null ? new long[0] : encryptionKeyEpochs.clone();
        for (long epoch : encryptionKeyEpochs)
            if (epoch <= 0) throw new IllegalArgumentException("invalid encryption key epoch");
        compatibilityFingerprint =
                Objects.requireNonNull(compatibilityFingerprint, "compatibility fingerprint")
                        .clone();
        if (compatibilityFingerprint.length != 32)
            throw new IllegalArgumentException("compatibility fingerprint must be 32 bytes");
    }

    @Override
    public long[] encryptionKeyEpochs() {
        return encryptionKeyEpochs.clone();
    }

    @Override
    public byte[] compatibilityFingerprint() {
        return compatibilityFingerprint.clone();
    }

    public long objectCount() {
        return objects.size();
    }

    public long totalBytes() {
        return objects.stream().mapToLong(BackupManifestObject::length).sum();
    }

    /**
     * Encodes the manifest to a deterministic checksummed binary image.
     *
     * @return encoded manifest bytes
     */
    public byte[] encode() {
        byte[] version = utf8(aetherVersion), algorithm = utf8(hashAlgorithm);
        int length = HEADER_BYTES + sized(version) + sized(algorithm);
        length += 4 + Math.multiplyExact(encryptionKeyEpochs.length, Long.BYTES);
        length += 4;
        for (BackupManifestObject object : objects) {
            length += 12 + sized(utf8(object.path())) + object.checksum().length;
            length += sized(utf8(object.encryptionMetadataReference()));
        }
        length += 4;
        byte[] encoded = new byte[length];
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(MAGIC).putShort((short) 1).putShort((short) HEADER_BYTES).putInt(length);
        bytes.putInt(0);
        putUuid(bytes, backupId);
        putUuid(bytes, databaseId);
        putUuid(bytes, clusterId == null ? new UUID(0, 0) : clusterId);
        bytes.putLong(createdUnixMillis)
                .putLong(formatEpoch)
                .putLong(raftLastIncludedIndex)
                .putLong(raftLastIncludedTerm)
                .putLong(sequenceWatermark)
                .putLong(objectCount())
                .putLong(totalBytes())
                .put(compatibilityFingerprint);
        bytes.putInt(0);
        putString(bytes, version);
        putString(bytes, algorithm);
        bytes.putInt(encryptionKeyEpochs.length);
        for (long epoch : encryptionKeyEpochs) bytes.putLong(epoch);
        bytes.putInt(objects.size());
        for (BackupManifestObject object : objects) {
            putString(bytes, utf8(object.path()));
            bytes.put((byte) object.kind().code())
                    .put((byte) 0)
                    .putShort((short) object.requiredFormatVersion())
                    .putLong(object.length());
            bytes.put(object.checksum());
            putString(bytes, utf8(object.encryptionMetadataReference()));
        }
        ByteBuffer.wrap(encoded, 16, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MaskedCrc32c.masked(encoded, HEADER_BYTES, encoded.length - HEADER_BYTES - 4));
        ByteBuffer.wrap(encoded, encoded.length - 4, 4)
                .order(ByteOrder.LITTLE_ENDIAN)
                .putInt(MaskedCrc32c.masked(encoded, 0, encoded.length - 4));
        return encoded;
    }

    /**
     * Decodes and validates an exact v1 portable backup manifest image.
     *
     * @param encoded encoded manifest bytes
     * @return decoded manifest
     */
    public static BackupManifestV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length < HEADER_BYTES + 4)
            throw new IllegalArgumentException("backup manifest too short");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        bytes.get(magic);
        int version = Short.toUnsignedInt(bytes.getShort());
        int headerBytes = Short.toUnsignedInt(bytes.getShort());
        int totalBytes = bytes.getInt();
        int bodyCrc = bytes.getInt();
        if (!java.util.Arrays.equals(magic, MAGIC)
                || version != 1
                || headerBytes != HEADER_BYTES
                || totalBytes != encoded.length) {
            throw new IllegalArgumentException("invalid backup manifest header");
        }
        if (bodyCrc
                != MaskedCrc32c.masked(encoded, HEADER_BYTES, encoded.length - HEADER_BYTES - 4)) {
            throw new IllegalArgumentException("backup manifest body checksum mismatch");
        }
        int storedCrc =
                ByteBuffer.wrap(encoded, encoded.length - 4, 4)
                        .order(ByteOrder.LITTLE_ENDIAN)
                        .getInt();
        if (storedCrc != MaskedCrc32c.masked(encoded, 0, encoded.length - 4))
            throw new IllegalArgumentException("backup manifest checksum mismatch");
        UUID backupId = getUuid(bytes), databaseId = getUuid(bytes), clusterId = getUuid(bytes);
        if (clusterId.getMostSignificantBits() == 0 && clusterId.getLeastSignificantBits() == 0)
            clusterId = null;
        long created = bytes.getLong(), formatEpoch = bytes.getLong();
        long raftIndex = bytes.getLong(), raftTerm = bytes.getLong(), sequence = bytes.getLong();
        long expectedObjectCount = bytes.getLong(), expectedTotalBytes = bytes.getLong();
        byte[] fingerprint = new byte[32];
        bytes.get(fingerprint);
        if (bytes.getInt() != 0) throw new IllegalArgumentException("nonzero backup manifest reserved bytes");
        String aetherVersion = getString(bytes, false), hashAlgorithm = getString(bytes, false);
        int epochCount = readElementCount(bytes, "encryption key epochs", Long.BYTES);
        long[] epochs = new long[epochCount];
        for (int index = 0; index < epochs.length; index++) epochs[index] = bytes.getLong();
        int objectCount = readElementCount(bytes, "backup objects", 1);
        List<BackupManifestObject> objects = new ArrayList<>(objectCount);
        for (int index = 0; index < objectCount; index++) {
            String path = getString(bytes, false);
            BackupObjectKind kind = BackupObjectKind.fromCode(Byte.toUnsignedInt(bytes.get()));
            if (bytes.get() != 0) throw new IllegalArgumentException("nonzero object reserved byte");
            int requiredFormatVersion = Short.toUnsignedInt(bytes.getShort());
            long length = bytes.getLong();
            byte[] checksum = new byte[BackupManifestObject.SHA256_BYTES];
            bytes.get(checksum);
            String encryption = getString(bytes, true);
            objects.add(
                    new BackupManifestObject(
                            path, kind, length, checksum, encryption, requiredFormatVersion));
        }
        if (bytes.position() != encoded.length - 4)
            throw new IllegalArgumentException("trailing backup manifest bytes");
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        backupId,
                        created,
                        aetherVersion,
                        formatEpoch,
                        databaseId,
                        clusterId,
                        raftIndex,
                        raftTerm,
                        sequence,
                        hashAlgorithm,
                        objects,
                        epochs,
                        fingerprint);
        if (manifest.objectCount() != expectedObjectCount || manifest.totalBytes() != expectedTotalBytes)
            throw new IllegalArgumentException("backup manifest totals mismatch");
        return manifest;
    }

    private static void validateObjectInventory(List<BackupManifestObject> objects) {
        Set<String> paths = new HashSet<>();
        for (BackupManifestObject object : objects)
            if (!paths.add(object.path()))
                throw new IllegalArgumentException("duplicate backup object path");
    }

    private static String validateText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid " + field);
        return value;
    }

    private static void requireNonzero(UUID id, String field) {
        Objects.requireNonNull(id, field);
        if (id.getMostSignificantBits() == 0 && id.getLeastSignificantBits() == 0)
            throw new IllegalArgumentException(field + " must be nonzero");
    }

    private static int sized(byte[] value) {
        return Integer.BYTES + value.length;
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void putString(ByteBuffer bytes, byte[] value) {
        bytes.putInt(value.length).put(value);
    }

    private static String getString(ByteBuffer bytes, boolean allowEmpty) {
        int length = readCount(bytes, "string");
        if ((!allowEmpty && length == 0) || length > bytes.remaining())
            throw new IllegalArgumentException("invalid backup manifest string");
        byte[] value = new byte[length];
        bytes.get(value);
        return new String(value, StandardCharsets.UTF_8);
    }

    private static int readCount(ByteBuffer bytes, String field) {
        if (bytes.remaining() < Integer.BYTES)
            throw new IllegalArgumentException("truncated backup manifest " + field);
        int count = bytes.getInt();
        if (count < 0 || count > bytes.remaining())
            throw new IllegalArgumentException("invalid backup manifest " + field);
        return count;
    }

    private static int readElementCount(ByteBuffer bytes, String field, int minimumBytesPerElement) {
        int count = readCount(bytes, field);
        if (count > bytes.remaining() / minimumBytesPerElement)
            throw new IllegalArgumentException("invalid backup manifest " + field);
        return count;
    }

    private static void putUuid(ByteBuffer bytes, UUID id) {
        bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer bytes) {
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
