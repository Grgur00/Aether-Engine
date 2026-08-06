package io.aetherdb.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** SSTable internal-key v1 codec and semantic comparator. */
public final class InternalKey implements Comparable<InternalKey> {
    private final byte[] userKey;
    private final long sequence;
    private final byte type;

    /** Creates an internal key.
     * @param userKey logical key bytes
     * @param sequence positive MVCC sequence
     * @param type durable value or tombstone type code */
    public InternalKey(byte[] userKey, long sequence, byte type) {
        if (userKey == null || userKey.length > 65_536 || sequence <= 0 || type < 1 || type > 2)
            throw new IllegalArgumentException("invalid internal key");
        this.userKey = userKey.clone(); this.sequence = sequence; this.type = type;
    }

    /** Encodes the user key and fixed-width trailer.
     * @return newly allocated internal-key bytes */
    public byte[] encode() {
        ByteBuffer bytes = ByteBuffer.allocate(userKey.length + 9).order(ByteOrder.LITTLE_ENDIAN);
        return bytes.put(userKey).putLong(sequence).put(type).array();
    }

    /** Decodes and validates an internal key.
     * @param encoded internal-key bytes
     * @return decoded key */
    public static InternalKey decode(byte[] encoded) {
        if (encoded == null || encoded.length < 9) throw new SSTableCorruptionException("internal key too short");
        int userLength = encoded.length - 9;
        ByteBuffer trailer = ByteBuffer.wrap(encoded, userLength, 9).order(ByteOrder.LITTLE_ENDIAN);
        try { return new InternalKey(Arrays.copyOf(encoded, userLength), trailer.getLong(), trailer.get()); }
        catch (IllegalArgumentException failure) { throw new SSTableCorruptionException("invalid internal key"); }
    }

    @Override public int compareTo(InternalKey other) {
        int key = compare(userKey, 0, userKey.length, other.userKey, 0, other.userKey.length);
        if (key != 0) return key;
        int sequenceOrder = Long.compare(other.sequence, sequence);
        return sequenceOrder != 0 ? sequenceOrder : Integer.compare(Byte.toUnsignedInt(type), Byte.toUnsignedInt(other.type));
    }

    /** Compares two byte ranges using unsigned lexicographical ordering. */
    public static int compare(byte[] left, int leftOffset, int leftLength, byte[] right, int rightOffset, int rightLength) {
        if (left == null || right == null) throw new IllegalArgumentException("keys must not be null");
        int limit = Math.min(leftLength, rightLength);
        for (int index = 0; index < limit; index++) {
            int leftByte = Byte.toUnsignedInt(left[leftOffset + index]);
            int rightByte = Byte.toUnsignedInt(right[rightOffset + index]);
            if (leftByte != rightByte) return leftByte < rightByte ? -1 : 1;
        }
        return Integer.compare(leftLength, rightLength);
    }

    /** Compares an encoded internal key's user-key portion against a raw user key without allocating. */
    public static int compareUserKey(byte[] encodedInternalKey, byte[] userKey) {
        if (encodedInternalKey == null || userKey == null) throw new IllegalArgumentException("keys must not be null");
        int userLength = encodedInternalKey.length - 9;
        return compare(encodedInternalKey, 0, userLength, userKey, 0, userKey.length);
    }

    /** Compares two encoded internal keys with the SSTable ordering semantics. */
    public static int compareEncoded(byte[] left, byte[] right) {
        if (left == null || right == null) throw new IllegalArgumentException("keys must not be null");
        int userOrder = compare(left, 0, left.length - 9, right, 0, right.length - 9);
        if (userOrder != 0) return userOrder;
        long leftSequence = sequence(left, left.length - 9);
        long rightSequence = sequence(right, right.length - 9);
        int sequenceOrder = Long.compare(rightSequence, leftSequence);
        return sequenceOrder != 0 ? sequenceOrder : Integer.compare(Byte.toUnsignedInt(left[left.length - 1]), Byte.toUnsignedInt(right[right.length - 1]));
    }

    private static long sequence(byte[] encoded, int userLength) {
        return ByteBuffer.wrap(encoded, userLength, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    /** Returns the logical key.
     * @return defensive user-key copy */
    public byte[] userKey() { return userKey.clone(); }
    /** Returns the MVCC sequence.
     * @return positive sequence */
    public long sequence() { return sequence; }
    /** Returns the value kind.
     * @return durable type code */
    public byte type() { return type; }

    /** Lightweight view over a byte array range, used by hot-path comparisons. */
    public record ByteSlice(byte[] array, int offset, int length) {
        public ByteSlice {
            if (array == null) throw new IllegalArgumentException("byte slice array must not be null");
            if (offset < 0 || length < 0 || offset + length > array.length) throw new IllegalArgumentException("invalid byte slice");
        }
    }
}
