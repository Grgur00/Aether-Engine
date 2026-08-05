package io.aetherdb.lsm.read;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** Atomically publishes immutable read topology and pins it for readers. */
public final class ReadViewManager implements AutoCloseable {
    private final AtomicReference<ReadView> current = new AtomicReference<>();
    private final AtomicLong nextGeneration = new AtomicLong();

    /** Creates a manager and publishes its initial topology.
     * @param initial initial source inventory */
    public ReadViewManager(ReadTopology initial) { publish(initial); }

    /** Pins the currently published view.
     * @return closeable read-view handle */
    public ReadViewHandle pinCurrent() {
        for (;;) {
            ReadView view = current.get();
            if (view == null) throw new IllegalStateException("read view manager is closed");
            if (view.tryRetain()) return new ReadViewHandle(view);
        }
    }

    /** Atomically replaces the published topology.
     * @param topology new source inventory */
    public void publish(ReadTopology topology) {
        Objects.requireNonNull(topology, "topology");
        if (current.get() == null && nextGeneration.get() != 0)
            throw new IllegalStateException("read view manager is closed");
        ReadView replacement = new ReadView(nextGeneration.incrementAndGet(), topology);
        ReadView previous = current.getAndSet(replacement);
        if (previous != null) previous.retireOwner();
    }

    /** Retires the published owner reference and rejects new pins. */
    @Override public void close() {
        ReadView previous = current.getAndSet(null);
        if (previous != null) previous.retireOwner();
    }
}
