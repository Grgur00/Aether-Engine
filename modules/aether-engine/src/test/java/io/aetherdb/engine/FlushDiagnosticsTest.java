package io.aetherdb.engine;

import static org.junit.jupiter.api.Assertions.*;
import io.aetherdb.api.DurabilityMode;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.config.AetherConfiguration;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FlushDiagnosticsTest {
    @TempDir Path temp;

    @Test void memtableCapacityIsRecordedBeforeFlush() { exercise("MEMTABLE_CAPACITY", 1, 64); }
    @Test void walRolloverIsRecordedBeforeFlush() { exercise("WAL_SEGMENT", 4, 1); }

    private void exercise(String cause, int memMiB, int walMiB) {
        long mib = 1024 * 1024;
        var config = new AetherConfiguration(Map.of("aether.security.profile", "development",
                "aether.storage.disk_pressure.enabled", "false",
                "aether.memtable.native_bytes", Long.toString(memMiB * mib),
                "aether.wal.segment_bytes", Long.toString(walMiB * mib)));
        var collector = new FlushDiagnostics.Collector();
        byte[] payload = new byte[196849];
        var previous = FlushDiagnostics.attach(collector);
        try (var database = Aether.open(temp.resolve(cause), config)) {
            for (int i = 0; i < 6; i++) {
                try (var batch = new WriteBatch()) {
                    batch.put(new byte[] {(byte) i}, payload);
                    database.write(batch, new WriteOptions(DurabilityMode.SYNC, Duration.ofSeconds(30), false));
                }
            }
            assertEquals(1, collector.events().size());
            var event = collector.events().getFirst();
            assertEquals(cause, event.cause());
            assertTrue(event.completed());
            var c = event.counters();
            assertEquals(5L, c.get("memtableEntryCount"));
            assertEquals(memMiB * mib, c.get("memtableNativeLimitBytes"));
            assertEquals(walMiB * mib, c.get("walSegmentLimitBytes"));
            assertEquals(memMiB * mib, c.get("memtableNativeUsedBytes") + c.get("memtableNativeRemainingBytes"));
            assertEquals(48L + 12 + 1 + payload.length, c.get("logicalWalWriteBytes"));
            assertTrue(c.get("memtableNativeUsedBytes") >= 5L * payload.length);
            if (cause.equals("MEMTABLE_CAPACITY"))
                assertTrue(c.get("requiredNativeBytes") > c.get("memtableNativeRemainingBytes"));
            else {
                assertTrue(c.get("requiredNativeBytes") < c.get("memtableNativeRemainingBytes"));
                assertTrue(c.get("walPositionBytes") + c.get("logicalWalWriteBytes") > c.get("walSegmentLimitBytes"));
            }
            for (String stage : new String[] {"sstableBuild", "sstableFinish", "sstableRename", "directoryFsync1",
                    "walCreate", "manifestEdit", "walDelete", "directoryFsync2", "sstableOpen", "memtableReplace", "compactionSchedule"})
                assertTrue(event.stagesNs().get(stage) >= 0, stage);
            assertEquals(event.totalNs(), event.stagesNs().values().stream().mapToLong(Long::longValue).sum());
            for (int i = 0; i < 6; i++) assertArrayEquals(payload, database.get(new byte[] {(byte) i}).value());
        } finally {
            FlushDiagnostics.attach(previous);
        }
        assertEquals("OTHER", collector.events().getLast().cause());
        int count = collector.events().size();
        try (var reopened = Aether.open(temp.resolve(cause), config)) {
            for (int i = 0; i < 6; i++) assertArrayEquals(payload, reopened.get(new byte[] {(byte) i}).value());
        }
        assertEquals(count, collector.events().size());
        assertNull(FlushDiagnostics.current());
    }
}
