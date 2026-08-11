package io.aetherdb.cluster.api;

import java.util.UUID;

/**
 * Immutable identity established when a node first joins a cluster.
 *
 * @param clusterId owning cluster identity
 * @param nodeId unique node identity
 * @param creationEpochMillis identity creation time in Unix epoch milliseconds
 * @param initialRole role assigned at creation
 * @param generation identity generation, currently required to be one
 */
public record NodeIdentity(
        UUID clusterId,
        UUID nodeId,
        long creationEpochMillis,
        MemberRole initialRole,
        long generation) {
    /** Validates non-zero identities and the initial generation contract. */
    public NodeIdentity {
        if (clusterId == null
                || nodeId == null
                || clusterId.equals(new UUID(0, 0))
                || nodeId.equals(new UUID(0, 0))
                || creationEpochMillis < 0
                || initialRole == null
                || generation != 1) throw new IllegalArgumentException("invalid node identity");
    }
}
