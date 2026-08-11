package io.aetherdb.memory;

/** Aligned monotonic allocator returning region-relative offsets. */
public interface NativeAllocator {
    Allocation tryAllocate(long sizeBytes, int alignmentBytes);

    long usedBytes();

    long remainingBytes();

    long allocatedPayloadBytes();

    long alignmentPaddingBytes();

    long allocationCount();

    long failedAllocationCount();

    record Allocation(boolean allocated, int offset, int length) {
        public static Allocation full() {
            return new Allocation(false, 0, 0);
        }

        public static Allocation at(int offset, int length) {
            return new Allocation(true, offset, length);
        }
    }
}
