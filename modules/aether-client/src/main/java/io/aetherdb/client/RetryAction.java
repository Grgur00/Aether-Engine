package io.aetherdb.client;

/** Client retry action after one RPC attempt. */
public enum RetryAction {
    COMPLETE,
    RETRY_SAME_ENDPOINT,
    RETRY_PREFERRED_LEADER,
    STOP_UNCERTAIN
}
