package io.aetherdb.sstable.block;

import io.aetherdb.sstable.SSTableCorruptionException;
import java.io.ByteArrayOutputStream;

/** Canonical unsigned nonnegative varint32. */
public final class Varint32 {
    private Varint32() {}
    public static byte[] encode(int value) {
        if (value < 0) throw new IllegalArgumentException("varint must be nonnegative");
        ByteArrayOutputStream output = new ByteArrayOutputStream(5);
        int remaining = value;
        while ((remaining & ~0x7f) != 0) { output.write((remaining & 0x7f) | 0x80); remaining >>>= 7; }
        output.write(remaining); return output.toByteArray();
    }
    public static Decoded decode(byte[] bytes, int offset, int limit) {
        long value = 0;
        for (int index = 0; index < 5 && offset + index < limit; index++) {
            int current = Byte.toUnsignedInt(bytes[offset + index]);
            value |= (long) (current & 0x7f) << (7 * index);
            if ((current & 0x80) == 0) {
                if (value > Integer.MAX_VALUE) throw corrupt();
                int decoded = (int) value;
                if (encode(decoded).length != index + 1) throw new SSTableCorruptionException("noncanonical varint");
                return new Decoded(decoded, index + 1);
            }
        }
        throw corrupt();
    }
    private static SSTableCorruptionException corrupt() { return new SSTableCorruptionException("invalid varint32"); }
    public record Decoded(int value, int bytes) {}
}
