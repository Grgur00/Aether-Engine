package io.aetherdb.rpc.api;

import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

/** Immutable bounded request delivered after complete frame assembly and admission. */
public record RpcServerRequest(
        int operationCode,
        UUID invocationId,
        byte[] body,
        Instant deadline,
        RpcCancellationToken cancellation) {
    /** Validates required fields and copies request bytes. */
    public RpcServerRequest {
        if (operationCode <= 0
                || invocationId == null
                || invocationId.equals(new UUID(0, 0))
                || body == null
                || body.length > 64 * 1024 * 1024
                || deadline == null
                || cancellation == null)
            throw new IllegalArgumentException("invalid RPC server request");
        body = Arrays.copyOf(body, body.length);
    }

    /** Returns a defensive body copy. */
    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }
}
