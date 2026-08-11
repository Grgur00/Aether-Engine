package io.aetherdb.cache;

import java.util.concurrent.atomic.AtomicBoolean;

/** A pin that prevents its cached block from being evicted until closed. */
public final class BlockLease implements AutoCloseable {
    private final byte[] bytes;
    private final Runnable releaser;
    private final AtomicBoolean closed = new AtomicBoolean();

    BlockLease(byte[] bytes, Runnable releaser) {
        this.bytes = bytes;
        this.releaser = releaser;
    }

    /**
     * Returns read-only-by-contract raw bytes owned by the cache.
     *
     * @return leased block bytes
     */
    public byte[] rawBytes() {
        if (closed.get()) throw new IllegalStateException("block lease is closed");
        return bytes;
    }

    /**
     * Returns the block length.
     *
     * @return raw byte length
     */
    public int rawLength() {
        return bytes.length;
    }

    /**
     * Reports whether the lease is closed.
     *
     * @return {@code true} after release
     */
    public boolean isClosed() {
        return closed.get();
    }

    /** Releases this block pin exactly once. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) releaser.run();
    }
}
