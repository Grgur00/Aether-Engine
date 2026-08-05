package io.aetherdb.memory;

/** Exact arithmetic and field constants for process-local native records v1. */
public final class NativeRecordFormatV1 {
    public static final int TOTAL_LENGTH_OFFSET = 0;
    public static final int KEY_LENGTH_OFFSET = 4;
    public static final int VALUE_LENGTH_OFFSET = 8;
    public static final int FORMAT_VERSION_OFFSET = 12;
    public static final int RECORD_TYPE_OFFSET = 14;
    public static final int FLAGS_OFFSET = 15;
    public static final int SEQUENCE_OFFSET = 16;
    public static final int HEADER_BYTES = 24;
    public static final int ALIGNMENT = 8;
    public static final short VERSION = 1;
    public static final byte VALUE = 1;
    public static final byte TOMBSTONE = 2;
    public static final int MAX_KEY_BYTES = 65_536;
    public static final int MAX_VALUE_BYTES = 16_777_216;
    public static final int MAX_RECORD_BYTES = totalLength(MAX_KEY_BYTES, MAX_VALUE_BYTES);
    private NativeRecordFormatV1() {}

    public static int totalLength(int keyLength, int valueLength) {
        validateLengths(keyLength, valueLength);
        long logical = Math.addExact(HEADER_BYTES, Math.addExact((long) keyLength, valueLength));
        return Math.toIntExact(MonotonicNativeAllocator.alignUp(logical, ALIGNMENT));
    }

    public static int padding(int keyLength, int valueLength) {
        return totalLength(keyLength, valueLength) - HEADER_BYTES - keyLength - valueLength;
    }

    public static void validateLengths(int keyLength, int valueLength) {
        if (keyLength < 0 || keyLength > MAX_KEY_BYTES) throw new IllegalArgumentException("invalid key length");
        if (valueLength < 0 || valueLength > MAX_VALUE_BYTES) throw new IllegalArgumentException("invalid value length");
    }
}
