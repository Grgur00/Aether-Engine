package io.aetherdb.cluster.core;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Quorum calculations for stable and joint-consensus cluster configurations. */
public final class DualMajority {
    private DualMajority() {}

    /**
     * Tests whether acknowledgements cover a strict majority of voters.
     *
     * @param voters configured voters
     * @param acknowledgements nodes that acknowledged the operation
     * @return {@code true} when a strict majority acknowledged
     */
    public static boolean hasMajority(Set<UUID> voters, Set<UUID> acknowledgements) {
        Objects.requireNonNull(voters);
        Objects.requireNonNull(acknowledgements);
        if (voters.isEmpty()) throw new IllegalArgumentException("voter set is empty");
        int count = 0;
        for (UUID id : voters) if (acknowledgements.contains(id)) count++;
        return count >= voters.size() / 2 + 1;
    }

    /**
     * Tests whether acknowledgements independently satisfy both sides of a joint configuration.
     *
     * @param oldVoters outgoing voter set
     * @param newVoters incoming voter set
     * @param acknowledgements nodes that acknowledged the operation
     * @return {@code true} when both voter sets have a strict majority
     */
    public static boolean hasJointMajority(
            Set<UUID> oldVoters, Set<UUID> newVoters, Set<UUID> acknowledgements) {
        return hasMajority(oldVoters, acknowledgements) && hasMajority(newVoters, acknowledgements);
    }

    /**
     * Determines the highest index durably replicated to both required majorities.
     *
     * @param oldVoters outgoing voter set
     * @param newVoters incoming voter set
     * @param durableIndexes latest durable index reported by each node
     * @return joint-consensus committed index
     */
    public static long committedIndex(
            Set<UUID> oldVoters, Set<UUID> newVoters, Map<UUID, Long> durableIndexes) {
        return Math.min(majorityIndex(oldVoters, durableIndexes), majorityIndex(newVoters, durableIndexes));
    }

    private static long majorityIndex(Set<UUID> voters, Map<UUID, Long> indexes) {
        if (voters.isEmpty()) throw new IllegalArgumentException("voter set is empty");
        long[] values = voters.stream().mapToLong(id -> indexes.getOrDefault(id, 0L)).sorted().toArray();
        return values[values.length - (values.length / 2 + 1)];
    }
}
