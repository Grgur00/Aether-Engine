package io.aetherdb.reliability;

import java.util.Objects;

/** Immutable description of one deterministic persisted-byte corruption. */
public record CorruptionPlan(
        CorruptionKind kind, int offset, int length, byte value, int bitIndex) {
    public CorruptionPlan {
        Objects.requireNonNull(kind, "kind");
        if (offset < 0 || length < 0 || bitIndex < 0 || bitIndex > 7) {
            throw new IllegalArgumentException("invalid corruption plan bounds");
        }
    }

    public static CorruptionPlan flipBit(int offset, int bitIndex) {
        return new CorruptionPlan(CorruptionKind.FLIP_BIT, offset, 1, (byte) 0, bitIndex);
    }

    public static CorruptionPlan truncate(int prefixLength) {
        return new CorruptionPlan(CorruptionKind.TRUNCATE, prefixLength, 0, (byte) 0, 0);
    }

    public static CorruptionPlan appendGarbage(int bytes, byte value) {
        return new CorruptionPlan(CorruptionKind.APPEND_GARBAGE, 0, bytes, value, 0);
    }

    public static CorruptionPlan overwriteRange(int offset, int length, byte value) {
        return new CorruptionPlan(CorruptionKind.OVERWRITE_RANGE, offset, length, value, 0);
    }

    public static CorruptionPlan tornWritePrefix(int prefixLength) {
        return new CorruptionPlan(CorruptionKind.TORN_WRITE_PREFIX, prefixLength, 0, (byte) 0, 0);
    }
}
