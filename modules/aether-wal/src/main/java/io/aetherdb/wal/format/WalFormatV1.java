package io.aetherdb.wal.format;

/** Frozen WAL v1 sizes, limits, and physical-size arithmetic. */
public final class WalFormatV1 {
    /** Maximum WAL segment size. */
    public static final int SEGMENT_CAPACITY = 64 * 1024 * 1024;

    /** Physical block size. */
    public static final int BLOCK_BYTES = 32 * 1024;

    /** Reserved first-block size. */
    public static final int HEADER_BLOCK_BYTES = BLOCK_BYTES;

    /** Meaningful segment-header bytes before padding. */
    public static final int SEGMENT_HEADER_BYTES = 96;

    /** Per-fragment physical header size. */
    public static final int FRAGMENT_HEADER_BYTES = 16;

    /** Largest fragment payload that fits an empty block. */
    public static final int MAX_FRAGMENT_PAYLOAD = BLOCK_BYTES - FRAGMENT_HEADER_BYTES;

    /** Logical write-group header size. */
    public static final int GROUP_HEADER_BYTES = 48;

    /** Logical mutation-batch header size. */
    public static final int BATCH_HEADER_BYTES = 24;

    /** Logical mutation-operation header size. */
    public static final int OPERATION_HEADER_BYTES = 12;

    /** Maximum logical group size accepted by fragmentation. */
    public static final int MAX_LOGICAL_GROUP_BYTES = 48 * 1024 * 1024;

    /** Fragment type codes for complete, first, middle, and final fragments. */
    public static final byte FULL = 1, FIRST = 2, MIDDLE = 3, LAST = 4;

    private WalFormatV1() {}

    /**
     * Estimates the physical end offset after fragmenting a logical record.
     *
     * @param start starting file offset
     * @param logicalLength logical record length
     * @return exclusive physical end offset
     */
    public static long estimateEndOffset(long start, long logicalLength) {
        if (start < HEADER_BLOCK_BYTES
                || logicalLength <= 0
                || logicalLength > MAX_LOGICAL_GROUP_BYTES)
            throw new IllegalArgumentException("invalid WAL append dimensions");
        long offset = start;
        long remaining = logicalLength;
        while (remaining > 0) {
            long blockRemaining = BLOCK_BYTES - offset % BLOCK_BYTES;
            if (blockRemaining <= FRAGMENT_HEADER_BYTES) {
                offset = Math.addExact(offset, blockRemaining);
                continue;
            }
            long payload = Math.min(remaining, blockRemaining - FRAGMENT_HEADER_BYTES);
            offset = Math.addExact(offset, FRAGMENT_HEADER_BYTES + payload);
            remaining -= payload;
        }
        return offset;
    }

    /**
     * Formats a canonical WAL segment file name.
     *
     * @param segment positive segment number
     * @return fixed-width managed file name
     */
    public static String fileName(long segment) {
        if (segment <= 0) throw new IllegalArgumentException("segment must be positive");
        return "WAL-%020d.aewal".formatted(segment);
    }
}
