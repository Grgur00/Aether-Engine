package io.aetherdb.raft.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.function.LongUnaryOperator;

public final class RaftCommitTracker {
    private long commitIndex;
    public long commitIndex() { return commitIndex; }
    public long recalculate(Collection<Long> durableMatches, long currentTerm, LongUnaryOperator termAtIndex) {
        if (durableMatches.isEmpty()) return commitIndex;
        var sorted = new ArrayList<>(durableMatches); sorted.sort(Comparator.reverseOrder());
        long quorumMatch = sorted.get(RaftQuorum.required(sorted.size()) - 1);
        if (quorumMatch > commitIndex && termAtIndex.applyAsLong(quorumMatch) == currentTerm) commitIndex = quorumMatch;
        return commitIndex;
    }
}
