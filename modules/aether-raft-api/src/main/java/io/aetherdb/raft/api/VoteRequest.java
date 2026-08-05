package io.aetherdb.raft.api;

import java.util.UUID;

/**
 * Immutable pre-vote or binding-vote request.
 *
 * @param kind election probe kind
 * @param term candidate election term
 * @param candidateId candidate node identity
 * @param sessionId candidate election-session identity
 * @param lastLogIndex candidate's last log index
 * @param lastLogTerm candidate's last log term
 * @param lastStateSequence candidate's last replicated state sequence
 * @param lastEntryHash SHA-256 hash of the candidate's last entry
 * @param nonce non-zero request nonce
 * @param configurationVersion candidate's membership configuration version
 */
public record VoteRequest(VoteKind kind, long term, UUID candidateId, UUID sessionId, long lastLogIndex,
        long lastLogTerm, long lastStateSequence, byte[] lastEntryHash, long nonce, long configurationVersion) {
    /** Validates protocol bounds and defensively copies the entry hash. */
    public VoteRequest { lastEntryHash = lastEntryHash.clone(); if (term <= 0 || nonce == 0 || lastEntryHash.length != 32) throw new IllegalArgumentException("invalid vote request"); }
    /** Returns the log-integrity anchor.
     * @return defensive copy of the last-entry hash */
    @Override public byte[] lastEntryHash() { return lastEntryHash.clone(); }
}
