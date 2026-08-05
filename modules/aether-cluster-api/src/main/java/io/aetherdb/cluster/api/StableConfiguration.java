package io.aetherdb.cluster.api;
import java.util.List;
import java.util.UUID;

/** Cluster membership state outside a joint-consensus transition. */
public interface StableConfiguration extends ClusterConfiguration {
    /** Returns this configuration's identity.
     * @return unique identity of this configuration */
    UUID configurationId();
    /** Returns the hash-chain predecessor.
     * @return defensive copy of the preceding configuration hash */
    byte[] previousHash();
    /** Returns configured members.
     * @return canonically ordered immutable member list */
    List<ClusterMember> members();
    /** Returns the creation timestamp.
     * @return configuration creation time in Unix epoch milliseconds */
    long creationEpochMillis();
}
