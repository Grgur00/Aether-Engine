package io.aetherdb.training.cache;

import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.ReadDiagnostics;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Raw database get-loop diagnostic. Each invocation owns an isolated JVM/cache instance. */
public final class HitPathBenchmark {
    private HitPathBenchmark() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6)
            throw new IllegalArgumentException("usage: dbPath workloadPath outputPath requestSize epochs [warmupEpochs=1]");
        Path directory = Path.of(args[0]);
        Path output = Path.of(args[2]).toAbsolutePath();
        int requestSize = Integer.parseInt(args[3]);
        int epochs = Integer.parseInt(args[4]);
        int warmups = args.length == 6 ? Integer.parseInt(args[5]) : 1;
        if (requestSize < 1 || requestSize > 4096 || epochs < 0 || warmups < 0)
            throw new IllegalArgumentException("requestSize must be 1..4096; epochs and warmupEpochs must be nonnegative");
        var report = new LinkedHashMap<String, Object>();
        var batches = new ArrayList<Map<String, Object>>();
        report.put("schema", "aether-java-hit-path-v1");
        report.put("role", epochs == 0 ? "preparation" : "steady-state-hit-path-diagnostic");
        report.put("scope", "isolated JVM; raw AetherDatabase.get loop plus LookupResult.value defensive copy; "
                + "no RPC or artifact decoding; database values include cache encoding and only metadata for segments; "
                + "artifactBytes is declared metadata length, not segment bytes read; cross-layer differences are not causal estimates");
        report.put("lookupApi", "AetherDatabase.get loop");
        report.put("requestSize", requestSize);
        report.put("epochs", epochs);
        report.put("warmupEpochs", epochs == 0 ? 0 : warmups);
        report.put("readDiagnosticsEnabled", true);
        report.put("batches", batches);
        report.put("before", Map.of());
        report.put("after", Map.of());
        Exception failure = null;
        try {
            if (!Files.isRegularFile(directory.resolve("DB-IDENTITY")))
                throw new IllegalArgumentException("dbPath must be an existing TrainingCache root (no index subdirectory)");
            List<byte[]> keys = readWorkload(Path.of(args[1]));
            report.put("keyCount", keys.size());
            var unique = new HashSet<String>();
            for (byte[] key : keys) unique.add(HexFormat.of().formatHex(key));
            report.put("uniqueKeyCount", unique.size());
            TrainingCache cache = TrainingCache.open(directory, 1L << 40, TrainingCacheDurability.DURABLE);
            try (cache) {
                cache.awaitCompactionIdle();
                report.put("cacheEntries", cache.cacheEntries());
                AetherDatabase database = cache.benchmarkDatabase();
                if (cache.cacheEntries() != unique.size())
                    throw new IllegalStateException("cache cardinality differs from unique workload key count");
                // Presence and warmup occur before the timed activity snapshots.
                var segmentKeys = new HashSet<String>();
                for (byte[] key : keys) {
                    var found = database.get(key);
                    if (!found.isFound()) throw new IllegalStateException("workload contains a missing key");
                    byte[] encoded = found.value();
                    if (encoded.length >= 4 && ByteBuffer.wrap(encoded).getInt() == 0xAE7CA002)
                        segmentKeys.add(HexFormat.of().formatHex(key));
                }
                report.put("segmentBackedEntries", segmentKeys.size());
                if (epochs > 0) {
                    for (int epoch = 0; epoch < warmups; epoch++)
                        for (int offset = 0; offset < keys.size(); offset += requestSize)
                            lookupBatch(database, keys, offset, requestSize);
                    cache.awaitCompactionIdle();
                    Map<String, Object> before = cache.compactionDiagnostics();
                    report.put("before", before);
                    requireIdle(before);
                    try {
                        for (int epoch = 0; epoch < epochs; epoch++) {
                            int batchIndex = 0;
                            for (int offset = 0; offset < keys.size(); offset += requestSize) {
                                Map<String, Object> batchBefore = cache.compactionDiagnostics();
                                ReadDiagnostics.Collector collector = new ReadDiagnostics.Collector(epoch + ":" + batchIndex);
                                ReadDiagnostics.Collector previous = ReadDiagnostics.attach(collector);
                                byte[][] values;
                                long duration;
                                try {
                                    long started = System.nanoTime();
                                    values = lookupBatch(database, keys, offset, requestSize);
                                    duration = System.nanoTime() - started;
                                } finally { ReadDiagnostics.attach(previous); }
                                Map<String, Object> batchAfter = cache.compactionDiagnostics();
                                var batch = new LinkedHashMap<String, Object>();
                                batch.put("epoch", epoch);
                                batch.put("batchIndex", batchIndex++);
                                batch.put("durationNs", duration);
                                batch.put("samples", values.length);
                                long bytes = 0, artifactBytes = 0, segmentedValues = 0, misses = 0;
                                for (byte[] value : values) {
                                    if (value == null) { misses++; continue; }
                                    bytes += value.length;
                                    if (value.length < 12) throw new IllegalStateException("truncated cache metadata");
                                    ByteBuffer metadata = ByteBuffer.wrap(value);
                                    int magic = metadata.getInt(), version = metadata.getInt(), length = metadata.getInt();
                                    if ((magic != 0xAE7CA001 && magic != 0xAE7CA002) || version != 1 || length < 0)
                                        throw new IllegalStateException("invalid cache metadata");
                                    artifactBytes += length;
                                    if (magic == 0xAE7CA002) segmentedValues++;
                                }
                                batch.put("misses", misses);
                                batch.put("bytesReturned", bytes);
                                batch.put("databaseValueBytes", bytes);
                                batch.put("artifactBytes", artifactBytes);
                                batch.put("segmentedValues", segmentedValues);
                                batch.put("beforeActivity", batchBefore);
                                batch.put("afterActivity", batchAfter);
                                batch.put("readDiagnostics", collector.snapshot());
                                batches.add(batch);
                                if (misses != 0) throw new IllegalStateException("timed cache miss");
                                requireUnchanged(before, batchAfter);
                            }
                        }
                    } finally { report.put("after", cache.compactionDiagnostics()); }
                    requireUnchanged(before, cache.compactionDiagnostics());
                } else {
                    report.put("before", cache.compactionDiagnostics());
                }
            }
            // Preparation deliberately captures the normal close/seal outside all timing.
            if (epochs == 0) report.put("after", cache.compactionDiagnostics());
            report.put("status", "PASSED");
        } catch (Exception problem) {
            failure = problem;
            report.put("status", "FAILED");
            report.put("failure", problem.toString());
        }
        Map<String, Object> summary = summarize(batches);
        report.put("summary", summary);
        report.put("databaseValueBytes", summary.get("databaseValueBytes"));
        report.put("artifactBytes", summary.get("artifactBytes"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, DiagnosticJson.encode(report), StandardCharsets.UTF_8);
        if (failure != null) throw failure;
    }

    private static byte[][] lookupBatch(AetherDatabase database, List<byte[]> keys, int offset, int requestSize) {
        byte[][] values = new byte[Math.min(requestSize, keys.size() - offset)][];
        for (int index = 0; index < values.length; index++) {
            var result = database.get(keys.get(offset + index));
            values[index] = result.isFound() ? result.value() : null;
        }
        return values;
    }

    private static List<byte[]> readWorkload(Path path) throws IOException {
        try (var input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
            int count = input.readInt();
            if (count <= 0 || count > (Files.size(path) - 4) / 40)
                throw new IllegalArgumentException("invalid workload key count");
            var keys = new ArrayList<byte[]>();
            for (int index = 0; index < count; index++) {
                String namespace = readString(input), sample = readString(input);
                byte[] transform = new byte[32];
                input.readFully(transform);
                keys.add(new CacheKey(namespace, sample, TransformationFingerprint.fromBytes(transform)).storageKey());
            }
            if (input.read() != -1) throw new IllegalArgumentException("trailing workload bytes");
            return keys;
        }
    }

    private static String readString(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 64 * 1024 * 1024) throw new IllegalArgumentException("invalid UTF-8 length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static void requireIdle(Map<String, Object> activity) {
        if (!"IDLE".equals(activity.get("state")) || ((Number) activity.get("debtBytes")).longValue() != 0
                || !"".equals(activity.get("lastFailure")))
            throw new IllegalStateException("background compaction is not idle with zero debt and no failure");
    }

    private static void requireUnchanged(Map<String, Object> before, Map<String, Object> after) {
        requireIdle(after);
        for (String counter : List.of("flushesStarted", "flushesCompleted", "backgroundCompactionsStarted", "completed", "failed"))
            if (!before.get(counter).equals(after.get(counter)))
                throw new IllegalStateException("storage activity during measurement: " + counter);
    }

    private static Map<String, Object> summarize(List<Map<String, Object>> batches) {
        long samples = 0, duration = 0, bytes = 0, artifactBytes = 0, segmented = 0, misses = 0;
        long[] times = new long[batches.size()];
        for (int index = 0; index < batches.size(); index++) {
            Map<String, Object> batch = batches.get(index);
            times[index] = ((Number) batch.get("durationNs")).longValue();
            duration += times[index];
            samples += ((Number) batch.get("samples")).longValue();
            bytes += ((Number) batch.get("databaseValueBytes")).longValue();
            artifactBytes += ((Number) batch.get("artifactBytes")).longValue();
            segmented += ((Number) batch.get("segmentedValues")).longValue();
            misses += ((Number) batch.get("misses")).longValue();
        }
        java.util.Arrays.sort(times);
        var result = new LinkedHashMap<String, Object>();
        result.put("samples", samples);
        result.put("misses", misses);
        result.put("durationNs", duration);
        result.put("bytesReturned", bytes);
        result.put("databaseValueBytes", bytes);
        result.put("artifactBytes", artifactBytes);
        result.put("segmentedValues", segmented);
        result.put("meanNs", times.length == 0 ? 0.0 : (double) duration / times.length);
        result.put("medianNs", times.length == 0 ? 0.0
                : times.length % 2 == 1 ? times[times.length / 2]
                : times[times.length / 2 - 1] / 2.0 + times[times.length / 2] / 2.0);
        result.put("p95Ns", percentile(times, 0.95));
        result.put("p99Ns", percentile(times, 0.99));
        result.put("samplesPerSecond", duration == 0 ? 0.0 : samples * 1_000_000_000.0 / duration);
        return result;
    }

    private static long percentile(long[] sorted, double fraction) {
        return sorted.length == 0 ? 0 : sorted[(int) Math.ceil(sorted.length * fraction) - 1];
    }
}
