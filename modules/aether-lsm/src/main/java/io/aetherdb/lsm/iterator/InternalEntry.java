package io.aetherdb.lsm.iterator;

import java.util.Arrays;

/** One immutable value or tombstone in internal-key order. */
public final class InternalEntry implements Comparable<InternalEntry> {
    /** Internal record kind. */
    public enum Type {
        /** Record contains value bytes. */
        VALUE,
        /** Record masks older values. */
        TOMBSTONE
    }

    private final byte[] userKey;
    private final long sequence;
    private final Type type;
    private final byte[] value;

    private InternalEntry(byte[] userKey, long sequence, Type type, byte[] value) {
        if (userKey == null || sequence <= 0)
            throw new IllegalArgumentException("invalid internal entry");
        if (type == Type.VALUE && value == null)
            throw new IllegalArgumentException("value must not be null");
        this.userKey = userKey.clone();
        this.sequence = sequence;
        this.type = type;
        this.value = value == null ? null : value.clone();
    }

    /**
     * Creates a value entry.
     *
     * @param key user key
     * @param sequence MVCC sequence
     * @param value value bytes
     * @return immutable entry
     */
    public static InternalEntry value(byte[] key, long sequence, byte[] value) {
        return new InternalEntry(key, sequence, Type.VALUE, value);
    }

    /**
     * Creates a deletion marker.
     *
     * @param key user key
     * @param sequence MVCC sequence
     * @return immutable entry
     */
    public static InternalEntry tombstone(byte[] key, long sequence) {
        return new InternalEntry(key, sequence, Type.TOMBSTONE, null);
    }

    /**
     * Returns the user key.
     *
     * @return defensive key copy
     */
    public byte[] userKey() {
        return userKey.clone();
    }

    /**
     * Returns the MVCC sequence.
     *
     * @return positive sequence
     */
    public long sequence() {
        return sequence;
    }

    /**
     * Returns the record kind.
     *
     * @return value or tombstone
     */
    public Type type() {
        return type;
    }

    /**
     * Returns value bytes.
     *
     * @return defensive value copy
     */
    public byte[] value() {
        if (type == Type.TOMBSTONE) throw new IllegalStateException("tombstone has no value");
        return value.clone();
    }

    @Override
    public int compareTo(InternalEntry other) {
        int key = Arrays.compareUnsigned(userKey, other.userKey);
        if (key != 0) return key;
        int sequenceOrder = Long.compare(other.sequence, sequence);
        return sequenceOrder != 0 ? sequenceOrder : type.compareTo(other.type);
    }

    /**
     * Tests exact internal identity, excluding value bytes.
     *
     * @param other entry to compare
     * @return whether user key, sequence, and type match
     */
    public boolean sameIdentity(InternalEntry other) {
        return sequence == other.sequence
                && type == other.type
                && Arrays.equals(userKey, other.userKey);
    }
}
