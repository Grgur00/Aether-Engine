package io.aetherdb.rpc.api;

import java.util.UUID;

/** Canonical network endpoint with an optional expected stable peer identity. */
public record RpcEndpoint(String host, int port, UUID expectedNodeId) {
    /** Validates host syntax, port range, and an optional non-zero node identity. */
    public RpcEndpoint {
        if (host == null
                || host.isBlank()
                || port < 1
                || port > 65_535
                || host.chars()
                        .anyMatch(character -> character == 0 || Character.isISOControl(character))
                || host.contains("/")
                || host.contains("@")
                || host.contains("://")
                || expectedNodeId != null && expectedNodeId.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException("invalid RPC endpoint");
        }
        host = host.strip();
    }

    /** Creates an endpoint without pinning a peer node ID. */
    public static RpcEndpoint of(String host, int port) {
        return new RpcEndpoint(host, port, null);
    }

    /** Returns bracketed canonical text for IPv6 literals. */
    public String canonical() {
        return (host.contains(":") ? '[' + host + ']' : host) + ':' + port;
    }
}
