package io.aetherdb.wal.format;

/** Frozen WAL v1 sizes, limits, and physical-size arithmetic. */
public final class WalFormatV1 {
    public static final int SEGMENT_CAPACITY = 64 * 1024 * 1024;
    public static final int BLOCK_BYTES = 32 * 1024;
    public static final int HEADER_BLOCK_BYTES = BLOCK_BYTES;
    public static final int SEGMENT_HEADER_BYTES = 96;
    public static final int FRAGMENT_HEADER_BYTES = 16;
    public static final int MAX_FRAGMENT_PAYLOAD = BLOCK_BYTES - FRAGMENT_HEADER_BYTES;
    public static final int GROUP_HEADER_BYTES = 48;
    public static final int BATCH_HEADER_BYTES = 24;
    public static final int OPERATION_HEADER_BYTES = 12;
    public static final int MAX_LOGICAL_GROUP_BYTES = 48 * 1024 * 1024;
    public static final byte FULL = 1, FIRST = 2, MIDDLE = 3, LAST = 4;
    private WalFormatV1() {}

    public static long estimateEndOffset(long start, long logicalLength) {
        if (start < HEADER_BLOCK_BYTES || logicalLength <= 0 || logicalLength > MAX_LOGICAL_GROUP_BYTES)
            throw new IllegalArgumentException("invalid WAL append dimensions");
        long offset = start;
        long remaining = logicalLength;
        while (remaining > 0) {
            long blockRemaining = BLOCK_BYTES - offset % BLOCK_BYTES;
            if (blockRemaining <= FRAGMENT_HEADER_BYTES) { offset = Math.addExact(offset, blockRemaining); continue; }
            long payload = Math.min(remaining, blockRemaining - FRAGMENT_HEADER_BYTES);
            offset = Math.addExact(offset, FRAGMENT_HEADER_BYTES + payload);
            remaining -= payload;
        }
        return offset;
    }

    public static String fileName(long segment) {
        if (segment <= 0) throw new IllegalArgumentException("segment must be positive");
        return "WAL-%020d.aewal".formatted(segment);
    }
}
