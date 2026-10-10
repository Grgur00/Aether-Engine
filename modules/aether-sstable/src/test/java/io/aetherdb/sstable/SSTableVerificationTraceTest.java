package io.aetherdb.sstable;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SSTableVerificationTraceTest {
    @TempDir Path root;

    @Test void ordinaryFinishStillFullyVerifiesWithAndWithoutTimings() throws Exception {
        for (int i = 0; i < 2; i++) {
            var builder = new SSTableBuilder(root.resolve("table-" + i), i + 1, UUID.randomUUID(), 0);
            builder.add(new InternalKey(new byte[] {1}, 1, (byte) 1), new byte[] {2, 3});
            try (var verification = SSTableVerificationTrace.begin()) {
                var timings = new SSTableFinishTrace();
                var metadata = i == 0 ? builder.finish() : builder.finish(timings);
                assertEquals(1L, verification.snapshot().get("tablesFullyVerified"));
                assertEquals(metadata.fileSize(), verification.snapshot().get("bytesFullyVerified"));
                assertEquals(0L, verification.snapshot().get("inventoryCalls"));
                if (i == 1) assertTrue(timings.stagesNs().containsKey("verificationOpenAndRead"));
            }
        }
    }

    @Test void traceRejectsNestingAndDoesNotLeakAcrossScopes() {
        try (var first = SSTableVerificationTrace.begin()) {
            assertThrows(IllegalStateException.class, SSTableVerificationTrace::begin);
            SSTableVerificationTrace.verified(10);
            SSTableVerificationTrace.verified(10);
            SSTableVerificationTrace.inventoryCall();
            assertEquals(2L, first.snapshot().get("tablesFullyVerified"));
            assertEquals(20L, first.snapshot().get("bytesFullyVerified"));
        }
        try (var next = SSTableVerificationTrace.begin()) {
            assertTrue(next.snapshot().values().stream().allMatch(value -> value == 0));
        }
    }
}
