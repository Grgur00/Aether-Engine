package io.aetherdb.raft.api;

/** Stable outcome reason returned by an AppendEntries receiver. */
public enum AppendEntriesReason {
    /** Previous-log coordinates matched and entries were accepted. */ MATCHED,
    /** Leader term is stale. */ STALE_TERM,
    /** Receiver is not a member of the active configuration. */ NOT_MEMBER,
    /** Leader and receiver use incompatible configuration versions. */ CONFIGURATION_MISMATCH,
    /** Receiver log does not reach the requested previous index. */ LOG_TOO_SHORT,
    /** Previous index exists with a different term. */ TERM_MISMATCH,
    /** Previous entry has a different integrity hash. */ LOG_HASH_MISMATCH,
    /** Replicated state sequences are discontinuous. */ STATE_SEQUENCE_MISMATCH,
    /** Request conflicts with an already committed entry. */ COMMITTED_CONFLICT,
    /** Request conflicts with an already applied entry. */ APPLIED_CONFLICT,
    /** Receiver could not access durable log storage. */ LOG_STORAGE_UNAVAILABLE,
    /** Receiver temporarily cannot admit more replication work. */ FOLLOWER_BUSY,
    /** Request violates the replication protocol. */ INVALID_REQUEST,
    /** Incremental repair is insufficient and a snapshot is required. */ SNAPSHOT_REQUIRED
}
