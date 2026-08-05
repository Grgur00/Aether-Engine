package io.aetherdb.lsm.iterator;

import java.util.Arrays;

/** One immutable VALUE or TOMBSTONE in internal-key order. */
public final class InternalEntry implements Comparable<InternalEntry> {
    public enum Type { VALUE, TOMBSTONE }
    private final byte[] userKey;
    private final long sequence;
    private final Type type;
    private final byte[] value;

    private InternalEntry(byte[] userKey, long sequence, Type type, byte[] value) {
        if (userKey == null || sequence <= 0) throw new IllegalArgumentException("invalid internal entry");
        if (type == Type.VALUE && value == null) throw new IllegalArgumentException("value must not be null");
        this.userKey = userKey.clone(); this.sequence = sequence; this.type = type;
        this.value = value == null ? null : value.clone();
    }
    public static InternalEntry value(byte[] key, long sequence, byte[] value) { return new InternalEntry(key, sequence, Type.VALUE, value); }
    public static InternalEntry tombstone(byte[] key, long sequence) { return new InternalEntry(key, sequence, Type.TOMBSTONE, null); }
    public byte[] userKey() { return userKey.clone(); }
    public long sequence() { return sequence; }
    public Type type() { return type; }
    public byte[] value() { if (type == Type.TOMBSTONE) throw new IllegalStateException("tombstone has no value"); return value.clone(); }
    @Override public int compareTo(InternalEntry other) {
        int key = Arrays.compareUnsigned(userKey, other.userKey);
        if (key != 0) return key;
        int sequenceOrder = Long.compare(other.sequence, sequence);
        return sequenceOrder != 0 ? sequenceOrder : type.compareTo(other.type);
    }
    public boolean sameIdentity(InternalEntry other) {
        return sequence == other.sequence && type == other.type && Arrays.equals(userKey, other.userKey);
    }
}
