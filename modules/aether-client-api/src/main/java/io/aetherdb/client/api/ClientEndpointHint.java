package io.aetherdb.client.api;

import java.util.UUID;

/** Optional endpoint hint returned by server-side client protocol responses. */
public record ClientEndpointHint(String host, int port, UUID expectedNodeId) {
    /** Validates canonical host, port, and optional non-zero node identity. */
    public ClientEndpointHint {
        if (host == null
                || host.isBlank()
                || port < 1
                || port > 65_535
                || host.chars().anyMatch(character -> character == 0 || Character.isISOControl(character))
                || host.contains("/")
                || host.contains("@")
                || host.contains("://")
                || expectedNodeId != null && expectedNodeId.equals(new UUID(0, 0))) {
            throw new IllegalArgumentException("invalid endpoint hint");
        }
        host = host.strip();
    }
}
