package io.aetherdb.replication.log;

import io.aetherdb.replication.api.StateSequenceRange;

/** Pure leader/follower sequence continuity rules for replicated commands. */
public final class StateSequencePlanner {
    private StateSequencePlanner() {}

    /**
     * Allocates a contiguous sequence range immediately after the current state.
     *
     * @param previousStateSequence last allocated state sequence
     * @param operationCount number of mutations to allocate
     * @return inclusive sequence range for the mutations
     * @throws IllegalArgumentException if the input is outside supported bounds
     * @throws ArithmeticException if the resulting sequence overflows
     */
    public static StateSequenceRange plan(long previousStateSequence, int operationCount) {
        if (previousStateSequence < 0 || operationCount <= 0 || operationCount > 10_000) throw new IllegalArgumentException("invalid sequence planning input");
        long first = Math.addExact(previousStateSequence, 1); long last = Math.addExact(previousStateSequence, operationCount);
        return new StateSequenceRange(first, last);
    }

    /**
     * Verifies that a proposed range is exactly the next contiguous allocation.
     *
     * @param previousStateSequence last allocated state sequence
     * @param operationCount expected number of mutations
     * @param proposed proposed inclusive range
     * @throws IllegalArgumentException if the proposal is not contiguous
     */
    public static void validate(long previousStateSequence, int operationCount, StateSequenceRange proposed) {
        if (!plan(previousStateSequence, operationCount).equals(proposed)) throw new IllegalArgumentException("replicated state sequence is not contiguous");
    }
}
