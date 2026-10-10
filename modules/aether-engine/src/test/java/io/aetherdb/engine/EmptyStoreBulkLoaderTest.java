package io.aetherdb.engine;

import static org.junit.jupiter.api.Assertions.*;
import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.reliability.CrashPointRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings("try")
class EmptyStoreBulkLoaderTest {
    @TempDir Path temp;
    private final AetherConfiguration config = new AetherConfiguration(Map.of(
            "aether.security.profile", "development", "aether.storage.disk_pressure.enabled", "false"));

    @Test void sortedTablesSurviveRestartAndSubsequentOnlineWrites() throws Exception {
        try (var bulk = new EmptyStoreBulkLoader(temp, config, 1024, 8)) {
            for (int i = 9; i >= 0; i--) bulk.add(new byte[] {(byte) i}, new byte[] {(byte) (i + 1)});
            var receipt = bulk.finish();
            assertEquals(10L, receipt.get("entries"));
            assertEquals(3, receipt.get("tables"));
            assertEquals(0, receipt.get("walPayloadBytes"));
            assertEquals(0, receipt.get("memtableInsertions"));
            var verification = (Map<?, ?>) receipt.get("verification");
            assertEquals(1L, verification.get("inventoryCalls"));
            assertEquals(3L, verification.get("tablesFullyVerified"));
            try (var files = Files.list(temp)) {
                long bytes = 0;
                for (Path file : files.filter(p -> p.toString().endsWith(".aess")).toList()) bytes += Files.size(file);
                assertEquals(bytes, verification.get("bytesFullyVerified"));
            }
            assertEquals("bulk-deferred-inventory-v2", receipt.get("verificationPolicy"));
            assertThrows(IllegalStateException.class, bulk::finish);
        }
        try (var db = Aether.open(temp, config)) {
            for (int i = 0; i < 10; i++) assertArrayEquals(new byte[] {(byte) (i + 1)}, db.get(new byte[] {(byte) i}).value());
            db.put(new byte[] {20}, new byte[] {99});
        }
        try (var db = Aether.open(temp, config)) {
            assertArrayEquals(new byte[] {99}, db.get(new byte[] {20}).value());
            assertArrayEquals(new byte[] {1}, db.get(new byte[] {0}).value());
        }
        assertThrows(IllegalStateException.class, () -> new EmptyStoreBulkLoader(temp, config));
    }

    @Test void abortAndDuplicateNeverPublishPartialArtifacts() throws Exception {
        try (var bulk = new EmptyStoreBulkLoader(temp, config)) {
            bulk.add(new byte[] {1}, new byte[] {2});
            assertThrows(IllegalArgumentException.class, () -> bulk.add(new byte[] {1}, new byte[] {2}));
            assertThrows(IllegalStateException.class, bulk::finish);
        }
        try (var db = Aether.open(temp, config)) { assertFalse(db.get(new byte[] {1}).isFound()); }
        try (var bulk = new EmptyStoreBulkLoader(temp, config)) { bulk.add(new byte[] {3}, new byte[] {4}); }
        try (var db = Aether.open(temp, config)) { assertFalse(db.get(new byte[] {3}).isFound()); }
    }

    @Test void bufferLimitAndExclusiveLockFailClosed() throws Exception {
        try (var bulk = new EmptyStoreBulkLoader(temp, config, 2, 2)) {
            assertThrows(Exception.class, () -> Aether.open(temp, config));
            assertThrows(IllegalArgumentException.class, () -> bulk.add(new byte[] {1}, new byte[] {2, 3}));
            assertThrows(IllegalStateException.class, bulk::finish);
        }
    }

    @Test void publicationFailureRecoversAllOrNothing() throws Exception {
        for (String point : new String[] {"bulk.after_table", "bulk.before_manifest", "bulk.after_manifest"}) {
            Path root = temp.resolve(point);
            try (var bulk = new EmptyStoreBulkLoader(root, config, 1024, 2)) {
                for (int i = 0; i < 5; i++) bulk.add(new byte[] {(byte) i}, new byte[] {99});
                try (var fault = CrashPointRegistry.install((id, context) -> {
                    if (id.equals(point)) throw new IllegalStateException("injected bulk fault");
                })) { assertThrows(java.io.IOException.class, bulk::finish); }
            }
            try (var db = Aether.open(root, config)) {
                for (int i = 0; i < 5; i++) assertEquals(point.equals("bulk.after_manifest"), db.get(new byte[] {(byte) i}).isFound());
            }
        }
    }

    @Test void corruptedPublishedTableFailsRecovery() throws Exception {
        try (var bulk = new EmptyStoreBulkLoader(temp, config)) {
            bulk.add(new byte[] {1}, new byte[] {2});
            bulk.finish();
        }
        Path table;
        try (var files = Files.list(temp)) { table = files.filter(p -> p.toString().endsWith(".aess")).findFirst().orElseThrow(); }
        byte[] bytes = Files.readAllBytes(table);
        bytes[bytes.length / 2] ^= 1;
        Files.write(table, bytes);
        assertThrows(Exception.class, () -> Aether.open(temp, config));
    }

    @Test void bulkTargetsDoNotChangeTheOrdinaryWritePath() throws Exception {
        for (long target : new long[] {8, 16, 32}) {
            Path root = temp.resolve("target-" + target);
            try (var bulk = new EmptyStoreBulkLoader(root, config, 1024, target)) {
                for (int i = 0; i < 32; i++) bulk.add(new byte[] {(byte) i}, new byte[] {(byte) i});
                var result = bulk.finish();
                assertEquals((int) (64 / target), result.get("tables"));
                assertEquals(target, result.get("targetSstableBytes"));
                assertTrue(((Map<?, ?>) result.get("manifest")).containsKey("inventoryVerificationNs"));
            }
            try (var db = Aether.open(root, config)) {
                for (int i = 0; i < 32; i++) assertArrayEquals(new byte[] {(byte) i}, db.get(new byte[] {(byte) i}).value());
                db.put(new byte[] {99}, new byte[] {100});
            }
            try (var db = Aether.open(root, config)) { assertArrayEquals(new byte[] {100}, db.get(new byte[] {99}).value()); }
        }
    }

    @Test void verificationFailureNeverPublishesInventory() throws Exception {
        try (var bulk = new EmptyStoreBulkLoader(temp, config)) {
            bulk.add(new byte[] {1}, new byte[] {2});
            try (var fault = CrashPointRegistry.install((id, context) -> {
                if (id.equals("bulk.before_verification")) {
                    try (var files = Files.list(temp)) {
                        Path table = files.filter(p -> p.toString().endsWith(".aess")).findFirst().orElseThrow();
                        byte[] bytes = Files.readAllBytes(table);
                        bytes[bytes.length / 2] ^= 1;
                        Files.write(table, bytes);
                    } catch (java.io.IOException error) { throw new RuntimeException(error); }
                }
            })) { assertThrows(java.io.IOException.class, bulk::finish); }
        }
        try (var db = Aether.open(temp, config)) { assertFalse(db.get(new byte[] {1}).isFound()); }
    }

    @Test void finalInventoryRejectsContentCorruptionAndTruncationWithoutManifestAppend() throws Exception {
        for (boolean truncate : new boolean[] {false, true}) {
            Path root = temp.resolve(truncate ? "truncated" : "content");
            try (var bulk = new EmptyStoreBulkLoader(root, config, 1024, 8)) {
                for (int i = 0; i < 10; i++) bulk.add(new byte[] {(byte) i}, new byte[] {99});
                Path manifest;
                try (var files = Files.list(root)) {
                    manifest = files.filter(p -> p.getFileName().toString().startsWith("MANIFEST-"))
                            .findFirst().orElseThrow();
                }
                byte[] before = Files.readAllBytes(manifest);
                var corrupted = new java.util.concurrent.atomic.AtomicBoolean();
                try (var fault = CrashPointRegistry.install((id, context) -> {
                    if (!id.equals("bulk.before_verification")) return;
                    try (var files = Files.list(root)) {
                        var tables = files.filter(p -> p.toString().endsWith(".aess")).sorted().toList();
                        assertEquals(3, tables.size());
                        Path last = tables.get(tables.size() - 1);
                        byte[] bytes = Files.readAllBytes(last);
                        if (truncate) bytes = java.util.Arrays.copyOf(bytes, bytes.length - 1);
                        else bytes[io.aetherdb.sstable.SSTableHeaderV1.HEADER_REGION_BYTES + 8] ^= 1;
                        Files.write(last, bytes);
                        corrupted.set(true);
                    } catch (java.io.IOException error) { throw new RuntimeException(error); }
                })) {
                    assertThrows(java.io.IOException.class, bulk::finish);
                }
                assertTrue(corrupted.get(), "fault must run after all deferred tables were built");
                assertArrayEquals(before, Files.readAllBytes(manifest), "no failed inventory may append a manifest edit");
            }
            try (var db = Aether.open(root, config)) {
                for (int i = 0; i < 10; i++) assertFalse(db.get(new byte[] {(byte) i}).isFound());
            }
        }
    }
}
