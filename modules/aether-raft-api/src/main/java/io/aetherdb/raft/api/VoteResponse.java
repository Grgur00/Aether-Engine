package io.aetherdb.raft.api;

import java.util.UUID;

public record VoteResponse(VoteKind kind, boolean granted, VoteReason reason, long term, UUID responderId,
        UUID sessionId, long nonce, long lastLogIndex, long lastLogTerm, long configurationVersion) {
    public VoteResponse { if (granted != (reason == VoteReason.GRANTED)) throw new IllegalArgumentException("grant/reason mismatch"); }
}
