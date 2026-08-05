package io.aetherdb.memtable.reference;

import io.aetherdb.api.exceptions.SequenceExhaustedException;

/** Monotonic, overflow-safe sequence allocator. Sequence zero is reserved. */
public final class SequenceSource {
    private long lastAssigned;

    public SequenceSource() { this(0); }

    public SequenceSource(long lastAssigned) {
        if (lastAssigned < 0) {
            throw new IllegalArgumentException("last assigned sequence must not be negative");
        }
        this.lastAssigned = lastAssigned;
    }

    public long reserveOne() { return reserve(1).first(); }

    public SequenceRange reserve(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("sequence count must be positive");
        }
        if (lastAssigned > Long.MAX_VALUE - count) {
            throw new SequenceExhaustedException("sequence space exhausted");
        }
        long first = lastAssigned + 1;
        lastAssigned += count;
        return new SequenceRange(first, lastAssigned);
    }

    public long lastAssigned() { return lastAssigned; }

    public record SequenceRange(long first, long last) {}
}
