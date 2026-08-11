package io.aetherdb.rpc.api;

import java.time.Duration;

/** Immutable per-call deadline, retry, and bounded-admission policy. */
public record RpcCallOptions(
        Duration timeout, boolean automaticRetry, RpcBackpressureMode backpressureMode) {
    /** Validates a positive bounded relative timeout and required mode. */
    public RpcCallOptions {
        if (timeout == null
                || timeout.isZero()
                || timeout.isNegative()
                || timeout.compareTo(Duration.ofHours(1)) > 0
                || backpressureMode == null) {
            throw new IllegalArgumentException("invalid RPC call options");
        }
    }

    /** Creates conservative options using an operation's declared default timeout. */
    public static RpcCallOptions defaults(RpcOperationDescriptor operation) {
        if (operation == null) throw new IllegalArgumentException("operation must not be null");
        return new RpcCallOptions(operation.defaultTimeout(), false, RpcBackpressureMode.FAIL_FAST);
    }
}
