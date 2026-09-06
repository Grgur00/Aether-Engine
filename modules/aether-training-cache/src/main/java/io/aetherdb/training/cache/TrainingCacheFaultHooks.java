package io.aetherdb.training.cache;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in test hook: announces an exact boundary and waits for an external kill. */
final class TrainingCacheFaultHooks {
    private TrainingCacheFaultHooks() {}

    static void reach(String point) {
        if (!point.equals(System.getProperty("aether.training.test.fault"))) return;
        String marker = System.getProperty("aether.training.test.marker");
        if (marker == null) throw new IllegalStateException("fault marker path is required");
        try {
            Files.writeString(Path.of(marker), point);
            while (true) Thread.sleep(1000);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("cannot announce fault boundary", failure);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("fault probe interrupted", interrupted);
        }
    }
}
