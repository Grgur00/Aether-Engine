package io.aetherdb.io;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.UUID;

/**
 * Exact immutable 4 KiB FORMAT-OPTIONS v1 codec and compatibility fingerprint.
 *
 * @param databaseId durable non-zero database identity
 * @param creationEpochMillis database creation time in Unix epoch milliseconds
 */
public record FormatOptionsV1(UUID databaseId, long creationEpochMillis) {
    /** Total encoded file length in bytes. */
    public static final int ENCODED_LENGTH = 4_096;

    /** Checksummed header length in bytes. */
    public static final int HEADER_LENGTH = 512;

    private static final byte[] MAGIC = "AETHFMT1".getBytes(StandardCharsets.US_ASCII);

    /** Validates database identity and creation time. */
    public FormatOptionsV1 {
        if (databaseId == null
                || databaseId.getMostSignificantBits() == 0
                        && databaseId.getLeastSignificantBits() == 0)
            throw new IllegalArgumentException("database UUID must be nonzero");
        if (creationEpochMillis < 0)
            throw new IllegalArgumentException("creation time must be non-negative");
    }

    /**
     * Encodes this options vector into its exact durable representation.
     *
     * @return newly allocated 4 KiB options image
     */
    public byte[] encode() {
        byte[] encoded = new byte[ENCODED_LENGTH];
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(MAGIC).putShort((short) 1).putShort((short) HEADER_LENGTH).putInt(ENCODED_LENGTH);
        bytes.putLong(databaseId.getMostSignificantBits())
                .putLong(databaseId.getLeastSignificantBits());
        putFormatVector(bytes);
        bytes.position(120).putLong(creationEpochMillis);
        byte[] fingerprint = compatibilityFingerprint();
        bytes.position(128).put(fingerprint);
        bytes.position(508).putInt(MaskedCrc32c.masked(encoded, 0, 508));
        return encoded;
    }

    /**
     * Computes the SHA-256 fingerprint of format-affecting constants.
     *
     * @return newly allocated 32-byte compatibility fingerprint
     */
    public byte[] compatibilityFingerprint() {
        ByteBuffer vector = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        vector.put("AETHER-COMPAT-V1".getBytes(StandardCharsets.US_ASCII));
        putFormatVector(vector);
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(Arrays.copyOf(vector.array(), vector.position()));
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    /**
     * Validates and decodes a durable options image.
     *
     * @param encoded encoded FORMAT-OPTIONS bytes
     * @return decoded options
     * @throws IllegalArgumentException if the image is incompatible or corrupt
     */
    public static FormatOptionsV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_LENGTH)
            throw new IllegalArgumentException("FORMAT-OPTIONS must be exactly 4096 bytes");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!Arrays.equals(magic, MAGIC)
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != HEADER_LENGTH
                || bytes.getInt() != ENCODED_LENGTH)
            throw new IllegalArgumentException("invalid FORMAT-OPTIONS header");
        UUID id = new UUID(bytes.getLong(), bytes.getLong());
        verifyFormatVector(bytes);
        long created = bytes.position(120).getLong();
        byte[] storedFingerprint = new byte[32];
        bytes.position(128).get(storedFingerprint);
        for (int index = 160; index < 508; index++)
            if (encoded[index] != 0)
                throw new IllegalArgumentException("nonzero FORMAT-OPTIONS reserved byte");
        for (int index = 512; index < encoded.length; index++)
            if (encoded[index] != 0)
                throw new IllegalArgumentException("nonzero FORMAT-OPTIONS tail byte");
        int storedCrc = ByteBuffer.wrap(encoded, 508, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (storedCrc != MaskedCrc32c.masked(encoded, 0, 508))
            throw new IllegalArgumentException("FORMAT-OPTIONS checksum mismatch");
        FormatOptionsV1 options = new FormatOptionsV1(id, created);
        if (!MessageDigest.isEqual(storedFingerprint, options.compatibilityFingerprint()))
            throw new IllegalArgumentException("FORMAT-OPTIONS compatibility fingerprint mismatch");
        return options;
    }

    private static void putFormatVector(ByteBuffer bytes) {
        bytes.putLong(1).putLong(0).putInt(1);
        bytes.putShort((short) 1).putShort((short) 1).putShort((short) 1).putShort((short) 1);
        bytes.putShort((short) 1).putShort((short) 2).putShort((short) 1).putShort((short) 1);
        bytes.putShort((short) 1).putShort((short) 1).putShort((short) 7).putShort((short) 32);
        bytes.putInt(65_536).putInt(16_777_216).putInt(32_768).putLong(67_108_864L);
        bytes.putLong(1_073_741_824L).putInt(8).putInt(128).putInt(9).putInt(0);
    }

    private static void verifyFormatVector(ByteBuffer bytes) {
        ByteBuffer expected = ByteBuffer.allocate(88).order(ByteOrder.LITTLE_ENDIAN);
        putFormatVector(expected);
        byte[] actual = new byte[88];
        bytes.position(32).get(actual);
        if (!Arrays.equals(actual, expected.array()))
            throw new IllegalArgumentException("unsupported FORMAT-OPTIONS vector");
    }
}
