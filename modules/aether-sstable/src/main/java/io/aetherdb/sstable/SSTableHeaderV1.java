package io.aetherdb.sstable;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Fixed SSTable header v1 stored at the start of a 4 KiB header region.
 *
 * @param fileNumber positive durable file number
 * @param databaseId owning database identity
 * @param entryCount number of internal entries
 * @param smallestSequence smallest entry sequence, or zero for an empty fixture
 * @param largestSequence largest entry sequence, or zero for an empty fixture
 * @param dataBlockCount number of data blocks
 * @param creationEpochMillis diagnostic creation time
 * @param fileSize exact final file size
 */
public record SSTableHeaderV1(
        long fileNumber,
        UUID databaseId,
        long entryCount,
        long smallestSequence,
        long largestSequence,
        int dataBlockCount,
        long creationEpochMillis,
        long fileSize) {
    /** Fixed header size. */
    public static final int HEADER_BYTES = 128;

    /** Reserved header-region size. */
    public static final int HEADER_REGION_BYTES = 4_096;

    /** Target raw data-block size. */
    public static final int TARGET_DATA_BLOCK_BYTES = 16_384;

    /** Prefix-compression restart interval. */
    public static final int RESTART_INTERVAL = 16;

    /** Validates header fields, permitting zero counts only for explicit empty fixtures. */
    public SSTableHeaderV1 {
        if (fileNumber <= 0
                || databaseId == null
                || entryCount < 0
                || smallestSequence < 0
                || largestSequence < smallestSequence
                || dataBlockCount < 0
                || (entryCount == 0) != (dataBlockCount == 0)
                || (entryCount > 0 && smallestSequence == 0)
                || fileSize < HEADER_REGION_BYTES + 128L) {
            throw new IllegalArgumentException("invalid SSTable header fields");
        }
    }

    /**
     * Encodes this header.
     *
     * @return canonical fixed 128-byte header
     */
    public byte[] encode() {
        byte[] encoded = new byte[HEADER_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("AETHSST1".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) HEADER_BYTES)
                .putInt(0)
                .putLong(fileNumber)
                .putLong(databaseId.getMostSignificantBits())
                .putLong(databaseId.getLeastSignificantBits())
                .putInt(1)
                .putInt(TARGET_DATA_BLOCK_BYTES)
                .putInt(RESTART_INTERVAL)
                .putShort((short) 10)
                .put((byte) 7)
                .put((byte) 0)
                .putLong(entryCount)
                .putLong(smallestSequence)
                .putLong(largestSequence)
                .putInt(dataBlockCount)
                .putInt(HEADER_REGION_BYTES)
                .putLong(creationEpochMillis)
                .putLong(fileSize);
        bytes.putInt(124, MaskedCrc32c.masked(encoded, 0, 124));
        return encoded;
    }

    /**
     * Decodes the complete 4 KiB header region and validates reserved bytes.
     *
     * @param region exact header-region bytes
     * @return decoded header
     */
    public static SSTableHeaderV1 decodeRegion(byte[] region) {
        if (region == null || region.length != HEADER_REGION_BYTES)
            throw corrupt("invalid header region length");
        for (int index = HEADER_BYTES; index < region.length; index++)
            if (region[index] != 0) throw corrupt("nonzero header reserved byte");
        byte[] encoded = Arrays.copyOf(region, HEADER_BYTES);
        if (ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(124)
                != MaskedCrc32c.masked(encoded, 0, 124)) throw corrupt("header checksum mismatch");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!Arrays.equals(magic, "AETHSST1".getBytes(StandardCharsets.US_ASCII))
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != HEADER_BYTES
                || bytes.getInt() != 0) throw corrupt("invalid SSTable header prefix");
        long fileNumber = bytes.getLong();
        UUID databaseId = new UUID(bytes.getLong(), bytes.getLong());
        if (bytes.getInt() != 1
                || bytes.getInt() != TARGET_DATA_BLOCK_BYTES
                || bytes.getInt() != RESTART_INTERVAL
                || Short.toUnsignedInt(bytes.getShort()) != 10
                || Byte.toUnsignedInt(bytes.get()) != 7
                || bytes.get() != 0) throw corrupt("unsupported SSTable header options");
        long entries = bytes.getLong(), smallest = bytes.getLong(), largest = bytes.getLong();
        int blocks = bytes.getInt();
        if (bytes.getInt() != HEADER_REGION_BYTES)
            throw corrupt("invalid header region declaration");
        long created = bytes.getLong(), size = bytes.getLong();
        for (int index = 104; index < 124; index++)
            if (encoded[index] != 0) throw corrupt("nonzero header reserved byte");
        try {
            return new SSTableHeaderV1(
                    fileNumber, databaseId, entries, smallest, largest, blocks, created, size);
        } catch (IllegalArgumentException failure) {
            throw corrupt("invalid SSTable header fields");
        }
    }

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }
}
