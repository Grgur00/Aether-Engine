package io.aetherdb.rpc.api;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable operation contract used before allocating or dispatching message bodies.
 *
 * @param operationCode positive protocol operation code
 * @param requestLimit maximum request body size in bytes
 * @param responseLimit maximum response body size in bytes
 * @param retryClass client retry-safety classification
 * @param executionPolicy server executor isolation class
 * @param defaultTimeout positive default request timeout
 */
public record RpcOperationDescriptor(int operationCode, int requestLimit, int responseLimit,
        RpcRetryClass retryClass, RpcExecutionPolicy executionPolicy, Duration defaultTimeout) {
    /** Validates wire limits and dispatch metadata. */
    public RpcOperationDescriptor {
        if (operationCode <= 0 || requestLimit < 0 || requestLimit > 64 * 1024 * 1024
                || responseLimit < 0 || responseLimit > 64 * 1024 * 1024)
            throw new IllegalArgumentException("invalid RPC operation limits");
        Objects.requireNonNull(retryClass); Objects.requireNonNull(executionPolicy); Objects.requireNonNull(defaultTimeout);
        if (defaultTimeout.isNegative() || defaultTimeout.isZero()) throw new IllegalArgumentException("timeout must be positive");
    }
}
