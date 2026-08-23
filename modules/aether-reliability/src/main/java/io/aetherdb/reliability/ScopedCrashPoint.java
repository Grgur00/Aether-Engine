package io.aetherdb.reliability;

/** AutoCloseable crash-point installation scope. */
public final class ScopedCrashPoint implements AutoCloseable {
    private final CrashPoint previous;
    private boolean closed;

    ScopedCrashPoint(CrashPoint previous) {
        this.previous = previous;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        CrashPointRegistry.restore(previous);
    }
}
