package io.aetherdb.sstable.block;

import io.aetherdb.sstable.SSTableCorruptionException;

import java.io.ByteArrayOutputStream;

/** Canonical unsigned nonnegative varint32. */
public final class Varint32 {
    private Varint32() {}

    /**
     * Encodes a non-negative integer canonically.
     *
     * @param value value to encode
     * @return one to five varint bytes
     */
    public static byte[] encode(int value) {
        if (value < 0) throw new IllegalArgumentException("varint must be nonnegative");
        ByteArrayOutputStream output = new ByteArrayOutputStream(5);
        int remaining = value;
        while ((remaining & ~0x7f) != 0) {
            output.write((remaining & 0x7f) | 0x80);
            remaining >>>= 7;
        }
        output.write(remaining);
        return output.toByteArray();
    }

    /**
     * Decodes one canonical varint within explicit array bounds.
     *
     * @param bytes source bytes
     * @param offset first encoded byte
     * @param limit exclusive decoding limit
     * @return decoded value and consumed byte count
     */
    public static Decoded decode(byte[] bytes, int offset, int limit) {
        long value = 0;
        for (int index = 0; index < 5 && offset + index < limit; index++) {
            int current = Byte.toUnsignedInt(bytes[offset + index]);
            value |= (long) (current & 0x7f) << (7 * index);
            if ((current & 0x80) == 0) {
                if (value > Integer.MAX_VALUE) throw corrupt();
                int decoded = (int) value;
                if (encode(decoded).length != index + 1)
                    throw new SSTableCorruptionException("noncanonical varint");
                return new Decoded(decoded, index + 1);
            }
        }
        throw corrupt();
    }

    private static SSTableCorruptionException corrupt() {
        return new SSTableCorruptionException("invalid varint32");
    }

    /**
     * Decoded varint and its encoded length.
     *
     * @param value decoded non-negative value
     * @param bytes consumed byte count
     */
    public record Decoded(int value, int bytes) {}
}
