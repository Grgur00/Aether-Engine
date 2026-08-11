package io.aetherdb.memory;

/** Native region limits fixed by Chapter 9. */
public final class RegionConfig {
    public static final long DEFAULT_CAPACITY_BYTES = 64L * 1024 * 1024;
    public static final long MIN_CAPACITY_BYTES = 1024L * 1024;
    public static final long MAX_CAPACITY_BYTES = 1024L * 1024 * 1024;
    public static final int CAPACITY_GRANULARITY_BYTES = 4096;
    public static final int FIRST_ALLOCATABLE_OFFSET = 64;
    public static final int DEFAULT_ALIGNMENT = 8;

    private RegionConfig() {}

    public static void validateCapacity(long capacity) {
        if (capacity < MIN_CAPACITY_BYTES
                || capacity > MAX_CAPACITY_BYTES
                || capacity % CAPACITY_GRANULARITY_BYTES != 0) {
            throw new IllegalArgumentException("capacity must be 1 MiB..1 GiB and 4 KiB aligned");
        }
    }
}
