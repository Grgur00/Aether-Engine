package io.aetherdb.sstable;

import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.sstable.block.BlockHandle;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/**
 * Fixed 128-byte SSTable footer v1 with mandatory metadata handles.
 *
 * @param metaindex metaindex block handle
 * @param index data-block index handle
 * @param filter Bloom-filter block handle
 * @param properties properties block handle
 * @param fileNumber positive durable file number
 * @param fileSize exact final file size
 * @param databaseId owning database identity
 */
public record SSTableFooterV1(
        BlockHandle metaindex,
        BlockHandle index,
        BlockHandle filter,
        BlockHandle properties,
        long fileNumber,
        long fileSize,
        UUID databaseId) {
    /** Fixed footer size. */
    public static final int FOOTER_BYTES = 128;

    /** Validates required footer values. */
    public SSTableFooterV1 {
        if (metaindex == null
                || index == null
                || filter == null
                || properties == null
                || fileNumber <= 0
                || fileSize < SSTableHeaderV1.HEADER_REGION_BYTES + FOOTER_BYTES
                || databaseId == null) throw new IllegalArgumentException("invalid SSTable footer");
    }

    /**
     * Encodes this footer.
     *
     * @return canonical fixed footer bytes
     */
    public byte[] encode() {
        byte[] encoded = new byte[FOOTER_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("AETHFTR1".getBytes(StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) FOOTER_BYTES)
                .putInt(0)
                .put(metaindex.encode())
                .put(index.encode())
                .put(filter.encode())
                .put(properties.encode())
                .putLong(fileNumber)
                .putLong(fileSize)
                .putLong(databaseId.getMostSignificantBits())
                .putLong(databaseId.getLeastSignificantBits());
        bytes.putInt(124, MaskedCrc32c.masked(encoded, 0, 124));
        return encoded;
    }

    /**
     * Decodes and validates footer bytes.
     *
     * @param encoded exact footer bytes
     * @return decoded footer
     */
    public static SSTableFooterV1 decode(byte[] encoded) {
        if (encoded == null || encoded.length != FOOTER_BYTES)
            throw corrupt("invalid footer length");
        if (ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN).getInt(124)
                != MaskedCrc32c.masked(encoded, 0, 124)) throw corrupt("footer checksum mismatch");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!Arrays.equals(magic, "AETHFTR1".getBytes(StandardCharsets.US_ASCII))
                || Short.toUnsignedInt(bytes.getShort()) != 1
                || Short.toUnsignedInt(bytes.getShort()) != FOOTER_BYTES
                || bytes.getInt() != 0) throw corrupt("invalid footer prefix");
        BlockHandle metaindex = handle(bytes),
                index = handle(bytes),
                filter = handle(bytes),
                properties = handle(bytes);
        long fileNumber = bytes.getLong(), fileSize = bytes.getLong();
        UUID databaseId = new UUID(bytes.getLong(), bytes.getLong());
        for (int position = 112; position < 124; position++)
            if (encoded[position] != 0) throw corrupt("nonzero footer reserved byte");
        try {
            return new SSTableFooterV1(
                    metaindex, index, filter, properties, fileNumber, fileSize, databaseId);
        } catch (IllegalArgumentException failure) {
            throw corrupt("invalid footer fields");
        }
    }

    /** Validates all mandatory handles and rejects overlap. */
    public void validateHandles() {
        long footerOffset = fileSize - FOOTER_BYTES;
        BlockHandle[] handles = {metaindex, index, filter, properties};
        for (BlockHandle handle : handles) handle.validateWithin(footerOffset);
        for (int left = 0; left < handles.length; left++)
            for (int right = left + 1; right < handles.length; right++) {
                if (overlap(handles[left], handles[right]))
                    throw corrupt("mandatory metadata blocks overlap");
            }
    }

    private static BlockHandle handle(ByteBuffer bytes) {
        byte[] encoded = new byte[BlockHandle.ENCODED_BYTES];
        bytes.get(encoded);
        return BlockHandle.decode(encoded);
    }

    private static boolean overlap(BlockHandle left, BlockHandle right) {
        return left.offset() < right.offset() + right.length()
                && right.offset() < left.offset() + left.length();
    }

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }
}
