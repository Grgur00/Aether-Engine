package io.aetherdb.io;

import java.util.Arrays;
import java.util.Objects;

/**
 * One immutable object referenced by a portable backup manifest.
 *
 * @param path slash-separated relative object path
 * @param kind durable object class
 * @param length exact object length in bytes
 * @param checksum hash bytes using the manifest hash algorithm
 * @param encryptionMetadataReference optional encryption metadata reference, empty for plaintext
 * @param requiredFormatVersion minimum object decoder version required for restore
 */
public record BackupManifestObject(
        String path,
        BackupObjectKind kind,
        long length,
        byte[] checksum,
        String encryptionMetadataReference,
        int requiredFormatVersion) {
    public static final int SHA256_BYTES = 32;

    /** Validates fields and takes a defensive checksum copy. */
    public BackupManifestObject {
        path = validatePath(path);
        Objects.requireNonNull(kind, "kind");
        if (length < 0) throw new IllegalArgumentException("negative backup object length");
        if (checksum == null || checksum.length != SHA256_BYTES)
            throw new IllegalArgumentException("backup object checksum must be SHA-256");
        checksum = checksum.clone();
        encryptionMetadataReference =
                encryptionMetadataReference == null ? "" : encryptionMetadataReference;
        if (encryptionMetadataReference.indexOf('\0') >= 0)
            throw new IllegalArgumentException("invalid encryption metadata reference");
        if (requiredFormatVersion <= 0 || requiredFormatVersion > 0xffff)
            throw new IllegalArgumentException("invalid required format version");
    }

    @Override
    public byte[] checksum() {
        return checksum.clone();
    }

    private static String validatePath(String path) {
        Objects.requireNonNull(path, "path");
        String normalized = path.replace('\\', '/');
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

    boolean checksumEquals(byte[] other) {
        return Arrays.equals(checksum, other);
    }
}
