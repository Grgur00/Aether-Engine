package io.aetherdb.rpc.transport;

import java.util.UUID;

/** Stable cluster/node identity plus the current process-session identity. */
public record RpcIdentity(UUID clusterId, UUID nodeId, UUID sessionId) {
    /** Validates three distinct non-zero identities. */
    public RpcIdentity {
        UUID zero = new UUID(0, 0);
        if (clusterId == null || nodeId == null || sessionId == null
                || clusterId.equals(zero) || nodeId.equals(zero) || sessionId.equals(zero)) {
            throw new IllegalArgumentException("invalid RPC identity");
        }
    }

    /** Creates a process identity with a freshly randomized session ID. */
    public static RpcIdentity start(UUID clusterId, UUID nodeId) {
        return new RpcIdentity(clusterId, nodeId, UUID.randomUUID());
    }
}
