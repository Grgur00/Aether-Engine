package io.aetherdb.engine;

import static org.junit.jupiter.api.Assertions.*;
import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
class BackgroundCompactionTest {
    @TempDir Path temp;
    private static final byte[] PAYLOAD = new byte[196849];

    private AetherConfiguration config(boolean enabled) {
        return new AetherConfiguration(Map.of("aether.security.profile", "development",
                "aether.storage.disk_pressure.enabled", "false", "aether.memtable.native_bytes", "1048576",
                "aether.compaction.enabled", Boolean.toString(enabled)));
    }

    private List<Path> seed(Path root) throws Exception {
        for (int i = 0; i < 4; i++) {
            try (var db = PersistentAetherDatabase.open(root, config(false))) { db.put(new byte[] {(byte) i}, PAYLOAD); }
        }
        try (var files = Files.list(root)) { return files.filter(p -> p.toString().endsWith(".aess")).toList(); }
    }

    private static void waitFor(CountDownLatch latch) {
        try { assertTrue(latch.await(20, TimeUnit.SECONDS), "compaction barrier timed out"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    private static boolean worker() { return Thread.currentThread().getName().equals("aether-compaction"); }

    @Test void foregroundFlushAndReadsProceedDuringBuildAndManifestPreservesNewFlush() throws Exception {
        Path root = temp.resolve("concurrent");
        var inputs = seed(root);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var hook = CrashPointRegistry.install((id, context) -> {
            if (worker() && id.equals(CrashPointIds.COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST)) {
                ready.countDown(); waitFor(release);
            }
        }); var db = PersistentAetherDatabase.open(root, config(true))) {
            waitFor(ready);
            try (var snapshot = db.newSnapshot(); var cursor = db.scanAll()) {
                // A six-entry write sequence forces a new L0 while compaction owns its old inputs.
                var trace = new FlushDiagnostics.Collector("foreground");
                var previous = FlushDiagnostics.attach(trace);
                try {
                    for (int i = 4; i < 10; i++) db.put(new byte[] {(byte) i}, PAYLOAD);
                    db.put(new byte[] {0}, new byte[] {42});
                } finally { FlushDiagnostics.attach(previous); }
                assertEquals(1, trace.events().size());
                assertTrue(trace.compactions().isEmpty(), "foreground must not execute compaction");
                assertTrue(trace.compactionScheduled());
                assertFalse(trace.events().getFirst().stagesNs().containsKey("compaction"));
                assertArrayEquals(PAYLOAD, db.get(new byte[] {9}).value());
                assertArrayEquals(PAYLOAD, db.get(new byte[] {0}, snapshot).value());
                for (Path input : inputs) assertTrue(Files.exists(input), "inputs must live until install");
                release.countDown();
                db.awaitCompactionIdle();
                assertArrayEquals(PAYLOAD, db.get(new byte[] {0}, snapshot).value());
                int rows = 0;
                while (cursor.next()) { assertArrayEquals(PAYLOAD, cursor.value()); rows++; }
                assertEquals(4, rows, "materialized cursor survives retirement");
            } finally { release.countDown(); }
            for (Path input : inputs) assertFalse(Files.exists(input));
            assertEquals(1L, db.compactionDiagnostics().get("completed"));
        } finally { release.countDown(); }
        try (var db = PersistentAetherDatabase.open(root, config(false))) {
            assertArrayEquals(new byte[] {42}, db.get(new byte[] {0}).value());
            for (int i = 1; i < 10; i++) assertArrayEquals(PAYLOAD, db.get(new byte[] {(byte) i}).value());
        }
    }

    @Test void failuresBeforeAndAfterPublicationRecoverTheAuthoritativeFileSet() throws Exception {
        for (String point : List.of(CrashPointIds.COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST,
                CrashPointIds.COMPACTION_AFTER_MANIFEST_BEFORE_DELETE,
                CrashPointIds.MANIFEST_AFTER_APPEND_BEFORE_CURRENT)) {
            Path root = temp.resolve(point);
            var inputs = seed(root);
            var fired = new AtomicBoolean();
            try (var hook = CrashPointRegistry.install((id, context) -> {
                if (worker() && id.equals(point) && fired.compareAndSet(false, true)) throw new IllegalStateException("injected " + point);
            })) {
                var db = PersistentAetherDatabase.open(root, config(true));
                try {
                    db.awaitCompactionIdle();
                    assertTrue(fired.get());
                    assertEquals(1L, db.compactionDiagnostics().get("failed"));
                    for (int i = 0; i < 4; i++) assertArrayEquals(PAYLOAD, db.get(new byte[] {(byte) i}).value());
                    if (point.equals(CrashPointIds.MANIFEST_AFTER_APPEND_BEFORE_CURRENT))
                        assertThrows(RuntimeException.class, () -> db.put(new byte[] {99}, new byte[] {1}));
                    else {
                        db.put(new byte[] {99}, new byte[] {1}); // Non-ambiguous compaction failures do not fence writes.
                        assertArrayEquals(new byte[] {1}, db.get(new byte[] {99}).value());
                    }
                } finally {
                    if (point.equals(CrashPointIds.MANIFEST_AFTER_APPEND_BEFORE_CURRENT)) assertThrows(RuntimeException.class, db::close);
                    else db.close();
                }
            }
            // Recovery must discard abandoned temporary outputs using a narrowly matched owned name.
            Path orphan = root.resolve("SST-00000000000000000999.aess.tmp-0123456789abcdef0123456789abcdef");
            Files.write(orphan, new byte[] {1, 2});
            try (var db = PersistentAetherDatabase.open(root, config(false))) {
                for (int i = 0; i < 4; i++) assertArrayEquals(PAYLOAD, db.get(new byte[] {(byte) i}).value());
                assertFalse(Files.exists(orphan));
                if (point.equals(CrashPointIds.MANIFEST_AFTER_APPEND_BEFORE_CURRENT))
                    for (Path input : inputs) assertFalse(Files.exists(input));
            }
        }
    }

    @Test void shutdownWaitsForActiveBuildWithoutHoldingDatabaseMonitor() throws Exception {
        Path root = temp.resolve("shutdown");
        seed(root);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var hook = CrashPointRegistry.install((id, context) -> {
            if (worker() && id.equals(CrashPointIds.COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST)) {
                ready.countDown(); waitFor(release);
            }
        }); var executor = Executors.newSingleThreadExecutor()) {
            var db = PersistentAetherDatabase.open(root, config(true));
            try {
                waitFor(ready);
                var closing = executor.submit(db::close);
                assertThrows(TimeoutException.class, () -> closing.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                closing.get(20, TimeUnit.SECONDS);
                assertTrue(db.isClosed());
            } finally { release.countDown(); db.close(); }
        }
        try (var db = PersistentAetherDatabase.open(root, config(false))) {
            for (int i = 0; i < 4; i++) assertArrayEquals(PAYLOAD, db.get(new byte[] {(byte) i}).value());
        }
    }

    @Test void hardLevelZeroLimitRejectsBeforeWalAndWritesResumeAfterCompaction() throws Exception {
        Path root = temp.resolve("pressure");
        seed(root);
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var hook = CrashPointRegistry.install((id, context) -> {
            if (worker() && id.equals(CrashPointIds.COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST)) {
                ready.countDown(); waitFor(release);
            }
        }); var db = PersistentAetherDatabase.open(root, config(true))) {
            waitFor(ready);
            int rejected = -1;
            try {
                for (int i = 4; i < 120; i++) {
                    try { db.put(new byte[] {(byte) i}, PAYLOAD); }
                    catch (io.aetherdb.api.exceptions.AetherException pressure) {
                        assertTrue(pressure.getMessage().contains("RESOURCE_EXHAUSTED"));
                        rejected = i; break;
                    }
                }
                assertTrue(rejected > 4);
                assertFalse(db.get(new byte[] {(byte) rejected}).isFound());
                assertTrue((long) db.compactionDiagnostics().get("debtBytes") > 0);
            } finally { release.countDown(); }
            db.awaitCompactionIdle();
            db.put(new byte[] {(byte) rejected}, PAYLOAD);
            for (int i = 0; i <= rejected; i++) assertArrayEquals(PAYLOAD, db.get(new byte[] {(byte) i}).value());
        } finally { release.countDown(); }
    }

    @Test void repeatedRequestsCoalesceAndFailureRequiresAnotherEvent() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var request = new CompactionCoordinator.Request(System.nanoTime(), System.currentTimeMillis(), "test", 2, true);
        try (var coordinator = new CompactionCoordinator(r -> {
            if (calls.incrementAndGet() == 1) { entered.countDown(); waitFor(release); }
            return false;
        })) {
            coordinator.requestCompaction(request);
            waitFor(entered);
            for (int i = 0; i < 100; i++) coordinator.requestCompaction(request);
            release.countDown();
            coordinator.awaitIdle(Duration.ofSeconds(20));
            assertEquals(2, calls.get());
        } finally { release.countDown(); }
        var failedCalls = new AtomicInteger();
        try (var coordinator = new CompactionCoordinator(r -> { failedCalls.incrementAndGet(); throw new IllegalStateException("injected"); })) {
            coordinator.requestCompaction(request);
            coordinator.awaitIdle(Duration.ofSeconds(20));
            assertEquals(1, failedCalls.get());
            assertNotNull(coordinator.backgroundFailure());
            coordinator.requestCompaction(request);
            coordinator.awaitIdle(Duration.ofSeconds(20));
            assertEquals(2, failedCalls.get());
        }
    }
}
