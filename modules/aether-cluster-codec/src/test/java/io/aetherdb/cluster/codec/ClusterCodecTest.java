package io.aetherdb.cluster.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.aetherdb.cluster.api.*;

import org.junit.jupiter.api.Test;

import java.nio.*;
import java.util.*;

class ClusterCodecTest {
    private static final UUID CLUSTER = UUID.fromString("00000000-0000-0001-0000-000000000001"),
            NODE = UUID.fromString("00000000-0000-0002-0000-000000000002"),
            CONFIG = UUID.fromString("00000000-0000-0003-0000-000000000003");

    @Test
    void identityFormatsAreExactAndRejectCorruption() {
        var c = new ClusterIdentity(CLUSTER, 42, CONFIG, filled(1), filled(2));
        byte[] bytes = IdentityCodecV1.encodeCluster(c);
        assertEquals(256, bytes.length);
        assertEquals(c.clusterId(), IdentityCodecV1.decodeCluster(bytes).clusterId());
        bytes[20] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> IdentityCodecV1.decodeCluster(bytes));
        var n = new NodeIdentity(CLUSTER, NODE, 43, MemberRole.VOTER, 1);
        assertEquals(n, IdentityCodecV1.decodeNode(IdentityCodecV1.encodeNode(n)));
    }

    @Test
    void stableAndJointFormatsRoundTrip() {
        ClusterMember m = member(NODE, MemberRole.VOTER);
        var stable0 =
                new StableConfigurationV1(
                        CLUSTER, 1, CONFIG, new byte[32], List.of(m), 44, new byte[32]);
        StableConfigurationV1 stable =
                StableConfigurationCodecV1.decode(StableConfigurationCodecV1.encode(stable0));
        assertEquals(1, stable.voters().size());
        var target0 =
                new StableConfigurationV2(
                        CLUSTER,
                        3,
                        UUID.randomUUID(),
                        stable.hash(),
                        UUID.randomUUID(),
                        List.of(m),
                        45,
                        1,
                        new byte[32]);
        StableConfigurationV2 target =
                StableConfigurationCodecV2.decode(StableConfigurationCodecV2.encode(target0));
        assertEquals(192, headerLength(StableConfigurationCodecV2.encode(target)));
        var joint0 =
                new JointConfigurationV1(
                        target.completedTransitionId(), stable, target, NODE, 46, new byte[32]);
        byte[] joint = JointConfigurationCodecV1.encode(joint0);
        assertEquals(256, headerLength(joint));
        assertEquals(2, JointConfigurationCodecV1.decode(joint).stateVersion());
    }

    private static ClusterMember member(UUID id, MemberRole role) {
        return new ClusterMember(
                id,
                role,
                1,
                1,
                1,
                1,
                "node",
                filled(3),
                List.of(
                        ClusterEndpoint.of(
                                ClusterEndpoint.Scheme.AETHER_TLS,
                                "Example.COM.",
                                7000,
                                0,
                                ClusterEndpoint.INTERNAL_RPC)));
    }

    private static byte[] filled(int n) {
        byte[] b = new byte[32];
        Arrays.fill(b, (byte) n);
        return b;
    }

    private static int headerLength(byte[] b) {
        return Short.toUnsignedInt(ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort(6));
    }
}
