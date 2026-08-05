package io.aetherdb.raft.api;

import java.util.UUID;

public record VoteRequest(VoteKind kind, long term, UUID candidateId, UUID sessionId, long lastLogIndex,
        long lastLogTerm, long lastStateSequence, byte[] lastEntryHash, long nonce, long configurationVersion) {
    public VoteRequest { lastEntryHash = lastEntryHash.clone(); if (term <= 0 || nonce == 0 || lastEntryHash.length != 32) throw new IllegalArgumentException("invalid vote request"); }
    @Override public byte[] lastEntryHash() { return lastEntryHash.clone(); }
}
