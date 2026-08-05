package io.aetherdb.replication.api;

/**
 * Positive replicated-log term/index identity, with {@code 0/0} reserved for the empty log.
 *
 * @param index zero-based empty-log marker or positive log index
 * @param term zero-based empty-log marker or positive election term
 */
public record LogPosition(long index, long term) {
    /** Validates that index and term are non-negative and use the empty marker together. */
    public LogPosition {
        if (index < 0 || term < 0 || index == 0 != (term == 0)) throw new IllegalArgumentException("invalid log position");
    }
}
