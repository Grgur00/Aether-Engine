package io.aetherdb.replication.api;

/** Leader-assigned contiguous MVCC sequence range for one replicated command. */
public record StateSequenceRange(long first, long last) {
    public StateSequenceRange { if (first <= 0 || last < first) throw new IllegalArgumentException("invalid state sequence range"); }
    public int operationCount() { return Math.toIntExact(last - first + 1); }
}
