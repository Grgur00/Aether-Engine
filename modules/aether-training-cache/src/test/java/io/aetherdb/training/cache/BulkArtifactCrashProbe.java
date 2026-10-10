package io.aetherdb.training.cache;

/** Test-only child process for actual termination at bulk publication boundaries. */
@SuppressWarnings("try")
public final class BulkArtifactCrashProbe {
    public static void main(String[] args) throws Exception {
        String point = System.getProperty("bulk.test.crash");
        try (var fault = io.aetherdb.reliability.CrashPointRegistry.install((id, context) -> {
            if (id.equals(point)) Runtime.getRuntime().halt(73);
        })) { BulkArtifactWriter.main(args); }
    }
}
