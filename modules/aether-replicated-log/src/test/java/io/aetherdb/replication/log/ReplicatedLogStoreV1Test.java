package io.aetherdb.replication.log;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.WriteBatch;
import io.aetherdb.format.catalog.AetherFormatCatalog;
import io.aetherdb.format.catalog.FormatGoldenFixture;
import io.aetherdb.format.catalog.FormatGoldenFixtureCatalog;
import io.aetherdb.io.DatabaseLock;
import io.aetherdb.replication.api.ReplicatedEntryType;
import io.aetherdb.replication.api.ReplicatedLogEntry;
import io.aetherdb.reliability.CrashPointException;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;
import io.aetherdb.reliability.ScopedCrashPoint;
import io.aetherdb.reliability.TriggeringCrashPoint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    void segmentHeaderMatchesGoldenFixtureCatalog() {
        ReplicatedLogSegmentHeaderV1 header =
                new ReplicatedLogSegmentHeaderV1(
                        CLUSTER, NODE, 1, 1, 0, 0, new byte[32], 1234);
        byte[] encoded = header.encodeRegion();
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require(
                                "aether.replicated_log_segment.v1",
                                "canonical-first-segment-header-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(ReplicatedLogSegmentHeaderV1.decodeRegion(encoded, CLUSTER, NODE, 1))
                .isEqualTo(header);
    }

    @Test
    void entryRecordMatchesGoldenFixtureCatalog() {
        ReplicatedLogEntry entry = command(1, 1, 1, new byte[32], "a");
        byte[] encoded = ReplicatedLogEntryCodecV1.encode(entry);
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.replicated_log_entry.v1", "canonical-command-entry-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(ReplicatedLogEntryCodecV1.decode(encoded)).isEqualTo(entry);
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
    void appendCrashPointFiresAfterLogForceBeforeReply() throws Exception {
        Path testRoot = Path.of("build", "tmp", "replicated-log-crash-tests");
        Files.createDirectories(testRoot);
        Path directory = Files.createTempDirectory(testRoot, "append-crash-point-");
        TriggeringCrashPoint crashPoint =
                new TriggeringCrashPoint(
                        CrashPointIds.RAFT_APPEND_AFTER_LOG_PERSIST_BEFORE_REPLY, 1);

        try (var store = storeForForceTesting(directory);
                ScopedCrashPoint scope = CrashPointRegistry.install(crashPoint)) {
            assertThat(scope).isNotNull();
            assertThatThrownBy(
                            () ->
                                    store.appendAndForce(
                                            List.of(command(1, 1, 1, new byte[32], "a"))))
                    .isInstanceOf(IllegalStateException.class)
                    .hasRootCauseInstanceOf(CrashPointException.class);
            assertThat(store.durableIndex()).isEqualTo(1);
        }

        assertThat(crashPoint.hits()).isEqualTo(1);
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

    private static ReplicatedLogStoreV1 storeForForceTesting(Path root) throws Exception {
        DatabaseLock lock = DatabaseLock.acquire(root);
        Constructor<ReplicatedLogStoreV1> constructor =
                ReplicatedLogStoreV1.class.getDeclaredConstructor(
                        Path.class, ReplicatedLogIdentityV1.class, DatabaseLock.class);
        constructor.setAccessible(true);
        ReplicatedLogStoreV1 store =
                constructor.newInstance(
                        root, new ReplicatedLogIdentityV1(CLUSTER, NODE, 1), lock);
        Path segment = root.resolve(ReplicatedLogFormatV1.segmentName(1));
        FileChannel active =
                FileChannel.open(
                        segment,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
        set(store, "active", active);
        set(store, "activePath", segment);
        set(store, "activeSegmentNumber", 1L);
        set(store, "nextSegmentNumber", 2L);
        return store;
    }

    private static void set(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
