package io.aetherdb.lsm.read;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Immutable, reference-counted topology snapshot for one read operation. */
public final class ReadView {
    private final long generation;
    private final long visibleSequence;
    private final RetainedSource activeMemTable;
    private final List<RetainedSource> immutableMemTables;
    private final RetainedSource version;
    private final Instant createdAt = Instant.now();
    private final AtomicInteger references = new AtomicInteger(1);
    private volatile boolean retired;

    ReadView(long generation, ReadTopology topology) {
        this.generation = generation;
        visibleSequence = topology.visibleSequence();
        activeMemTable = topology.activeMemTable();
        immutableMemTables = List.copyOf(topology.immutableMemTables());
        version = topology.version();
        retainComponents();
    }

    private void retainComponents() {
        int retainedImmutable = 0;
        boolean activeRetained = false;
        try {
            if (activeMemTable != null) {
                activeMemTable.retain();
                activeRetained = true;
            }
            for (RetainedSource source : immutableMemTables) {
                source.retain();
                retainedImmutable++;
            }
            if (version != null) version.retain();
        } catch (RuntimeException | Error failure) {
            for (int i = retainedImmutable - 1; i >= 0; i--) immutableMemTables.get(i).release();
            if (activeRetained) activeMemTable.release();
            throw failure;
        }
    }

    boolean tryRetain() {
        while (!retired) {
            int current = references.get();
            if (current == 0) return false;
            if (references.compareAndSet(current, current + 1)) {
                if (!retired) return true;
                release();
                return false;
            }
        }
        return false;
    }

    void retireOwner() {
        retired = true;
        release();
    }

    void release() {
        int remaining = references.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("read view reference underflow");
        if (remaining == 0) {
            if (version != null) version.release();
            for (int i = immutableMemTables.size() - 1; i >= 0; i--)
                immutableMemTables.get(i).release();
            if (activeMemTable != null) activeMemTable.release();
        }
    }

    /**
     * Returns the publication generation.
     *
     * @return monotonic generation
     */
    public long generation() {
        return generation;
    }

    /**
     * Returns the visibility boundary.
     *
     * @return maximum visible sequence
     */
    public long visibleSequence() {
        return visibleSequence;
    }

    /**
     * Returns the retained active MemTable.
     *
     * @return source or null
     */
    public RetainedSource activeMemTable() {
        return activeMemTable;
    }

    /**
     * Returns retained immutable MemTables.
     *
     * @return immutable source list
     */
    public List<RetainedSource> immutableMemTables() {
        return immutableMemTables;
    }

    /**
     * Returns the retained table version.
     *
     * @return version source or null
     */
    public RetainedSource version() {
        return version;
    }

    /**
     * Returns creation time.
     *
     * @return view creation instant
     */
    public Instant createdAt() {
        return createdAt;
    }

    /**
     * Reports whether the manager retired its owner reference.
     *
     * @return retirement state
     */
    public boolean isRetired() {
        return retired;
    }

    /**
     * Returns current ownership and reader references.
     *
     * @return reference count
     */
    public int referenceCount() {
        return references.get();
    }
}
