package io.aetherdb.cluster.api;
import java.util.Set;import java.util.UUID;

/** Membership transition requiring independent majorities of old and target voters. */
public interface JointConfiguration extends ClusterConfiguration {
    /** Returns the transition identity.
     * @return unique transition identity */
    UUID transitionId();
    /** Returns the outgoing configuration.
     * @return stable configuration being replaced */
    StableConfiguration oldConfiguration();
    /** Returns the incoming configuration.
     * @return stable configuration that will complete the transition */
    StableConfigurationV2 targetConfiguration();
    /** Returns the proposing node.
     * @return node that proposed the transition */
    UUID proposerNodeId();
    /** Returns the creation timestamp.
     * @return transition creation time in Unix epoch milliseconds */
    long creationEpochMillis();
    /** {@inheritDoc} */
    default Set<UUID> voters(){var s=new java.util.HashSet<>(oldConfiguration().voters());s.addAll(targetConfiguration().voters());return Set.copyOf(s);}
}
