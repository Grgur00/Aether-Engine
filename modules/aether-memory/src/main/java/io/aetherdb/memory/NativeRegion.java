package io.aetherdb.memory;

import java.lang.foreign.MemorySegment;

/** Deterministically owned contiguous native region. */
@SuppressWarnings("preview")
public interface NativeRegion extends AutoCloseable {
    enum State {
        OPEN,
        FROZEN,
        CLOSED
    }

    long capacityBytes();

    State state();

    NativeAllocator allocator();

    MemorySegment rootSegment();

    void freeze();

    @Override
    void close();
}
