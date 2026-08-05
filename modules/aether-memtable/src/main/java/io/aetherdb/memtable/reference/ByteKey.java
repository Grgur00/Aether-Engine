package io.aetherdb.memtable.reference;

import java.util.Arrays;

/** Immutable, content-identifiable byte key. */
public final class ByteKey implements Comparable<ByteKey> {
    private final byte[] bytes;

    private ByteKey(byte[] bytes) {
        this.bytes = bytes;
    }

    public static ByteKey copyOf(byte[] bytes) {
        if (bytes == null) {
            throw new IllegalArgumentException("key must not be null");
        }
        return new ByteKey(Arrays.copyOf(bytes, bytes.length));
    }

    public byte[] copyBytes() {
        return Arrays.copyOf(bytes, bytes.length);
    }

    @Override
    public int compareTo(ByteKey other) {
        return UnsignedBytes.compare(bytes, other.bytes);
    }

    @Override
    public boolean equals(Object other) {
        return this == other || other instanceof ByteKey key && Arrays.equals(bytes, key.bytes);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(bytes);
    }
}
