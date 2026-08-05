package io.aetherdb.raft.api;

/** Current election role of a Raft node. */
public enum RaftRole {
    /** Accepts leader replication and may grant votes. */ FOLLOWER,
    /** Tests quorum availability without incrementing the term. */ PRE_CANDIDATE,
    /** Solicits binding votes for the current election term. */ CANDIDATE,
    /** Replicates log entries and drives commit progress. */ LEADER
}
