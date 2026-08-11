package io.aetherdb.lsm.read;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

final class ReadViewManagerTest {
    @Test
    void pinnedOldViewKeepsComponentsAliveUntilFinalRelease() {
        Source old = new Source();
        Source replacement = new Source();
        try (ReadViewManager manager =
                        new ReadViewManager(new ReadTopology(old, List.of(), null, 1));
                ReadViewHandle pin = manager.pinCurrent()) {
            manager.publish(new ReadTopology(replacement, List.of(), null, 2));
            assertTrue(pin.view().isRetired());
            assertEquals(0, old.releases.get());
        }
        assertEquals(1, old.releases.get());
        assertEquals(1, replacement.releases.get());
    }

    @Test
    void snapshotRegistryTracksDuplicateSequencesExactly() {
        SnapshotRegistry registry = new SnapshotRegistry();
        var first = registry.register(9);
        var second = registry.register(9);
        var third = registry.register(12);
        try {
            assertEquals(3, registry.activeCount());
            assertEquals(9, registry.oldestSequence());
            first.close();
            assertEquals(9, registry.oldestSequence());
            second.close();
            assertEquals(12, registry.oldestSequence());
        } finally {
            first.close();
            second.close();
            third.close();
        }
        assertEquals(0, registry.activeCount());
        assertEquals(-1, registry.oldestSequence());
    }

    private static final class Source implements RetainedSource {
        final AtomicInteger retains = new AtomicInteger();
        final AtomicInteger releases = new AtomicInteger();

        @Override
        public void retain() {
            retains.incrementAndGet();
        }

        @Override
        public void release() {
            releases.incrementAndGet();
        }
    }
}
