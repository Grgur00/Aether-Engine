package io.aetherdb.cluster.api;
import java.util.List;
import java.util.UUID;
public interface StableConfiguration extends ClusterConfiguration { UUID configurationId(); byte[] previousHash(); List<ClusterMember> members(); long creationEpochMillis(); }
