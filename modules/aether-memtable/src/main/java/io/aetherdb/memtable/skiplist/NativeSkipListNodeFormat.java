package io.aetherdb.memtable.skiplist;

/** Exact process-local skip-list node v1 layout. */
public final class NativeSkipListNodeFormat {
    public static final int HEAD_OFFSET = 64;
    public static final int HEADER_BYTES = 16;
    public static final int HEAD_BYTES = 96;
    public static final byte VERSION = 1;
    public static final short HEAD_FLAG = 1;
    public static final int ALLOCATION_LENGTH_OFFSET = 0;
    public static final int RECORD_OFFSET = 4;
    public static final int HEIGHT_OFFSET = 8;
    public static final int VERSION_OFFSET = 9;
    public static final int FLAGS_OFFSET = 10;
    public static final int RESERVED_OFFSET = 12;
    private NativeSkipListNodeFormat() {}

    public static int prefixBytes(int height) {
        if (height < 1 || height > SkipListHeightGenerator.MAX_HEIGHT) throw new IllegalArgumentException("invalid height");
        return (HEADER_BYTES + 4 * height + 7) & -8;
    }

    public static int allocationBytes(int height, int recordBytes) {
        return Math.addExact(prefixBytes(height), recordBytes);
    }

    public static int linkOffset(int level) {
        if (level < 0 || level >= SkipListHeightGenerator.MAX_HEIGHT) throw new IllegalArgumentException("invalid level");
        return HEADER_BYTES + 4 * level;
    }
}
