package io.aetherdb.sstable.block;

import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.sstable.SSTableCorruptionException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** Generic SSTable block trailer and checksum codec. */
public final class BlockEnvelope {
    /** Fixed trailer size following every raw block. */
    public static final int TRAILER_BYTES = 8;
    private BlockEnvelope() {}

    /**
     * Appends a v1 uncompressed block trailer.
     *
     * @param raw raw block contents
     * @param kind persistent block kind
     * @return physical block bytes including trailer
     */
    public static byte[] encode(byte[] raw, BlockKind kind) {
        if (raw == null || kind == null) throw new IllegalArgumentException("raw block and kind are required");
        byte[] physical = Arrays.copyOf(raw, Math.addExact(raw.length, TRAILER_BYTES));
        int trailer = raw.length;
        physical[trailer] = 0;
        physical[trailer + 1] = (byte) kind.code();
        physical[trailer + 2] = 1;
        physical[trailer + 3] = 0;
        ByteBuffer.wrap(physical).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(trailer + 4, MaskedCrc32c.masked(physical, 0, raw.length + 4));
        return physical;
    }

    /**
     * Validates and removes a block trailer.
     *
     * @param physical raw contents followed by trailer
     * @param expectedKind context-required kind
     * @return copied raw block contents
     */
    public static byte[] decode(byte[] physical, BlockKind expectedKind) {
        if (physical == null || physical.length < TRAILER_BYTES || expectedKind == null) throw corrupt("block too short");
        int trailer = physical.length - TRAILER_BYTES;
        int compression = Byte.toUnsignedInt(physical[trailer]);
        BlockKind actual = BlockKind.fromCode(Byte.toUnsignedInt(physical[trailer + 1]));
        int version = Byte.toUnsignedInt(physical[trailer + 2]);
        int flags = Byte.toUnsignedInt(physical[trailer + 3]);
        if (compression != 0 || actual != expectedKind || version != 1 || flags != 0) {
            throw corrupt("invalid block trailer metadata");
        }
        int stored = ByteBuffer.wrap(physical).order(ByteOrder.LITTLE_ENDIAN).getInt(trailer + 4);
        if (stored != MaskedCrc32c.masked(physical, 0, trailer + 4)) throw corrupt("block checksum mismatch");
        return Arrays.copyOf(physical, trailer);
    }

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }
}
