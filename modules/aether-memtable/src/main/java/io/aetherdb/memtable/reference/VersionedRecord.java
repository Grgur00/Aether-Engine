package io.aetherdb.memtable.reference;

import java.util.Arrays;

/** One immutable VALUE or TOMBSTONE mutation. */
public final class VersionedRecord {
    public enum Type {
        VALUE,
        TOMBSTONE
    }

    private final long sequence;
    private final Type type;
    private final byte[] value;

    private VersionedRecord(long sequence, Type type, byte[] value) {
        if (sequence <= 0) {
            throw new IllegalArgumentException("sequence must be positive");
        }
        this.sequence = sequence;
        this.type = type;
        this.value = value;
    }

    public static VersionedRecord value(long sequence, byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new VersionedRecord(sequence, Type.VALUE, Arrays.copyOf(value, value.length));
    }

    public static VersionedRecord tombstone(long sequence) {
        return new VersionedRecord(sequence, Type.TOMBSTONE, null);
    }

    public long sequence() {
        return sequence;
    }

    public Type type() {
        return type;
    }

    public byte[] copyValue() {
        if (type != Type.VALUE) {
            throw new IllegalStateException("tombstone has no value");
        }
        return Arrays.copyOf(value, value.length);
    }
}
