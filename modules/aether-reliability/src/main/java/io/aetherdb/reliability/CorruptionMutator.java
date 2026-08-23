package io.aetherdb.reliability;

import java.util.Arrays;
import java.util.Objects;

/** Applies deterministic byte-level corruption plans without mutating caller-owned arrays. */
public final class CorruptionMutator {
    private CorruptionMutator() {}

    public static byte[] apply(byte[] original, CorruptionPlan plan) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(plan, "plan");
        return switch (plan.kind()) {
            case FLIP_BIT -> flipBit(original, plan.offset(), plan.bitIndex());
            case TRUNCATE, TORN_WRITE_PREFIX -> truncate(original, plan.offset());
            case APPEND_GARBAGE -> appendGarbage(original, plan.length(), plan.value());
            case OVERWRITE_RANGE ->
                    overwriteRange(original, plan.offset(), plan.length(), plan.value());
        };
    }

    private static byte[] flipBit(byte[] original, int offset, int bitIndex) {
        requireOffset(original, offset);
        byte[] copy = original.clone();
        copy[offset] = (byte) (copy[offset] ^ (1 << bitIndex));
        return copy;
    }

    private static byte[] truncate(byte[] original, int prefixLength) {
        if (prefixLength < 0 || prefixLength > original.length) {
            throw new IllegalArgumentException("corruption prefix is outside input");
        }
        return Arrays.copyOf(original, prefixLength);
    }

    private static byte[] appendGarbage(byte[] original, int bytes, byte value) {
        if (bytes <= 0) throw new IllegalArgumentException("garbage length must be positive");
        byte[] copy = Arrays.copyOf(original, Math.addExact(original.length, bytes));
        Arrays.fill(copy, original.length, copy.length, value);
        return copy;
    }

    private static byte[] overwriteRange(byte[] original, int offset, int length, byte value) {
        if (length <= 0) throw new IllegalArgumentException("overwrite length must be positive");
        requireRange(original, offset, length);
        byte[] copy = original.clone();
        Arrays.fill(copy, offset, offset + length, value);
        return copy;
    }

    private static void requireOffset(byte[] original, int offset) {
        if (offset < 0 || offset >= original.length) {
            throw new IllegalArgumentException("corruption offset is outside input");
        }
    }

    private static void requireRange(byte[] original, int offset, int length) {
        if (offset < 0 || length < 0 || offset + (long) length > original.length) {
            throw new IllegalArgumentException("corruption range is outside input");
        }
    }
}
