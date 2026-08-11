package io.aetherdb.cluster.api;

import java.util.Set;
import java.util.UUID;

/** Common immutable contract for stable and joint cluster configurations. */
public interface ClusterConfiguration {
    /**
     * Returns the owning cluster.
     *
     * @return cluster to which this configuration belongs
     */
    UUID clusterId();

    /**
     * Returns the configuration version.
     *
     * @return monotonic configuration state version
     */
    long stateVersion();

    /**
     * Returns quorum participants.
     *
     * @return immutable set of nodes participating in quorum decisions
     */
    Set<UUID> voters();

    /**
     * Returns the configuration hash.
     *
     * @return defensive copy of the canonical configuration hash
     */
    byte[] hash();
}
