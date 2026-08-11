package io.aetherdb.cluster.api;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

/**
 * Canonical membership and advertised endpoints for one cluster node.
 *
 * @param nodeId unique non-zero node identity
 * @param role durable membership role
 * @param flags reserved member flags
 * @param generation positive identity generation
 * @param addedAtIndex log index at which the member was added
 * @param addedAtTerm log term at which the member was added
 * @param name human-readable UTF-8 name
 * @param identityHash 32-byte node identity hash
 * @param endpoints one to eight canonical advertised endpoints
 */
public record ClusterMember(
        UUID nodeId,
        MemberRole role,
        int flags,
        long generation,
        long addedAtIndex,
        long addedAtTerm,
        String name,
        byte[] identityHash,
        List<ClusterEndpoint> endpoints) {
    /** Validates membership history and canonicalizes endpoint storage. */
    public ClusterMember {
        if (nodeId == null || nodeId.equals(new UUID(0, 0)) || role == null)
            throw new IllegalArgumentException("member identity and role are required");
        if (generation < 1 || addedAtIndex < 1 || addedAtTerm < 1)
            throw new IllegalArgumentException("invalid member history");
        if (name == null || name.getBytes(StandardCharsets.UTF_8).length > 128)
            throw new IllegalArgumentException("invalid member name");
        if (identityHash == null || identityHash.length != 32)
            throw new IllegalArgumentException("identity hash must be 32 bytes");
        identityHash = identityHash.clone();
        endpoints = endpoints == null ? List.of() : endpoints.stream().sorted().toList();
        if (endpoints.isEmpty() || endpoints.size() > 8)
            throw new IllegalArgumentException("members require 1..8 endpoints");
        for (int i = 1; i < endpoints.size(); i++)
            if (endpoints.get(i - 1).equals(endpoints.get(i)))
                throw new IllegalArgumentException("duplicate endpoint");
    }

    /**
     * Returns the node identity hash.
     *
     * @return defensive copy of the node identity hash
     */
    @Override
    public byte[] identityHash() {
        return identityHash.clone();
    }
}
