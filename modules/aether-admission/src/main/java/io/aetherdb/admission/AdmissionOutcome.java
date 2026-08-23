package io.aetherdb.admission;

/** Chapter 31 admission outcome taxonomy. */
public enum AdmissionOutcome {
    /** Operation may cross the uncertainty boundary. */
    ACCEPTED,
    /** Operation was rejected before any commit or acknowledgement point. */
    REJECTED_BEFORE_ACK,
    /** Caller cannot know final state and must use idempotency/read-back. */
    UNCERTAIN,
    /** Node is draining, shutting down, or transferring leadership. */
    DRAINING_REJECTED,
    /** A hard bounded resource limit was exhausted before admission. */
    RESOURCE_EXHAUSTED
}
