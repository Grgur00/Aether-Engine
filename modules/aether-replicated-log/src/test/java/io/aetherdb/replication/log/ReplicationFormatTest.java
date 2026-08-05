package io.aetherdb.replication.log;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.WriteBatch;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ReplicationFormatTest {
    @Test void replicatedLogIdentityIsExactAndIntegrityProtected() {
        var identity = new ReplicatedLogIdentityV1(UUID.fromString("11111111-1111-1111-8111-111111111111"),
                UUID.fromString("22222222-2222-2222-8222-222222222222"), 42);
        byte[] encoded = identity.encode(); assertThat(encoded).hasSize(256); assertThat(ReplicatedLogIdentityV1.decode(encoded)).isEqualTo(identity);
        encoded[140] = 1;
        assertThatThrownBy(() -> ReplicatedLogIdentityV1.decode(encoded)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void replicatedBatchRoundTripPreservesOrderAndSequences() {
        try (WriteBatch batch = new WriteBatch().put(bytes("k"), bytes("a")).delete(bytes("k")).put(bytes("k"), bytes("b"))) {
            var range = StateSequencePlanner.plan(10, 3);
            var command = ReplicatedWriteCommandV1.fromBatch(UUID.fromString("33333333-3333-3333-8333-333333333333"), range, batch);
            var decoded = ReplicatedWriteCommandV1.decode(command.encode());
            assertThat(decoded).isEqualTo(command);
            assertThat(decoded.operations()).extracting(ReplicatedWriteCommandV1.Operation::ordinal).containsExactly(0, 1, 2);
            assertThat(decoded.sequences().first()).isEqualTo(11); assertThat(decoded.sequences().last()).isEqualTo(13);
        }
    }

    @Test void sequencePlannerRejectsFollowerDiscontinuity() {
        var proposed = StateSequencePlanner.plan(5, 2);
        assertThatThrownBy(() -> StateSequencePlanner.validate(4, 2, proposed))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("not contiguous");
    }
    private static byte[] bytes(String value) { return value.getBytes(UTF_8); }
}
