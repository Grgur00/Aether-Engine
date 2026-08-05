package io.aetherdb.raft.core;

import java.security.MessageDigest;

/** Deterministic comparison of election-candidate and local log tips. */
public final class LogFreshness {
    private LogFreshness() {}
    /** Compares log terms, indexes, and integrity hashes in Raft election order.
     * @param candidateTerm candidate's last-log term
     * @param candidateIndex candidate's last-log index
     * @param candidateHash candidate's last-entry hash
     * @param localTerm receiver's last-log term
     * @param localIndex receiver's last-log index
     * @param localHash receiver's last-entry hash
     * @return freshness decision */
    public static Decision compare(long candidateTerm, long candidateIndex, byte[] candidateHash, long localTerm, long localIndex, byte[] localHash) {
        if (candidateTerm != localTerm) return candidateTerm > localTerm ? Decision.UP_TO_DATE : Decision.STALE;
        if (candidateIndex != localIndex) return candidateIndex > localIndex ? Decision.UP_TO_DATE : Decision.STALE;
        return MessageDigest.isEqual(candidateHash, localHash) ? Decision.UP_TO_DATE : Decision.DIVERGED;
    }
    /** Result of comparing two replicated-log tips. */
    public enum Decision {
        /** Candidate log is at least as current and has a matching integrity anchor. */ UP_TO_DATE,
        /** Candidate term or index is behind the receiver. */ STALE,
        /** Coordinates match but integrity hashes differ. */ DIVERGED
    }
}
