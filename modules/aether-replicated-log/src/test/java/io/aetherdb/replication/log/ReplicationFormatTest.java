package io.aetherdb.replication.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.replication.api.ReplicatedEntryType;

import org.junit.jupiter.api.Test;

import java.util.UUID;

final class ReplicationFormatTest {
    @Test
    void replicatedLogIdentityIsExactAndIntegrityProtected() {
        var identity =
                new ReplicatedLogIdentityV1(
                        UUID.fromString("11111111-1111-1111-8111-111111111111"),
                        UUID.fromString("22222222-2222-2222-8222-222222222222"),
                        42);
        byte[] encoded = identity.encode();
        assertThat(encoded).hasSize(256);
        assertThat(ReplicatedLogIdentityV1.decode(encoded)).isEqualTo(identity);
        encoded[140] = 1;
        assertThatThrownBy(() -> ReplicatedLogIdentityV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void replicatedBatchRoundTripPreservesOrderAndSequences() {
        try (WriteBatch batch =
                new WriteBatch()
                        .put(bytes("k"), bytes("a"))
                        .delete(bytes("k"))
                        .put(bytes("k"), bytes("b"))) {
            var range = StateSequencePlanner.plan(10, 3);
            var command =
                    ReplicatedWriteCommandV1.fromBatch(
                            UUID.fromString("33333333-3333-3333-8333-333333333333"), range, batch);
            var decoded = ReplicatedWriteCommandV1.decode(command.encode());
            assertThat(decoded).isEqualTo(command);
            assertThat(decoded.operations())
                    .extracting(ReplicatedWriteCommandV1.Operation::ordinal)
                    .containsExactly(0, 1, 2);
            assertThat(decoded.sequences().first()).isEqualTo(11);
            assertThat(decoded.sequences().last()).isEqualTo(13);
        }
    }

    @Test
    void sequencePlannerRejectsFollowerDiscontinuity() {
        var proposed = StateSequencePlanner.plan(5, 2);
        assertThatThrownBy(() -> StateSequencePlanner.validate(4, 2, proposed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not contiguous");
    }

    @Test
    void segmentHeaderIsExactAndBindsItsPreviousLogBoundary() {
        UUID cluster = UUID.fromString("11111111-1111-1111-8111-111111111111");
        UUID node = UUID.fromString("22222222-2222-2222-8222-222222222222");
        var first = new ReplicatedLogSegmentHeaderV1(cluster, node, 1, 1, 0, 0, new byte[32], 42);
        byte[] region = first.encodeRegion();

        assertThat(region).hasSize(4096);
        assertThat(ReplicatedLogSegmentHeaderV1.decodeRegion(region, cluster, node, 1))
                .isEqualTo(first);
        region[3000] = 1;
        assertThatThrownBy(
                        () -> ReplicatedLogSegmentHeaderV1.decodeRegion(region, cluster, node, 1))
                .hasMessageContaining("header-region tail");
    }

    @Test
    void alignedEntryRecordRoundTripsAndChainsCommandIdentity() {
        var commandId = UUID.fromString("33333333-3333-3333-8333-333333333333");
        ReplicatedWriteCommandV1 command;
        try (WriteBatch batch = new WriteBatch().put(bytes("key"), bytes("value"))) {
            command =
                    ReplicatedWriteCommandV1.fromBatch(
                            commandId, StateSequencePlanner.plan(0, 1), batch);
        }
        var first =
                ReplicatedLogEntryCodecV1.create(
                        ReplicatedEntryType.COMMAND,
                        1,
                        1,
                        7,
                        commandId,
                        1,
                        1,
                        new byte[32],
                        command.encode());
        byte[] encoded = ReplicatedLogEntryCodecV1.encode(first);
        var second =
                ReplicatedLogEntryCodecV1.create(
                        ReplicatedEntryType.NOOP,
                        0,
                        2,
                        7,
                        new UUID(0, 0),
                        0,
                        1,
                        first.entryHash(),
                        new byte[0]);

        assertThat(encoded.length % 8).isZero();
        assertThat(ReplicatedLogEntryCodecV1.decode(encoded)).isEqualTo(first);
        assertThat(ReplicatedLogEntryCodecV1.decode(ReplicatedLogEntryCodecV1.encode(second)))
                .isEqualTo(second);
        assertThat(second.previousEntryHash()).isEqualTo(first.entryHash());
    }

    @Test
    void entryParserRejectsHeaderPayloadPaddingAndTrailerCorruption() {
        var entry =
                ReplicatedLogEntryCodecV1.create(
                        ReplicatedEntryType.NOOP,
                        0,
                        1,
                        1,
                        new UUID(0, 0),
                        0,
                        0,
                        new byte[32],
                        new byte[0]);
        byte[] encoded = ReplicatedLogEntryCodecV1.encode(entry);
        assertThat(encoded).hasSize(200);

        for (int offset : new int[] {20, 188, 196, 199}) {
            byte[] corrupt = encoded.clone();
            corrupt[offset] ^= 1;
            assertThatThrownBy(() -> ReplicatedLogEntryCodecV1.decode(corrupt))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(UTF_8);
    }
}
