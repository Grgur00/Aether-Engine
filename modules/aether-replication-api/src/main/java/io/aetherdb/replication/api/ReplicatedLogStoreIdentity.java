package io.aetherdb.replication.api;

import java.util.UUID;

/** Stable cluster/node binding of one local replicated-log store. */
public record ReplicatedLogStoreIdentity(UUID clusterId, UUID nodeId) {
    /** Rejects absent and all-zero identities. */
    public ReplicatedLogStoreIdentity {
        UUID zero = new UUID(0, 0);
        if (clusterId == null || nodeId == null || clusterId.equals(zero) || nodeId.equals(zero)) {
            throw new IllegalArgumentException("invalid replicated-log store identity");
        }
    }
}
