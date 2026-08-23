package io.aetherdb.client;

import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcRetryClass;

import java.util.Objects;

/** Chapter 37 retry policy that refuses blind retries of uncertain writes. */
public final class RemoteRetryPolicy {
    public RemoteRetryDecision decide(
            ClientStatus status,
            RpcRetryClass retryClass,
            RpcEndpoint current,
            RpcEndpoint leaderHint,
            boolean commandDeduplicated,
            int attempt,
            int maximumAttempts) {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(retryClass, "retryClass");
        Objects.requireNonNull(current, "current");
        if (attempt < 1 || maximumAttempts < 1 || attempt > maximumAttempts)
            throw new IllegalArgumentException("invalid retry attempt bounds");
        if (status == ClientStatus.OK) return new RemoteRetryDecision(RetryAction.COMPLETE, null, "ok");
        if (status == ClientStatus.INDETERMINATE)
            return new RemoteRetryDecision(
                    RetryAction.STOP_UNCERTAIN,
                    null,
                    "operation crossed uncertainty boundary");
        if (attempt == maximumAttempts)
            return new RemoteRetryDecision(RetryAction.COMPLETE, null, "attempt budget exhausted");
        if (status == ClientStatus.NOT_LEADER && leaderHint != null)
            return new RemoteRetryDecision(
                    RetryAction.RETRY_PREFERRED_LEADER, leaderHint, "leader redirect");
        if (!retrySafe(retryClass, commandDeduplicated))
            return new RemoteRetryDecision(RetryAction.COMPLETE, null, "retry class is not automatic");
        if (status == ClientStatus.RESOURCE_EXHAUSTED
                || status == ClientStatus.DEADLINE_EXCEEDED
                || status == ClientStatus.NO_LEADER_KNOWN
                || status == ClientStatus.LEADER_NOT_READY) {
            return new RemoteRetryDecision(
                    RetryAction.RETRY_SAME_ENDPOINT, current, "retryable transient status");
        }
        return new RemoteRetryDecision(RetryAction.COMPLETE, null, "non-retryable status");
    }

    private static boolean retrySafe(RpcRetryClass retryClass, boolean commandDeduplicated) {
        return retryClass == RpcRetryClass.IDEMPOTENT
                || retryClass == RpcRetryClass.DEDUP_REQUIRED && commandDeduplicated;
    }
}
