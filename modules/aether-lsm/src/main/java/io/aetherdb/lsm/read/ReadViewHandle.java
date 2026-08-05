package io.aetherdb.lsm.read;

import java.util.concurrent.atomic.AtomicBoolean;

/** Idempotently closeable pin on a ReadView. */
public final class ReadViewHandle implements AutoCloseable {
    private final ReadView view;
    private final AtomicBoolean closed = new AtomicBoolean();

    ReadViewHandle(ReadView view) { this.view = view; }
    public ReadView view() {
        if (closed.get()) throw new IllegalStateException("read view handle is closed");
        return view;
    }
    public boolean isClosed() { return closed.get(); }
    @Override public void close() { if (closed.compareAndSet(false, true)) view.release(); }
}
