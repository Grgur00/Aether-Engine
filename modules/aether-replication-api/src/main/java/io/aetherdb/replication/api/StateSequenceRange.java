package io.aetherdb.replication.api;

/**
 * Leader-assigned contiguous MVCC sequence range for one replicated command.
 *
 * @param first first sequence in the inclusive range
 * @param last last sequence in the inclusive range
 */
public record StateSequenceRange(long first, long last) {
    /** Validates that the range is positive, ordered, and non-empty. */
    public StateSequenceRange { if (first <= 0 || last < first) throw new IllegalArgumentException("invalid state sequence range"); }

    /**
     * Returns the number of mutations represented by this range.
     *
     * @return inclusive range size
     * @throws ArithmeticException if the size cannot be represented as an {@code int}
     */
    public int operationCount() { return Math.toIntExact(last - first + 1); }
}
