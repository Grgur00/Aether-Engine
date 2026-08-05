package io.aetherdb.cluster.api;
import java.util.Set;import java.util.UUID;
public interface JointConfiguration extends ClusterConfiguration {UUID transitionId();StableConfiguration oldConfiguration();StableConfigurationV2 targetConfiguration();UUID proposerNodeId();long creationEpochMillis();default Set<UUID> voters(){var s=new java.util.HashSet<>(oldConfiguration().voters());s.addAll(targetConfiguration().voters());return Set.copyOf(s);}}
