package io.aetherdb.raft.api;

/** Stable reason explaining a granted or rejected vote request. */
public enum VoteReason {
    /** Vote was granted. */
    GRANTED,
    /** Candidate term is behind the receiver. */
    STALE_TERM,
    /** Receiver already voted for another candidate. */
    ALREADY_VOTED,
    /** Candidate log is less current than the receiver's log. */
    LOG_NOT_UP_TO_DATE,
    /** A leader contacted the receiver within the election lease. */
    RECENT_LEADER_CONTACT,
    /** Receiver does not participate in voting. */
    NOT_VOTER,
    /** Candidate is absent from the active configuration. */
    CANDIDATE_NOT_MEMBER,
    /** Candidate and receiver use different configuration versions. */
    CONFIGURATION_MISMATCH,
    /** Candidate's last-entry hash conflicts with the receiver. */
    LOG_HASH_MISMATCH,
    /** Local node state forbids granting a vote. */
    NODE_NOT_ELIGIBLE,
    /** Durable election state could not be accessed. */
    STORAGE_UNAVAILABLE,
    /** Request violates the vote protocol. */
    INVALID_REQUEST
}
