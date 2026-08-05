package io.aetherdb.engine;

import io.aetherdb.api.Snapshot;
import io.aetherdb.api.exceptions.SnapshotException;

final class SnapshotHandle implements Snapshot {
    private final Object identity;
    private final long id;
    private final long sequence;
    private final Runnable release;
    private boolean closed;

    SnapshotHandle(Object identity, long id, long sequence, Runnable release) {
        this.identity = identity;
        this.id = id;
        this.sequence = sequence;
        this.release = release;
    }

    Object identity() { return identity; }

    void ensureOpen() {
        if (closed) {
            throw new SnapshotException("snapshot is closed");
        }
    }

    @Override public long id() { return id; }
    @Override public long sequence() { ensureOpen(); return sequence; }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() { if (!closed) { closed = true; release.run(); } }

    void invalidate() { closed = true; }
}
