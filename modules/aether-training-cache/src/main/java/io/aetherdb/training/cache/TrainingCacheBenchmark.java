package io.aetherdb.training.cache;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Comparator;

/** Small reproducible cold-population versus warm-reuse benchmark for local validation. */
public final class TrainingCacheBenchmark {
    private TrainingCacheBenchmark() {}

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) throw new IllegalArgumentException("usage: <directory> <samples> <payloadBytes> <output>");
        Path directory = Path.of(arguments[0]).toAbsolutePath().normalize();
        int samples = Integer.parseInt(arguments[1]);
        String payloadArgument = arguments[2];
        Path output = Path.of(arguments[3]).toAbsolutePath().normalize();
        if (samples <= 0) throw new IllegalArgumentException("samples must be positive");
        int[] payloadSizes = payloadArgument.equalsIgnoreCase("matrix")
                ? new int[] {256, 1024, 4096, 16 * 1024, 64 * 1024, 256 * 1024, 1024 * 1024, 4 * 1024 * 1024}
                : new int[] {Integer.parseInt(payloadArgument)};
        for (int payloadBytes : payloadSizes)
            if (payloadBytes < 0) throw new IllegalArgumentException("payloadBytes must be non-negative");
        StringBuilder reports = new StringBuilder("[\n");
        for (int sizeIndex = 0; sizeIndex < payloadSizes.length; sizeIndex++) {
            int payloadBytes = payloadSizes[sizeIndex];
            Path runDirectory = payloadSizes.length == 1 ? directory : directory.resolve("payload-" + payloadBytes);
            String report = runBenchmark(runDirectory, samples, payloadBytes);
            reports.append(report.indent(2).stripTrailing());
            if (sizeIndex + 1 < payloadSizes.length) reports.append(',');
            reports.append('\n');
            System.out.println("Payload " + formatBytes(payloadBytes) + ":");
            System.out.println(report);
        }
        reports.append("]\n");
        Files.createDirectories(output.getParent());
        Files.writeString(output, payloadSizes.length == 1 ? reports.toString().replaceFirst("^\\[\\n  ", "").replaceFirst("\\n\\]\\n$", "\n") : reports.toString(), StandardCharsets.UTF_8);
        System.out.println("Report: " + output);
    }

    private static String runBenchmark(Path directory, int samples, int payloadBytes) throws Exception {
        delete(directory);
        CacheKey[] keys = new CacheKey[samples];
        byte[][] payloads = new byte[samples][];
        TransformationFingerprint transform = TransformationFingerprint.ofCanonicalDescriptor("training-cache-benchmark-v1");
        for (int index = 0; index < samples; index++) {
            keys[index] = new CacheKey("benchmark", "sample-" + index, transform);
            payloads[index] = payload(index, payloadBytes);
        }
        Files.createDirectories(directory.getParent());
        long coldStarted = System.nanoTime();
        TrainingCacheMetrics coldMetrics;
        try (TrainingCache cache = TrainingCache.open(directory)) {
            for (int index = 0; index < samples; index++) {
                byte[] payload = payloads[index];
                cache.getOrCompute(keys[index], () -> payload);
            }
            coldMetrics = cache.metrics();
        }
        long coldNanos = System.nanoTime() - coldStarted;
        long warmStarted = System.nanoTime();
        TrainingCacheMetrics warmMetrics;
        try (TrainingCache cache = TrainingCache.open(directory)) {
            for (CacheKey key : keys) if (cache.get(key) != null) {}
            warmMetrics = cache.metrics();
        }
        long warmNanos = System.nanoTime() - warmStarted;
        long mappedStarted = System.nanoTime();
        long mappedBytes = 0;
        try (TrainingCache cache = TrainingCache.open(directory)) {
            for (CacheKey key : keys) {
                var mapped = cache.map(key);
                if (mapped == null) throw new IllegalStateException("mapped cache read missed");
                mappedBytes += mapped.remaining();
            }
        }
        long mappedNanos = System.nanoTime() - mappedStarted;
        String report = String.format(Locale.ROOT,
                "{\n  \"samples\": %d,\n  \"payloadBytes\": %d,\n  \"coldNanos\": %d,\n  \"warmNanos\": %d,\n  \"mappedNanos\": %d,\n  \"warmHits\": %d,\n  \"mappedBytes\": %d,\n  \"coldSamplesPerSecond\": %.3f,\n  \"warmSamplesPerSecond\": %.3f,\n  \"mappedSamplesPerSecond\": %.3f\n}\n",
                samples, payloadBytes, coldNanos, warmNanos, mappedNanos, warmMetrics.hits(), mappedBytes,
                samples / seconds(coldNanos), samples / seconds(warmNanos), samples / seconds(mappedNanos));
            report = report.substring(0, report.length() - 2) + String.format(Locale.ROOT,
                ",\n  \"cold\": %s,\n  \"warm\": %s\n}\n",
                metricsJson(coldMetrics), metricsJson(warmMetrics));
        return report;
    }

    private static byte[] payload(int seed, int length) {
        byte[] payload = new byte[length];
        for (int index = 0; index < payload.length; index++) payload[index] = (byte) (seed + index);
        return payload;
    }

    private static double seconds(long nanos) { return Math.max(1L, nanos) / 1_000_000_000.0; }

    private static String formatBytes(int bytes) {
        if (bytes >= 1024 * 1024 && bytes % (1024 * 1024) == 0) return (bytes / (1024 * 1024)) + " MB";
        if (bytes >= 1024 && bytes % 1024 == 0) return (bytes / 1024) + " KB";
        return bytes + " B";
    }

    private static String metricsJson(TrainingCacheMetrics metrics) {
        return String.format(Locale.ROOT,
                "{\"hits\": %d, \"misses\": %d, \"hitRatio\": %.6f, "
                        + "\"bytesServed\": %d, \"bytesWritten\": %d, \"evictions\": %d, "
                        + "\"corruptEntries\": %d, \"diskBytes\": %d, "
                        + "\"getP50Nanos\": %d, \"getP95Nanos\": %d, \"getP99Nanos\": %d, "
                        + "\"putP50Nanos\": %d, \"putP95Nanos\": %d, \"putP99Nanos\": %d}",
                metrics.hits(), metrics.misses(), ratio(metrics.hits(), metrics.misses()), metrics.bytesServed(),
                metrics.bytesWritten(), metrics.evictions(), metrics.corruptEntries(), metrics.diskBytes(),
                metrics.getP50Nanos(), metrics.getP95Nanos(), metrics.getP99Nanos(), metrics.putP50Nanos(),
                metrics.putP95Nanos(), metrics.putP99Nanos());
    }

    private static double ratio(long hits, long misses) {
        return hits + misses == 0 ? 0.0 : (double) hits / (hits + misses);
    }

    private static void delete(Path directory) throws Exception {
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try { Files.deleteIfExists(path); }
                catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
        }
    }
}
