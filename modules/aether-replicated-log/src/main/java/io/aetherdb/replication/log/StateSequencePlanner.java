package io.aetherdb.replication.log;

import io.aetherdb.replication.api.StateSequenceRange;

/** Pure leader/follower sequence continuity rules for replicated commands. */
public final class StateSequencePlanner {
    private StateSequencePlanner() {}
    public static StateSequenceRange plan(long previousStateSequence, int operationCount) {
        if (previousStateSequence < 0 || operationCount <= 0 || operationCount > 10_000) throw new IllegalArgumentException("invalid sequence planning input");
        long first = Math.addExact(previousStateSequence, 1); long last = Math.addExact(previousStateSequence, operationCount);
        return new StateSequenceRange(first, last);
    }
    public static void validate(long previousStateSequence, int operationCount, StateSequenceRange proposed) {
        if (!plan(previousStateSequence, operationCount).equals(proposed)) throw new IllegalArgumentException("replicated state sequence is not contiguous");
    }
}
