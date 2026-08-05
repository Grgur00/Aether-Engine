package io.aetherdb.raft.api;

import java.util.UUID;

/**
 * Immutable response to a pre-vote or binding-vote request.
 *
 * @param kind election probe kind
 * @param granted whether the vote was granted
 * @param reason stable decision reason
 * @param term receiver's current term
 * @param responderId responding node identity
 * @param sessionId election session being answered
 * @param nonce echoed request nonce
 * @param lastLogIndex receiver's last log index
 * @param lastLogTerm receiver's last log term
 * @param configurationVersion receiver's membership configuration version
 */
public record VoteResponse(VoteKind kind, boolean granted, VoteReason reason, long term, UUID responderId,
        UUID sessionId, long nonce, long lastLogIndex, long lastLogTerm, long configurationVersion) {
    /** Ensures the boolean grant flag agrees with the structured reason. */
    public VoteResponse { if (granted != (reason == VoteReason.GRANTED)) throw new IllegalArgumentException("grant/reason mismatch"); }
}
