package io.aetherdb.replication.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.replication.api.ReplicatedEntryType;
import io.aetherdb.replication.api.ReplicatedLogEntry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.UUID;

class ReplicatedLogStoreV1Test {
    private static final UUID CLUSTER = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID NODE = UUID.fromString("22222222-2222-4222-8222-222222222222");
    @TempDir Path temporaryDirectory;

    @Test
    void appendForceRangeCloseAndReopenPreserveExactTail() {
        Path directory = temporaryDirectory.resolve("replication");
        ReplicatedLogEntry first, second;
        try (var store = ReplicatedLogStoreV1.open(directory, CLUSTER, NODE)) {
            first = command(1, 1, 1, new byte[32], "a");
            second = command(2, 1, 2, first.entryHash(), "b");
            store.append(List.of(first, second));
            assertThat(store.lastIndex()).isEqualTo(2);
            assertThat(store.durableIndex()).isZero();
            assertThat(store.readRange(1, 3, 1, 10)).containsExactly(first);
            store.forceThrough(1);
            assertThat(store.durableIndex()).isEqualTo(2);
        }
        try (var reopened = ReplicatedLogStoreV1.open(directory, CLUSTER, NODE)) {
            assertThat(reopened.firstIndex()).isEqualTo(1);
            assertThat(reopened.lastIndex()).isEqualTo(2);
            assertThat(reopened.lastTerm()).isEqualTo(1);
            assertThat(reopened.lastStateSequence()).isEqualTo(2);
            assertThat(reopened.durableIndex()).isEqualTo(2);
            assertThat(reopened.read(2)).isEqualTo(second);
        }
    }

    @Test
    void suffixTruncationProtectsCommitAndAppliedBoundariesAndPermitsSafeReuse() {
        Path directory = temporaryDirectory.resolve("truncate");
        try (var store = ReplicatedLogStoreV1.open(directory, CLUSTER, NODE)) {
            var first = command(1, 1, 1, new byte[32], "a");
            var second = command(2, 1, 2, first.entryHash(), "b");
            var third = command(3, 1, 3, second.entryHash(), "c");
            store.appendAndForce(List.of(first, second, third));
            assertThatThrownBy(() -> store.truncateSuffix(2, 2, 1))
                    .hasMessageContaining("commit/applied");
            assertThatThrownBy(() -> store.truncateSuffix(2, 1, 2))
                    .hasMessageContaining("commit/applied");

            store.truncateSuffix(2, 1, 1);
            var replacement = command(2, 2, 2, first.entryHash(), "replacement");
            store.appendAndForce(List.of(replacement));
            assertThat(store.lastIndex()).isEqualTo(2);
            assertThat(store.lastTerm()).isEqualTo(2);
            assertThat(store.read(2)).isEqualTo(replacement);
        }
    }

    @Test
    void reopenRepairsOnlyAnIncompleteFinalTailAndRejectsFullCorruption() throws Exception {
        Path repair = temporaryDirectory.resolve("repair");
        try (var store = ReplicatedLogStoreV1.open(repair, CLUSTER, NODE)) {
            store.appendAndForce(List.of(command(1, 1, 1, new byte[32], "a")));
        }
        Path segment = repair.resolve(ReplicatedLogFormatV1.segmentName(1));
        long validSize = Files.size(segment);
        Files.write(segment, new byte[] {1, 2, 3, 4}, StandardOpenOption.APPEND);
        try (var reopened = ReplicatedLogStoreV1.open(repair, CLUSTER, NODE)) {
            assertThat(reopened.lastIndex()).isEqualTo(1);
        }
        assertThat(Files.size(segment)).isEqualTo(validSize);

        Path corrupt = temporaryDirectory.resolve("corrupt");
        try (var store = ReplicatedLogStoreV1.open(corrupt, CLUSTER, NODE)) {
            store.appendAndForce(List.of(command(1, 1, 1, new byte[32], "a")));
        }
        Path corruptSegment = corrupt.resolve(ReplicatedLogFormatV1.segmentName(1));
        byte[] bytes = Files.readAllBytes(corruptSegment);
        bytes[4096 + 192] ^= 1;
        Files.write(corruptSegment, bytes);
        assertThatThrownBy(() -> ReplicatedLogStoreV1.open(corrupt, CLUSTER, NODE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot open replicated log");
    }

    @Test
    void immutableIdentityRejectsOpeningForAnotherNode() {
        Path directory = temporaryDirectory.resolve("identity");
        try (var store = ReplicatedLogStoreV1.open(directory, CLUSTER, NODE)) {
            assertThat(store.identity().nodeId()).isEqualTo(NODE);
        }
        assertThatThrownBy(
                        () ->
                                ReplicatedLogStoreV1.open(
                                        directory,
                                        CLUSTER,
                                        UUID.fromString("33333333-3333-4333-8333-333333333333")))
                .hasMessageContaining("cannot open replicated log");
    }

    private static ReplicatedLogEntry command(
            long index, long term, long sequence, byte[] previousHash, String value) {
        UUID commandId = UUID.nameUUIDFromBytes(("command-" + index + '-' + term).getBytes(UTF_8));
        ReplicatedWriteCommandV1 command;
        try (WriteBatch batch =
                new WriteBatch().put(("key-" + index).getBytes(UTF_8), value.getBytes(UTF_8))) {
            command =
                    ReplicatedWriteCommandV1.fromBatch(
                            commandId,
                            new io.aetherdb.replication.api.StateSequenceRange(sequence, sequence),
                            batch);
        }
        return ReplicatedLogEntryCodecV1.create(
                ReplicatedEntryType.COMMAND,
                1,
                index,
                term,
                commandId,
                sequence,
                sequence,
                previousHash,
                command.encode());
    }
}
