package io.aetherdb.client;

import io.aetherdb.rpc.api.RpcEndpoint;

import java.util.Objects;

/** Immutable retry decision for one remote client attempt. */
public record RemoteRetryDecision(RetryAction action, RpcEndpoint nextEndpoint, String reason) {
    public RemoteRetryDecision {
        Objects.requireNonNull(action, "action");
        reason = reason == null ? "" : reason;
        if ((action == RetryAction.RETRY_PREFERRED_LEADER || action == RetryAction.RETRY_SAME_ENDPOINT)
                && nextEndpoint == null) {
            throw new IllegalArgumentException("retry action requires endpoint");
        }
        if ((action == RetryAction.COMPLETE || action == RetryAction.STOP_UNCERTAIN)
                && nextEndpoint != null) {
            throw new IllegalArgumentException("terminal action cannot carry endpoint");
        }
    }
}
