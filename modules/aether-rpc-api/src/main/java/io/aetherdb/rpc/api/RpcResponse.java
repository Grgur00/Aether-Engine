package io.aetherdb.rpc.api;

import java.util.Arrays;
import java.util.UUID;

/** Immutable unary response retaining status, invocation identity, and exact body bytes. */
public record RpcResponse(RpcStatus status, UUID invocationId, byte[] body, String errorDetail) {
    /** Validates and defensively copies the bounded response data. */
    public RpcResponse {
        if (status == null
                || invocationId == null
                || invocationId.equals(new UUID(0, 0))
                || body == null
                || body.length > 64 * 1024 * 1024
                || errorDetail == null
                || errorDetail.length() > 4096)
            throw new IllegalArgumentException("invalid RPC response");
        body = Arrays.copyOf(body, body.length);
    }

    /** Returns a defensive body copy. */
    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }

    /** Creates a successful response. */
    public static RpcResponse ok(UUID invocationId, byte[] body) {
        return new RpcResponse(RpcStatus.OK, invocationId, body, "");
    }
}
