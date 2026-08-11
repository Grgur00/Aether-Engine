package io.aetherdb.memtable.reference;

import java.util.Comparator;

/** Unsigned lexicographic byte-array ordering. */
public final class UnsignedBytes {
    public static final Comparator<byte[]> COMPARATOR = UnsignedBytes::compare;

    private UnsignedBytes() {}

    public static int compare(byte[] left, byte[] right) {
        int limit = Math.min(left.length, right.length);
        for (int index = 0; index < limit; index++) {
            int comparison =
                    Integer.compare(
                            Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }
}
