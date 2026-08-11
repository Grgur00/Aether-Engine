package io.aetherdb.io;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Exact 128-byte DB-IDENTITY format v1 codec.
 *
 * @param databaseId durable non-zero database identity
 * @param creationEpochMillis database creation time in Unix epoch milliseconds
 * @param creatorMajor creator's major software version
 * @param creatorMinor creator's minor software version
 */
public record DatabaseIdentityV1(
        UUID databaseId, long creationEpochMillis, int creatorMajor, int creatorMinor) {
    /** Encoded DB-IDENTITY length in bytes. */
    public static final int ENCODED_LENGTH = 128;

    private static final byte[] MAGIC = "AETHDBI1".getBytes(StandardCharsets.US_ASCII);

    /** Validates identity fields before the record becomes observable. */
    public DatabaseIdentityV1 {
        if (databaseId == null
                || databaseId.getMostSignificantBits() == 0
                        && databaseId.getLeastSignificantBits() == 0)
            throw new IllegalArgumentException("database UUID must be nonzero");
        if (creationEpochMillis < 0 || creatorMajor < 0 || creatorMinor < 0)
            throw new IllegalArgumentException("identity values must be non-negative");
    }

    /**
     * Encodes this identity using the exact v1 layout and masked CRC32C trailer.
     *
     * @return newly allocated 128-byte representation
     */
    public byte[] encode() {
        byte[] encoded = new byte[ENCODED_LENGTH];
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(MAGIC).putShort((short) 1).putShort((short) ENCODED_LENGTH).putInt(0);
        bytes.putLong(databaseId.getMostSignificantBits())
                .putLong(databaseId.getLeastSignificantBits());
        bytes.putLong(creationEpochMillis).putLong(1);
        bytes.putLong(
                (Integer.toUnsignedLong(creatorMajor) << 32)
                        | Integer.toUnsignedLong(creatorMinor));
        bytes.position(124).putInt(MaskedCrc32c.masked(encoded, 0, 124));
        return encoded;
    }

    /**
     * Validates and decodes an exact v1 identity image.
     *
     * @param encoded encoded identity bytes
     * @return decoded identity
     * @throws IllegalArgumentException if length, header, reserved bytes, or checksum are invalid
     */
    public static DatabaseIdentityV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_LENGTH)
            throw new IllegalArgumentException("DB-IDENTITY must be exactly 128 bytes");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC)
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != ENCODED_LENGTH
                || bytes.getInt() != 0)
            throw new IllegalArgumentException("invalid DB-IDENTITY header");
        UUID id = new UUID(bytes.getLong(), bytes.getLong());
        long created = bytes.getLong();
        if (bytes.getLong() != 1) throw new IllegalArgumentException("unsupported format epoch");
        long creator = bytes.getLong();
        for (int index = 56; index < 124; index++)
            if (encoded[index] != 0)
                throw new IllegalArgumentException("nonzero DB-IDENTITY reserved byte");
        int storedCrc = ByteBuffer.wrap(encoded, 124, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
        if (storedCrc != MaskedCrc32c.masked(encoded, 0, 124))
            throw new IllegalArgumentException("DB-IDENTITY checksum mismatch");
        return new DatabaseIdentityV1(id, created, (int) (creator >>> 32), (int) creator);
    }
}
