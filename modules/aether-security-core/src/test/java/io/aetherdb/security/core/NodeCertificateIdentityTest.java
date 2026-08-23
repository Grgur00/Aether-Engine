package io.aetherdb.security.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class NodeCertificateIdentityTest {
    private static final UUID CLUSTER = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID NODE = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @Test
    void parsesAetherNodeSanUri() {
        NodeCertificateIdentity identity =
                NodeCertificateIdentity.parse("aether-node://" + CLUSTER + "/" + NODE);

        assertThat(identity.clusterId()).isEqualTo(CLUSTER);
        assertThat(identity.nodeId()).isEqualTo(NODE);
        assertThat(identity.toSanUri()).isEqualTo("aether-node://" + CLUSTER + "/" + NODE);
    }

    @Test
    void rejectsWrongShape() {
        assertThatThrownBy(() -> NodeCertificateIdentity.parse("spiffe://example/node"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("scheme");
        assertThatThrownBy(() -> NodeCertificateIdentity.parse("aether-node://" + CLUSTER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("shape");
    }

    @Test
    void validatesExpectedClusterAndNode() {
        NodeCertificateIdentity identity =
                NodeIdentityBindingValidator.validateSanUris(
                        List.of("aether-node://" + CLUSTER + "/" + NODE), CLUSTER, NODE);

        assertThat(identity.nodeId()).isEqualTo(NODE);
    }

    @Test
    void rejectsMismatchedNode() {
        UUID other = UUID.fromString("33333333-3333-3333-3333-333333333333");

        assertThatThrownBy(
                        () ->
                                NodeIdentityBindingValidator.validateSanUris(
                                        List.of("aether-node://" + CLUSTER + "/" + other),
                                        CLUSTER,
                                        NODE))
                .isInstanceOf(NodeIdentityValidationException.class)
                .hasMessageContaining("node mismatch");
    }
}
