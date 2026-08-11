package io.aetherdb.lsm.read;

import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotently closeable pin on a ReadView. */
public final class ReadViewHandle implements AutoCloseable {
    private final ReadView view;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReadViewHandle(ReadView view) {
        this.view = view;
    }

    /**
     * Returns the pinned view while this handle is open.
     *
     * @return retained read view
     */
    public ReadView view() {
        if (closed.get()) throw new IllegalStateException("read view handle is closed");
        return view;
    }

    /**
     * Reports whether the pin is released.
     *
     * @return {@code true} after close
     */
    public boolean isClosed() {
        return closed.get();
    }

    /** Releases the view reference exactly once. */
    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) view.release();
    }
}
