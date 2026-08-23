package io.aetherdb.crypto;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** AES-GCM encryption helper for portable backup archive objects. */
public final class BackupObjectAead {
    public static final String METADATA_PREFIX = "aether-backup-object-v1";
    public static final String ALGORITHM = "AES-256-GCM";

    private BackupObjectAead() {}

    /**
     * Encrypts one backup object with AAD bound to backup identity and object path.
     *
     * @param backupId backup manifest identity
     * @param objectPath manifest object path
     * @param key 32-byte AES-GCM data key
     * @param keyEpoch active key epoch
     * @param plaintext object bytes before encryption
     * @return encoded encrypted object and manifest metadata reference
     */
    public static EncryptedBackupObject encrypt(
            UUID backupId, String objectPath, byte[] key, long keyEpoch, byte[] plaintext) {
        String normalizedPath = normalizePath(objectPath);
        AeadEnvelope envelope =
                AetherAead.encrypt(key, keyEpoch, plaintext, aad(backupId, normalizedPath));
        return new EncryptedBackupObject(metadataReference(keyEpoch), envelope.encode());
    }

    /**
     * Decrypts one backup object using AAD bound to backup identity and object path.
     *
     * @param backupId backup manifest identity
     * @param objectPath manifest object path
     * @param key 32-byte AES-GCM data key
     * @param encodedEnvelope encoded archive payload
     * @return plaintext object bytes
     */
    public static byte[] decrypt(
            UUID backupId, String objectPath, byte[] key, byte[] encodedEnvelope) {
        String normalizedPath = normalizePath(objectPath);
        AeadEnvelope envelope = AeadEnvelope.decode(encodedEnvelope);
        return AetherAead.decrypt(key, envelope, aad(backupId, normalizedPath));
    }

    /** Returns the stable manifest encryption metadata reference for a key epoch. */
    public static String metadataReference(long keyEpoch) {
        if (keyEpoch <= 0) throw new IllegalArgumentException("key epoch must be positive");
        return METADATA_PREFIX + ":alg=" + ALGORITHM + ":key_epoch=" + keyEpoch;
    }

    private static byte[] aad(UUID backupId, String objectPath) {
        Objects.requireNonNull(backupId, "backupId");
        return (METADATA_PREFIX + '\0' + backupId + '\0' + objectPath)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static String normalizePath(String objectPath) {
        Objects.requireNonNull(objectPath, "objectPath");
        String normalized = objectPath.replace('\\', '/');
        if (normalized.isBlank()
                || normalized.startsWith("/")
                || normalized.endsWith("/")
                || normalized.contains("//")
                || normalized.contains("\0")) {
            throw new IllegalArgumentException("invalid backup object path");
        }
        for (String part : normalized.split("/")) {
            if (part.equals(".") || part.equals(".."))
                throw new IllegalArgumentException("unsafe backup object path");
        }
        return normalized;
    }
}
