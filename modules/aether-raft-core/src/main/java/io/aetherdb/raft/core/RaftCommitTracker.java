package io.aetherdb.raft.core;

import io.aetherdb.reliability.CrashContext;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.function.LongUnaryOperator;

/** Tracks monotonic leader commit progress from durable follower match indexes. */
public final class RaftCommitTracker {
    private long commitIndex;

    /** Creates a tracker at the empty-log commit index. */
    public RaftCommitTracker() {}

    /**
     * Returns the current committed index.
     *
     * @return monotonic commit index
     */
    public long commitIndex() {
        return commitIndex;
    }

    /**
     * Recomputes commit progress, enforcing Raft's current-term commitment rule.
     *
     * @param durableMatches durable match index for each voter
     * @param currentTerm leader's current term
     * @param termAtIndex lookup for the term stored at an index
     * @return updated commit index
     */
    public long recalculate(
            Collection<Long> durableMatches, long currentTerm, LongUnaryOperator termAtIndex) {
        if (durableMatches.isEmpty()) return commitIndex;
        var sorted = new ArrayList<>(durableMatches);
        sorted.sort(Comparator.reverseOrder());
        long quorumMatch = sorted.get(RaftQuorum.required(sorted.size()) - 1);
        if (quorumMatch > commitIndex && termAtIndex.applyAsLong(quorumMatch) == currentTerm) {
            commitIndex = quorumMatch;
            CrashPointRegistry.hit(
                    CrashPointIds.RAFT_COMMIT_AFTER_MAJORITY_BEFORE_APPLY,
                    commitContext(commitIndex, currentTerm, sorted.size()));
        }
        return commitIndex;
    }

    private static CrashContext commitContext(long commitIndex, long currentTerm, int voters) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("commit_index", Long.toUnsignedString(commitIndex));
        attributes.put("term", Long.toUnsignedString(currentTerm));
        attributes.put("voters", Integer.toString(voters));
        return new CrashContext(attributes);
    }
}
