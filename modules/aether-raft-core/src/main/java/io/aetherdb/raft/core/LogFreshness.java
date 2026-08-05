package io.aetherdb.raft.core;

import java.security.MessageDigest;

public final class LogFreshness {
    private LogFreshness() {}
    public static Decision compare(long candidateTerm, long candidateIndex, byte[] candidateHash, long localTerm, long localIndex, byte[] localHash) {
        if (candidateTerm != localTerm) return candidateTerm > localTerm ? Decision.UP_TO_DATE : Decision.STALE;
        if (candidateIndex != localIndex) return candidateIndex > localIndex ? Decision.UP_TO_DATE : Decision.STALE;
        return MessageDigest.isEqual(candidateHash, localHash) ? Decision.UP_TO_DATE : Decision.DIVERGED;
    }
    public enum Decision { UP_TO_DATE, STALE, DIVERGED }
}
