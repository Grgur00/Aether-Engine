package io.aetherdb.memory;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe capacity reservation budget for native regions. */
public final class NativeMemoryBudget {
    public static final long DEFAULT_LIMIT_BYTES = 256L * 1024 * 1024;

    private final long limitBytes;
    private final AtomicLong reservedBytes = new AtomicLong();
    private final AtomicLong peakReservedBytes = new AtomicLong();
    private final AtomicLong regionCount = new AtomicLong();
    private final AtomicLong reservationFailures = new AtomicLong();

    public NativeMemoryBudget() {
        this(DEFAULT_LIMIT_BYTES);
    }

    public NativeMemoryBudget(long limitBytes) {
        if (limitBytes <= 0) throw new IllegalArgumentException("limit must be positive");
        this.limitBytes = limitBytes;
    }

    public boolean tryReserve(long bytes) {
        if (bytes <= 0 || bytes > limitBytes)
            throw new IllegalArgumentException("invalid reservation size");
        while (true) {
            long current = reservedBytes.get();
            if (current > limitBytes - bytes) {
                reservationFailures.incrementAndGet();
                return false;
            }
            long next = current + bytes;
            if (reservedBytes.compareAndSet(current, next)) {
                peakReservedBytes.accumulateAndGet(next, Math::max);
                regionCount.incrementAndGet();
                return true;
            }
        }
    }

    public void release(long bytes) {
        if (bytes <= 0) throw new IllegalArgumentException("release must be positive");
        long remaining = reservedBytes.addAndGet(-bytes);
        if (remaining < 0) {
            reservedBytes.addAndGet(bytes);
            throw new IllegalStateException("native budget released more than reserved");
        }
        regionCount.decrementAndGet();
    }

    public long limitBytes() {
        return limitBytes;
    }

    public long reservedBytes() {
        return reservedBytes.get();
    }

    public long availableBytes() {
        return limitBytes - reservedBytes();
    }

    public long peakReservedBytes() {
        return peakReservedBytes.get();
    }

    public long regionCount() {
        return regionCount.get();
    }

    public long reservationFailures() {
        return reservationFailures.get();
    }
}
