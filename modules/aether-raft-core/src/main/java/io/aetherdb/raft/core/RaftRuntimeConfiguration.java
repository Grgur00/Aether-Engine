package io.aetherdb.raft.core;

import io.aetherdb.config.AetherConfigRegistry;
import io.aetherdb.config.AetherConfigValidator;
import io.aetherdb.config.AetherConfiguration;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/** Chapter 32 Raft runtime settings resolved for election and replication code. */
public record RaftRuntimeConfiguration(
        Duration electionTimeoutMin,
        Duration electionTimeoutMax,
        Duration heartbeatInterval,
        long snapshotThresholdEntries,
        long maxUncommittedBytes) {
    private static final AetherConfigRegistry REGISTRY = AetherConfigRegistry.defaults();

    public RaftRuntimeConfiguration {
        Objects.requireNonNull(electionTimeoutMin, "electionTimeoutMin");
        Objects.requireNonNull(electionTimeoutMax, "electionTimeoutMax");
        Objects.requireNonNull(heartbeatInterval, "heartbeatInterval");
        if (electionTimeoutMin.isNegative()
                || electionTimeoutMin.isZero()
                || electionTimeoutMax.compareTo(electionTimeoutMin) < 0
                || heartbeatInterval.isNegative()
                || heartbeatInterval.isZero()
                || !electionTimeoutMin.minus(heartbeatInterval.multipliedBy(2)).isPositive()
                || snapshotThresholdEntries <= 0
                || maxUncommittedBytes <= 0) {
            throw new IllegalArgumentException("invalid Raft runtime configuration");
        }
    }

    public static RaftRuntimeConfiguration defaults() {
        return from(new AetherConfiguration(Map.of("aether.security.profile", "development")));
    }

    public static RaftRuntimeConfiguration from(AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        return new RaftRuntimeConfiguration(
                Duration.ofMillis(longValue(configuration, "aether.raft.election_timeout_min_millis")),
                Duration.ofMillis(longValue(configuration, "aether.raft.election_timeout_max_millis")),
                Duration.ofMillis(longValue(configuration, "aether.raft.heartbeat_interval_millis")),
                longValue(configuration, "aether.raft.snapshot_threshold_entries"),
                longValue(configuration, "aether.raft.max_uncommitted_bytes"));
    }

    public boolean shouldSnapshot(long committedEntriesSinceSnapshot) {
        return committedEntriesSinceSnapshot >= snapshotThresholdEntries;
    }

    public boolean admitsUncommittedBytes(long currentUncommittedBytes, long additionalBytes) {
        if (currentUncommittedBytes < 0 || additionalBytes < 0)
            throw new IllegalArgumentException("uncommitted bytes must be non-negative");
        return currentUncommittedBytes <= maxUncommittedBytes - additionalBytes;
    }

    private static long longValue(AetherConfiguration configuration, String name) {
        return Long.parseLong(configuration.getOrDefault(REGISTRY.require(name)));
    }
}
