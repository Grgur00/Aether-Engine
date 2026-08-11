package io.aetherdb.wal.format;

import io.aetherdb.format.checksum.MaskedCrc32c;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Exact 32 KiB WAL segment header block codec.
 *
 * @param databaseId owning database identity
 * @param segmentNumber positive segment number
 * @param previousSegmentNumber preceding segment, or zero
 * @param firstSequence first sequence eligible for this segment
 * @param creationEpochMillis creation time in epoch milliseconds
 */
public record WalSegmentHeader(
        UUID databaseId,
        long segmentNumber,
        long previousSegmentNumber,
        long firstSequence,
        long creationEpochMillis) {
    private static final byte[] MAGIC = "AETHWAL1".getBytes(StandardCharsets.US_ASCII);

    /**
     * Encodes and validates this header as a padded header block.
     *
     * @return newly allocated header block
     */
    public byte[] encodeBlock() {
        if (databaseId == null
                || segmentNumber <= 0
                || previousSegmentNumber < 0
                || firstSequence <= 0
                || creationEpochMillis < 0)
            throw new IllegalArgumentException("invalid segment header");
        byte[] block = new byte[WalFormatV1.HEADER_BLOCK_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put(MAGIC)
                .putShort((short) 1)
                .putShort((short) 96)
                .putInt(WalFormatV1.BLOCK_BYTES)
                .putLong(WalFormatV1.SEGMENT_CAPACITY)
                .putLong(databaseId.getMostSignificantBits())
                .putLong(databaseId.getLeastSignificantBits())
                .putLong(segmentNumber)
                .putLong(previousSegmentNumber)
                .putLong(firstSequence)
                .putLong(creationEpochMillis)
                .putInt(0)
                .put(new byte[16]);
        bytes.putInt(MaskedCrc32c.masked(block, 0, 92));
        return block;
    }

    /**
     * Decodes and validates a header against expected durable identity.
     *
     * @param block complete header block
     * @param expectedDatabase expected database UUID
     * @param expectedSegment expected segment number
     * @return decoded header
     */
    public static WalSegmentHeader decode(
            byte[] block, UUID expectedDatabase, long expectedSegment) {
        if (block.length != WalFormatV1.HEADER_BLOCK_BYTES)
            throw corrupt("wrong header block size");
        if (!Arrays.equals(Arrays.copyOf(block, 8), MAGIC)) throw corrupt("bad WAL magic");
        ByteBuffer bytes = ByteBuffer.wrap(block).order(ByteOrder.LITTLE_ENDIAN);
        bytes.position(8);
        if (bytes.getShort() != 1
                || bytes.getShort() != 96
                || bytes.getInt() != WalFormatV1.BLOCK_BYTES
                || bytes.getLong() != WalFormatV1.SEGMENT_CAPACITY)
            throw corrupt("unsupported WAL header");
        UUID id = new UUID(bytes.getLong(), bytes.getLong());
        long segment = bytes.getLong(),
                previous = bytes.getLong(),
                first = bytes.getLong(),
                created = bytes.getLong();
        if (bytes.getInt() != 0) throw corrupt("unknown WAL flags");
        for (int index = 76; index < 92; index++)
            if (block[index] != 0) throw corrupt("non-zero header reserved bytes");
        bytes.position(92);
        if (bytes.getInt() != MaskedCrc32c.masked(block, 0, 92))
            throw corrupt("WAL header checksum mismatch");
        for (int index = 96; index < block.length; index++)
            if (block[index] != 0) throw corrupt("non-zero header block padding");
        if (!id.equals(expectedDatabase)
                || segment != expectedSegment
                || segment <= 0
                || previous < 0
                || first <= 0
                || created < 0) throw corrupt("WAL header identity mismatch");
        return new WalSegmentHeader(id, segment, previous, first, created);
    }

    private static WalCorruptionException corrupt(String message) {
        return new WalCorruptionException(message);
    }
}
