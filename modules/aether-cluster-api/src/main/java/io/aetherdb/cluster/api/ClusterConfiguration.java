package io.aetherdb.cluster.api;
import java.util.Set;
import java.util.UUID;
public interface ClusterConfiguration {
    UUID clusterId(); long stateVersion(); Set<UUID> voters(); byte[] hash();
}
