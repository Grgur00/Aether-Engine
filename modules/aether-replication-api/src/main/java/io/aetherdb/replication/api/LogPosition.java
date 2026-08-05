package io.aetherdb.replication.api;

/** Positive replicated-log term/index identity, with 0/0 reserved for the empty log. */
public record LogPosition(long index, long term) {
    public LogPosition {
        if (index < 0 || term < 0 || index == 0 != (term == 0)) throw new IllegalArgumentException("invalid log position");
    }
}
