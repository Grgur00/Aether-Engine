package io.aetherdb.lsm.read;

import java.util.NavigableMap;
import java.util.TreeMap;

/** Exact multiset of active snapshot sequences. */
public final class SnapshotRegistry {
    private final NavigableMap<Long, Integer> registrations = new TreeMap<>();
    private int count;

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

    public synchronized long oldestSequence() { return registrations.isEmpty() ? -1 : registrations.firstKey(); }
    public synchronized long newestSequence() { return registrations.isEmpty() ? -1 : registrations.lastKey(); }
    public synchronized int activeCount() { return count; }

    public static final class Registration implements AutoCloseable {
        private SnapshotRegistry registry;
        private final long sequence;
        Registration(SnapshotRegistry registry, long sequence) { this.registry = registry; this.sequence = sequence; }
        public long sequence() { return sequence; }
        public synchronized boolean isClosed() { return registry == null; }
        @Override public synchronized void close() {
            if (registry != null) { SnapshotRegistry owner = registry; registry = null; owner.release(sequence); }
        }
    }
}
