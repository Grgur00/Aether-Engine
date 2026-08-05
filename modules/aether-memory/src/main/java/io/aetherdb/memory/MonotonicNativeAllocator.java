package io.aetherdb.memory;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Aligned bump allocator over a fixed native region; individual allocations
 * remain valid until the allocator is closed.
 */
final class MonotonicNativeAllocator implements NativeAllocator {
    private final FfmNativeRegion region;
    private final AtomicLong cursor = new AtomicLong(RegionConfig.FIRST_ALLOCATABLE_OFFSET);
    private final AtomicLong payload = new AtomicLong();
    private final AtomicLong padding = new AtomicLong();
    private final AtomicLong allocations = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    MonotonicNativeAllocator(FfmNativeRegion region) { this.region = region; }

    @Override
    public Allocation tryAllocate(long sizeBytes, int alignmentBytes) {
        region.ensureOpenForAllocation();
        if (sizeBytes <= 0 || sizeBytes > Integer.MAX_VALUE || !supported(alignmentBytes)) {
            throw new IllegalArgumentException("invalid allocation request");
        }
        while (true) {
            long old = cursor.get();
            long aligned = alignUp(old, alignmentBytes);
            long end;
            try { end = Math.addExact(aligned, sizeBytes); }
            catch (ArithmeticException overflow) { throw new IllegalArgumentException("allocation overflow", overflow); }
            if (end > region.capacityBytes()) {
                failures.incrementAndGet();
                return Allocation.full();
            }
            if (cursor.compareAndSet(old, end)) {
                payload.addAndGet(sizeBytes);
                padding.addAndGet(aligned - old);
                allocations.incrementAndGet();
                return Allocation.at(Math.toIntExact(aligned), Math.toIntExact(sizeBytes));
            }
        }
    }

    static long alignUp(long value, int alignment) {
        if (!supported(alignment)) throw new IllegalArgumentException("unsupported alignment");
        return Math.addExact(value, alignment - 1L) & -alignment;
    }

    private static boolean supported(int alignment) {
        return alignment == 1 || alignment == 2 || alignment == 4 || alignment == 8
                || alignment == 16 || alignment == 32 || alignment == 64;
    }

    @Override public long usedBytes() { return cursor.get() - RegionConfig.FIRST_ALLOCATABLE_OFFSET; }
    @Override public long remainingBytes() { return region.capacityBytes() - cursor.get(); }
    @Override public long allocatedPayloadBytes() { return payload.get(); }
    @Override public long alignmentPaddingBytes() { return padding.get(); }
    @Override public long allocationCount() { return allocations.get(); }
    @Override public long failedAllocationCount() { return failures.get(); }
}
