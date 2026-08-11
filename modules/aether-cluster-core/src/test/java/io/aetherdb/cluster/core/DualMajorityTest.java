package io.aetherdb.cluster.core;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.*;

class DualMajorityTest {
    @Test
    void requiresIndependentMajorities() {
        UUID a = UUID.randomUUID(),
                b = UUID.randomUUID(),
                c = UUID.randomUUID(),
                d = UUID.randomUUID();
        Set<UUID> old = Set.of(a, b, c), next = Set.of(b, c, d);
        assertFalse(DualMajority.hasJointMajority(old, next, Set.of(a, b)));
        assertTrue(DualMajority.hasJointMajority(old, next, Set.of(b, c)));
        assertEquals(7, DualMajority.committedIndex(old, next, Map.of(a, 9L, b, 8L, c, 7L, d, 6L)));
    }
}
