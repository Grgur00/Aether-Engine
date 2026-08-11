package io.aetherdb.engine;

import static org.assertj.core.api.Assertions.*;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.aetherdb.api.*;
import io.aetherdb.api.exceptions.DatabaseOpenException;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.*;
import java.util.*;

class PersistentAetherDatabaseTest {
    @TempDir Path temp;

    @Test
    void valuesAndDeletesSurviveMultipleReopens() {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("a"), b("one"));
        }
        try (AetherDatabase db = Aether.open(root)) {
            assertThat(text(db.get(b("a")).value())).isEqualTo("one");
            db.put(b("a"), b("two"));
        }
        try (AetherDatabase db = Aether.open(root)) {
            assertThat(text(db.get(b("a")).value())).isEqualTo("two");
            db.delete(b("a"));
        }
        try (AetherDatabase db = Aether.open(root)) {
            assertThat(db.get(b("a")).isFound()).isFalse();
        }
    }

    @Test
    void atomicBatchAndSnapshotSemanticsSurviveReopen() {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("c"), b("old"));
            try (Snapshot snapshot = db.newSnapshot();
                    WriteBatch batch = new WriteBatch()) {
                batch.put(b("a"), b("1")).put(b("b"), b("2")).delete(b("c"));
                db.write(batch);
                assertThat(text(db.get(b("c"), snapshot).value())).isEqualTo("old");
                assertThat(db.get(b("c")).isFound()).isFalse();
            }
        }
        try (AetherDatabase db = Aether.open(root)) {
            assertThat(text(db.get(b("a")).value())).isEqualTo("1");
            assertThat(text(db.get(b("b")).value())).isEqualTo("2");
            assertThat(db.get(b("c")).isFound()).isFalse();
        }
    }

    @Test
    void walOnlyRecoveryReplaysForcedAcknowledgedGroup() throws IOException {
        Path live = temp.resolve("live"), crash = temp.resolve("crash");
        AetherDatabase db = Aether.open(live);
        db.put(b("key"), b("wal"));
        copyDatabase(live, crash);
        db.close();
        try (AetherDatabase recovered = Aether.open(crash)) {
            assertThat(text(recovered.get(b("key")).value())).isEqualTo("wal");
        }
    }

    @Test
    void checkpointAndNewerWalRecoverTogether() throws IOException {
        Path live = temp.resolve("live"), crash = temp.resolve("crash");
        try (AetherDatabase db = Aether.open(live)) {
            db.put(b("old"), b("checkpoint"));
        }
        AetherDatabase db = Aether.open(live);
        db.put(b("new"), b("wal"));
        copyDatabase(live, crash);
        db.close();
        try (AetherDatabase recovered = Aether.open(crash)) {
            assertThat(text(recovered.get(b("old")).value())).isEqualTo("checkpoint");
            assertThat(text(recovered.get(b("new")).value())).isEqualTo("wal");
        }
    }

    @Test
    void incompleteFinalWalTailIsDiscarded() throws IOException {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("safe"), b("value"));
        }
        Path wal =
                Files.list(root)
                        .filter(p -> p.getFileName().toString().startsWith("WAL-"))
                        .findFirst()
                        .orElseThrow();
        Files.write(wal, new byte[] {1, 2, 3, 4, 5}, StandardOpenOption.APPEND);
        try (AetherDatabase db = Aether.open(root)) {
            assertThat(text(db.get(b("safe")).value())).isEqualTo("value");
        }
        assertThat(Files.size(wal)).isGreaterThanOrEqualTo(32 * 1024);
    }

    @Test
    void exclusiveLockIsReleasedByIdempotentClose() {
        Path root = temp.resolve("db");
        AetherDatabase first = Aether.open(root);
        assertThatThrownBy(() -> Aether.open(root))
                .isInstanceOf(DatabaseOpenException.class)
                .hasRootCauseMessage("database lock is already held");
        first.close();
        first.close();
        try (AetherDatabase reopened = Aether.open(root)) {
            assertThat(reopened.isClosed()).isFalse();
        }
    }

    @Test
    void corruptCurrentFailsWithoutReinitializingAndReleasesLock() throws IOException {
        Path root = temp.resolve("db");
        try (AetherDatabase database = Aether.open(root)) {
            assertThat(database.isClosed()).isFalse();
        }
        Files.writeString(root.resolve("CURRENT"), "garbage\n");
        assertThatThrownBy(() -> Aether.open(root)).isInstanceOf(DatabaseOpenException.class);
        assertThatThrownBy(() -> Aether.open(root))
                .isInstanceOf(DatabaseOpenException.class)
                .hasRootCauseMessage("invalid CURRENT");
    }

    @Test
    void publishesCanonicalManifestAndSstableFiles() {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("key"), b("value"));
        }
        assertThat(root.resolve("CURRENT")).hasSize(128);
        assertThat(fileNames(root))
                .anyMatch(name -> name.matches("MANIFEST-[0-9]{20}\\.aeman"))
                .anyMatch(name -> name.matches("SST-[0-9]{20}\\.aess"))
                .noneMatch(name -> name.endsWith(".aesst") || name.equals("CHECKPOINT"));
    }

    @Test
    void snapshotsAndScansMergeActiveMemtableWithOlderSstables() {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("a"), b("old"));
            db.put(b("b"), b("stable"));
        }
        try (AetherDatabase db = Aether.open(root);
                Snapshot snapshot = db.newSnapshot()) {
            db.put(b("a"), b("new"));
            db.delete(b("b"));
            db.put(b("c"), b("active"));
            assertThat(text(db.get(b("a"), snapshot).value())).isEqualTo("old");
            assertThat(text(db.get(b("b"), snapshot).value())).isEqualTo("stable");
            assertThat(db.get(b("b")).isFound()).isFalse();
            try (AetherCursor cursor = db.scanAll()) {
                List<String> rows = new ArrayList<>();
                while (cursor.next()) rows.add(text(cursor.key()) + "=" + text(cursor.value()));
                assertThat(rows).containsExactly("a=new", "c=active");
            }
        }
    }

    @Test
    void corruptReferencedSstableIsFatal() throws IOException {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("key"), b("value"));
        }
        Path table =
                Files.list(root)
                        .filter(path -> path.getFileName().toString().endsWith(".aess"))
                        .findFirst()
                        .orElseThrow();
        byte[] bytes = Files.readAllBytes(table);
        bytes[4096] ^= 1;
        Files.write(table, bytes);
        assertThatThrownBy(() -> Aether.open(root))
                .isInstanceOf(DatabaseOpenException.class)
                .hasRootCauseInstanceOf(io.aetherdb.sstable.SSTableCorruptionException.class);
    }

    @Test
    void levelZeroCompactionPublishesReplacementBeforeDeletingInputs() throws IOException {
        Path root = temp.resolve("db");
        for (int cycle = 0; cycle < 4; cycle++) {
            try (AetherDatabase db = Aether.open(root)) {
                db.put(b("key-" + cycle), b("value-" + cycle));
                db.put(b("shared"), b("revision-" + cycle));
            }
        }
        try (AetherDatabase db = Aether.open(root)) {
            for (int cycle = 0; cycle < 4; cycle++)
                assertThat(text(db.get(b("key-" + cycle)).value())).isEqualTo("value-" + cycle);
            assertThat(text(db.get(b("shared")).value())).isEqualTo("revision-3");
        }
        try (var files = Files.list(root)) {
            assertThat(
                            files.filter(path -> path.getFileName().toString().endsWith(".aess"))
                                    .count())
                    .isEqualTo(1);
        }
    }

    @Test
    void concurrentGroupCommitAssignsContiguousSequencesAndRecoversEveryWrite() throws Exception {
        Path root = temp.resolve("db");
        int writers = 32;
        List<WriteResult> results = Collections.synchronizedList(new ArrayList<>());
        try (AetherDatabase db = Aether.open(root)) {
            java.util.concurrent.CountDownLatch
                    ready = new java.util.concurrent.CountDownLatch(writers),
                    start = new java.util.concurrent.CountDownLatch(1);
            try (var executor = java.util.concurrent.Executors.newFixedThreadPool(writers)) {
                for (int index = 0; index < writers; index++) {
                    int id = index;
                    executor.submit(
                            () -> {
                                ready.countDown();
                                start.await();
                                try (WriteBatch batch = new WriteBatch()) {
                                    batch.put(b("concurrent-" + id), b("value-" + id));
                                    results.add(db.write(batch, WriteOptions.defaults()));
                                }
                                return null;
                            });
                }
                ready.await();
                start.countDown();
                executor.shutdown();
                assertThat(executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS))
                        .isTrue();
            }
            assertThat(((PersistentAetherDatabase) db).walForceCountForTesting())
                    .isLessThan(writers);
        }
        assertThat(results).hasSize(writers);
        assertThat(results.stream().map(WriteResult::firstSequence).sorted().toList())
                .containsExactlyElementsOf(
                        java.util.stream.LongStream.rangeClosed(1, writers).boxed().toList());
        assertThat(results).allMatch(WriteResult::durabilityBarrierPerformed);
        try (AetherDatabase db = Aether.open(root)) {
            for (int index = 0; index < writers; index++)
                assertThat(text(db.get(b("concurrent-" + index)).value()))
                        .isEqualTo("value-" + index);
        }
    }

    @Test
    void flushRotatesWalAndRecoveryCleansKnownCrashWindowOrphans() throws IOException {
        Path root = temp.resolve("db");
        try (AetherDatabase db = Aether.open(root)) {
            db.put(b("key"), b("value"));
        }
        List<Path> wals;
        try (var files = Files.list(root)) {
            wals = files.filter(path -> path.getFileName().toString().endsWith(".aewal")).toList();
        }
        assertThat(wals).hasSize(1);
        long segment = Long.parseLong(wals.get(0).getFileName().toString().substring(4, 24));
        Path orphanWal = root.resolve(io.aetherdb.wal.format.WalFormatV1.fileName(segment + 1));
        Files.copy(wals.get(0), orphanWal);
        Path orphanTable = root.resolve("SST-99999999999999999999.aess");
        Files.write(orphanTable, new byte[] {1});
        try (AetherDatabase db = Aether.open(root)) {
            assertThat(text(db.get(b("key")).value())).isEqualTo("value");
        }
        assertThat(orphanWal).doesNotExist();
        assertThat(orphanTable).doesNotExist();
    }

    @Test
    void checksumFailureInsideAcknowledgedWalIsFatal() throws IOException {
        Path live = temp.resolve("live"), crash = temp.resolve("crash");
        AetherDatabase db = Aether.open(live);
        db.put(b("key"), b("value"));
        copyDatabase(live, crash);
        db.close();
        Path wal =
                Files.list(crash)
                        .filter(path -> path.getFileName().toString().endsWith(".aewal"))
                        .findFirst()
                        .orElseThrow();
        byte[] bytes = Files.readAllBytes(wal);
        bytes[io.aetherdb.wal.format.WalFormatV1.HEADER_BLOCK_BYTES + 20] ^= 1;
        Files.write(wal, bytes);
        assertThatThrownBy(() -> Aether.open(crash))
                .isInstanceOf(DatabaseOpenException.class)
                .hasRootCauseInstanceOf(io.aetherdb.wal.format.WalCorruptionException.class);
    }

    private static byte[] b(String s) {
        return s.getBytes(UTF_8);
    }

    private static String text(byte[] b) {
        return new String(b, UTF_8);
    }

    private static void copyDatabase(Path from, Path to) throws IOException {
        Files.createDirectories(to);
        try (var files = Files.list(from)) {
            for (Path source : files.toList())
                if (!source.getFileName().toString().equals("LOCK"))
                    Files.copy(
                            source,
                            to.resolve(source.getFileName()),
                            StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static List<String> fileNames(Path root) {
        try (var files = Files.list(root)) {
            return files.map(path -> path.getFileName().toString()).toList();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
