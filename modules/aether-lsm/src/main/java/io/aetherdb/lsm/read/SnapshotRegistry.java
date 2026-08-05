package io.aetherdb.lsm.read;

import java.util.NavigableMap;
import java.util.TreeMap;

/** Exact multiset of active snapshot sequences. */
public final class SnapshotRegistry {
    private final NavigableMap<Long, Integer> registrations = new TreeMap<>();
    private int count;

    /** Creates an empty snapshot multiset. */
    public SnapshotRegistry() {}

    /** Registers an active snapshot sequence.
     * @param sequence non-negative visibility sequence
     * @return idempotently closeable registration */
    public synchronized Registration register(long sequence) {
        if (sequence < 0) throw new IllegalArgumentException("sequence must be non-negative");
        registrations.merge(sequence, 1, Integer::sum);
        count++;
        return new Registration(this, sequence);
    }

    private synchronized void release(long sequence) {
        Integer references = registrations.get(sequence);
        if (references == null) throw new IllegalStateException("snapshot registration underflow");
        if (references == 1) registrations.remove(sequence); else registrations.put(sequence, references - 1);
        count--;
    }

    /** Returns the oldest active sequence.
     * @return oldest sequence or -1 when empty */
    public synchronized long oldestSequence() { return registrations.isEmpty() ? -1 : registrations.firstKey(); }
    /** Returns the newest active sequence.
     * @return newest sequence or -1 when empty */
    public synchronized long newestSequence() { return registrations.isEmpty() ? -1 : registrations.lastKey(); }
    /** Returns registration cardinality.
     * @return active handle count */
    public synchronized int activeCount() { return count; }

    /** One idempotently closeable snapshot-sequence registration. */
    public static final class Registration implements AutoCloseable {
        private SnapshotRegistry registry;
        private final long sequence;
        Registration(SnapshotRegistry registry, long sequence) { this.registry = registry; this.sequence = sequence; }
        /** Returns the registered sequence.
         * @return visibility sequence */
        public long sequence() { return sequence; }
        /** Reports whether this registration is released.
         * @return {@code true} after close */
        public synchronized boolean isClosed() { return registry == null; }
        /** Releases this registration exactly once. */
        @Override public synchronized void close() {
            if (registry != null) { SnapshotRegistry owner = registry; registry = null; owner.release(sequence); }
        }
    }
}
