package io.aetherdb.cluster.api;

import java.util.UUID;

/**
 * Durable identity and bootstrap trust anchor for a cluster.
 *
 * @param clusterId non-zero cluster identity
 * @param creationEpochMillis creation time in Unix epoch milliseconds
 * @param bootstrapConfigurationId initial configuration identity
 * @param bootstrapConfigurationHash canonical initial configuration hash
 * @param compatibilityFingerprint cluster format compatibility fingerprint
 */
public record ClusterIdentity(
        UUID clusterId,
        long creationEpochMillis,
        UUID bootstrapConfigurationId,
        byte[] bootstrapConfigurationHash,
        byte[] compatibilityFingerprint) {
    /** Validates identifiers and defensively copies both hashes. */
    public ClusterIdentity {
        if (clusterId == null
                || clusterId.equals(new UUID(0, 0))
                || bootstrapConfigurationId == null
                || bootstrapConfigurationId.equals(new UUID(0, 0))
                || creationEpochMillis < 0)
            throw new IllegalArgumentException("invalid cluster identity");
        if (bootstrapConfigurationHash == null
                || bootstrapConfigurationHash.length != 32
                || compatibilityFingerprint == null
                || compatibilityFingerprint.length != 32)
            throw new IllegalArgumentException("identity hashes must be 32 bytes");
        bootstrapConfigurationHash = bootstrapConfigurationHash.clone();
        compatibilityFingerprint = compatibilityFingerprint.clone();
    }

    /**
     * Returns the bootstrap hash.
     *
     * @return defensive copy of the bootstrap configuration hash
     */
    @Override
    public byte[] bootstrapConfigurationHash() {
        return bootstrapConfigurationHash.clone();
    }

    /**
     * Returns the format fingerprint.
     *
     * @return defensive copy of the compatibility fingerprint
     */
    @Override
    public byte[] compatibilityFingerprint() {
        return compatibilityFingerprint.clone();
    }
}
