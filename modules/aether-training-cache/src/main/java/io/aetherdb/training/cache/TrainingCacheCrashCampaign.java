package io.aetherdb.training.cache;

import java.nio.file.Files;
import java.nio.file.Path;

/** Repeatable forced-process recovery campaign for published training-cache values. */
public final class TrainingCacheCrashCampaign {
    private static final int CRASH_EXIT_CODE = 91;

    private TrainingCacheCrashCampaign() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length == 2 && arguments[0].equals("--worker")) {
            runWorker(Path.of(arguments[1]));
            return;
        }
        if (arguments.length < 1 || arguments.length > 2)
            throw new IllegalArgumentException("usage: <directory> [trials]");
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        int trials = arguments.length == 2 ? Integer.parseInt(arguments[1]) : 1000;
        for (int trial = 0; trial < trials; trial++) {
            Path directory = root.resolve("trial-" + trial);
            delete(directory);
            Process process = new ProcessBuilder(
                    javaBinary(), "--enable-preview", "-cp", System.getProperty("java.class.path"),
                    TrainingCacheCrashCampaign.class.getName(), "--worker", directory.toString())
                    .inheritIO().start();
            if (process.waitFor() != CRASH_EXIT_CODE)
                throw new IllegalStateException("crash worker exited unexpectedly: " + process.exitValue());
            CacheKey key = new CacheKey("crash", "sample", TransformationFingerprint.ofCanonicalDescriptor("campaign-v1"));
            try (TrainingCache cache = TrainingCache.open(directory)) {
                byte[] value = cache.get(key);
                if (value != null && value.length != 512 * 1024)
                    throw new IllegalStateException("recovery returned an invalid payload");
            }
        }
        System.out.println("completed forced crash trials: " + trials);
    }

    private static void runWorker(Path directory) {
        CacheKey key = new CacheKey("crash", "sample", TransformationFingerprint.ofCanonicalDescriptor("campaign-v1"));
        byte[] value = new byte[512 * 1024];
        try (TrainingCache cache = TrainingCache.open(directory)) {
            cache.put(key, value);
            Runtime.getRuntime().halt(CRASH_EXIT_CODE);
        }
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java" + (isWindows() ? ".exe" : "")).toString();
    }

    private static boolean isWindows() { return System.getProperty("os.name").toLowerCase().contains("win"); }

    private static void delete(Path path) throws Exception {
        if (!Files.exists(path)) return;
        try (var files = Files.walk(path)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(item -> {
                try { Files.deleteIfExists(item); } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        }
    }
}
