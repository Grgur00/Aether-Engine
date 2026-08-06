package io.aetherdb.sstable.block;

import io.aetherdb.sstable.SSTableCorruptionException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Exact physical location of an SSTable block including its trailer.
 *
 * @param offset nonnegative byte offset in the table file
 * @param length physical block length including the trailer
 */
public record BlockHandle(long offset, int length) {
    /** Encoded block-handle size. */
    public static final int ENCODED_BYTES = 16;

    /** Validates intrinsic handle bounds. */
    public BlockHandle {
        if (offset < 0 || length < BlockEnvelope.TRAILER_BYTES) {
            throw new IllegalArgumentException("invalid block handle");
        }
    }

    /**
     * Encodes this handle.
     *
     * @return canonical 16-byte little-endian representation
     */
    public byte[] encode() {
        return ByteBuffer.allocate(ENCODED_BYTES).order(ByteOrder.LITTLE_ENDIAN)
                .putLong(offset).putInt(length).putInt(0).array();
    }

    /**
     * Decodes a canonical handle.
     *
     * @param encoded exact handle bytes
     * @return decoded handle
     */
    public static BlockHandle decode(byte[] encoded) {
        if (encoded == null || encoded.length != ENCODED_BYTES) throw corrupt("invalid handle length");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN);
        long offset = bytes.getLong();
        int length = bytes.getInt();
        if (bytes.getInt() != 0 || offset < 0 || length < BlockEnvelope.TRAILER_BYTES) {
            throw corrupt("invalid block handle");
        }
        return new BlockHandle(offset, length);
    }

    /**
     * Validates that this handle addresses a block before the footer.
     *
     * @param footerOffset first footer byte
     */
    public void validateWithin(long footerOffset) {
        long end;
        try { end = Math.addExact(offset, length); }
        catch (ArithmeticException failure) { throw corrupt("block handle overflows"); }
        if (offset < 4_096 || end > footerOffset) throw corrupt("block handle outside table body");
    }

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }
}
