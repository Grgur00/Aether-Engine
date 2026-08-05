package io.aetherdb.raft.api;

/** Election probe represented by a vote request. */
public enum VoteKind {
    /** Non-binding probe that does not advance the receiver's term. */ PRE_VOTE(1),
    /** Binding vote request for a real election. */ REQUEST_VOTE(2);
    /** Stable wire code for this request kind. */
    public final int code;
    VoteKind(int code) { this.code = code; }
}
