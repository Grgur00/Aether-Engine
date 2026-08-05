package io.aetherdb.sstable;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** SSTable internal-key v1 codec and semantic comparator. */
public final class InternalKey implements Comparable<InternalKey> {
    private final byte[] userKey;
    private final long sequence;
    private final byte type;

    public InternalKey(byte[] userKey, long sequence, byte type) {
        if (userKey == null || userKey.length > 65_536 || sequence <= 0 || type < 1 || type > 2)
            throw new IllegalArgumentException("invalid internal key");
        this.userKey = userKey.clone(); this.sequence = sequence; this.type = type;
    }

    public byte[] encode() {
        ByteBuffer bytes = ByteBuffer.allocate(userKey.length + 9).order(ByteOrder.LITTLE_ENDIAN);
        return bytes.put(userKey).putLong(sequence).put(type).array();
    }

    public static InternalKey decode(byte[] encoded) {
        if (encoded == null || encoded.length < 9) throw new SSTableCorruptionException("internal key too short");
        int userLength = encoded.length - 9;
        ByteBuffer trailer = ByteBuffer.wrap(encoded, userLength, 9).order(ByteOrder.LITTLE_ENDIAN);
        try { return new InternalKey(Arrays.copyOf(encoded, userLength), trailer.getLong(), trailer.get()); }
        catch (IllegalArgumentException failure) { throw new SSTableCorruptionException("invalid internal key"); }
    }

    @Override public int compareTo(InternalKey other) {
        int key = Arrays.compareUnsigned(userKey, other.userKey);
        if (key != 0) return key;
        int sequenceOrder = Long.compare(other.sequence, sequence);
        return sequenceOrder != 0 ? sequenceOrder : Integer.compare(Byte.toUnsignedInt(type), Byte.toUnsignedInt(other.type));
    }
    public byte[] userKey() { return userKey.clone(); }
    public long sequence() { return sequence; }
    public byte type() { return type; }
}
