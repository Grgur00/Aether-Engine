package io.aetherdb.benchmarks;

import io.aetherdb.api.DurabilityMode;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.engine.Aether;
import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;
import org.HdrHistogram.Histogram;

/** Reproducible persistent-engine benchmark that emits an auditable JSON report. */
public final class CvBenchmark {
    private static final int CRASH_EXIT_CODE = 91;
    private static final long MAX_LATENCY_NANOS = TimeUnit.MINUTES.toNanos(1);

    private CvBenchmark() {}

    /**
     * Runs the benchmark coordinator or its internal crash worker.
     *
     * @param arguments command-line arguments
     * @throws Exception when setup, a worker process, or recovery validation fails
     */
    public static void main(String[] arguments) throws Exception {
        if (arguments.length > 0 && arguments[0].equals("crash-worker")) {
            runCrashWorker(Path.of(arguments[1]), Long.parseLong(arguments[2]),
                    Integer.parseInt(arguments[3]), Integer.parseInt(arguments[4]));
            return;
        }
        run(Config.parse(arguments));
    }

    private static void run(Config config) throws Exception {
        requireFreshDirectory(config.directory());
        System.out.printf(Locale.ROOT,
                "Aether benchmark: %,d records, %,d measured reads, %d forced crashes, %s, batch=%d%n",
                config.records(), config.reads(), config.crashPoints(), config.durability(), config.batchSize());

        Histogram writeLatency = histogram();
        long writeStarted = System.nanoTime();
        try (var database = Aether.open(config.directory())) {
            for (long first = 0; first < config.records(); first += config.batchSize()) {
                int size = (int) Math.min(config.batchSize(), config.records() - first);
                try (WriteBatch batch = new WriteBatch()) {
                    for (int offset = 0; offset < size; offset++) {
                        long record = first + offset;
                        batch.put(key(record), value(record, config.valueBytes()));
                    }
                    long started = System.nanoTime();
                    database.write(batch, new WriteOptions(config.durability(), Duration.ofSeconds(30), false));
                    record(writeLatency, System.nanoTime() - started);
                }
                if (first > 0 && first % Math.max(config.batchSize(), config.records() / 10) == 0) {
                    System.out.printf(Locale.ROOT, "  loaded %,d / %,d records%n", first, config.records());
                }
            }
        }
        long writeElapsedNanos = System.nanoTime() - writeStarted;
        long databaseBytesAfterLoad = directoryBytes(config.directory());

        Histogram readLatency = histogram();
        long hits = 0;
        long readStarted;
        long readElapsedNanos;
        SplittableRandom random = new SplittableRandom(0xC0FF_EE42L);
        int warmup = Math.min(config.reads(), Math.max(1_000, config.reads() / 10));
        if (config.cacheMode() == CacheMode.COLD_OPEN) {
            try (var warmDatabase = Aether.open(config.directory())) {
                for (int index = 0; index < warmup; index++) {
                    long record = random.nextLong(config.records());
                    requireValue(warmDatabase.get(key(record)), value(record, config.valueBytes()));
                }
            }
            purgeMacFileSystemCache();
        }
        long readOpenStarted = System.nanoTime();
        var readDatabase = Aether.open(config.directory());
        long readDatabaseOpenNanos = System.nanoTime() - readOpenStarted;
        try (readDatabase) {
            if (config.cacheMode() == CacheMode.REOPENED_WARMUP) {
            for (int index = 0; index < warmup; index++) {
                long record = random.nextLong(config.records());
                    requireValue(readDatabase.get(key(record)), value(record, config.valueBytes()));
                }
            }
            readStarted = System.nanoTime();
            for (int index = 0; index < config.reads(); index++) {
                long record = random.nextLong(config.records());
                long started = System.nanoTime();
                LookupResult result = readDatabase.get(key(record));
                record(readLatency, System.nanoTime() - started);
                requireValue(result, value(record, config.valueBytes()));
                hits++;
            }
            readElapsedNanos = System.nanoTime() - readStarted;
        }

        List<RecoveryTrial> recoveries = new ArrayList<>();
        for (int point = 1; point <= config.crashPoints(); point++) {
            long markerKey = config.records() + point - 1L;
            Process worker = crashWorker(config.directory(), markerKey, point, config.valueBytes());
            int exitCode = worker.waitFor();
            if (exitCode != CRASH_EXIT_CODE) {
                throw new IllegalStateException("crash worker " + point + " exited with " + exitCode);
            }
            long openStarted = System.nanoTime();
            var recovered = Aether.open(config.directory());
            long openNanos = System.nanoTime() - openStarted;
            long validationStarted = System.nanoTime();
            try (recovered) {
                for (int expected = 1; expected <= point; expected++) {
                    long expectedKey = config.records() + expected - 1L;
                    requireValue(recovered.get(key(expectedKey)), value(expectedKey, config.valueBytes()));
                }
                long baseRecord = point % config.records();
                requireValue(recovered.get(key(baseRecord)), value(baseRecord, config.valueBytes()));
                recovered.put(key(-point), value(-point, config.valueBytes()));
                requireValue(recovered.get(key(-point)), value(-point, config.valueBytes()));
            }
            long validationNanos = System.nanoTime() - validationStarted;
            recoveries.add(new RecoveryTrial(point, openNanos, validationNanos, point, true));
            System.out.printf(Locale.ROOT, "  recovered and validated forced crash %d / %d%n",
                    point, config.crashPoints());
        }

        long finalDatabaseBytes = directoryBytes(config.directory());
        String report = jsonReport(config, writeLatency, writeElapsedNanos, databaseBytesAfterLoad,
                readLatency, readElapsedNanos, readDatabaseOpenNanos, hits, recoveries, finalDatabaseBytes);
        Path output = config.output().toAbsolutePath().normalize();
        Path parent = output.getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(output, report + System.lineSeparator(), StandardCharsets.UTF_8);
        System.out.println();
        System.out.println(report);
        System.out.println();
        System.out.println("Full JSON report: " + output);
    }

    private static String jsonReport(Config config, Histogram writes, long writeElapsedNanos,
                                     long databaseBytesAfterLoad, Histogram reads, long readElapsedNanos,
                                     long readDatabaseOpenNanos, long hits, List<RecoveryTrial> recoveries,
                                     long finalDatabaseBytes) {
        double writesPerSecond = config.records() / seconds(writeElapsedNanos);
        double readsPerSecond = config.reads() / seconds(readElapsedNanos);
        long logicalBytes = Math.multiplyExact(config.records(), 16L + config.valueBytes());
        long medianRecoveryNanos = median(recoveries.stream().mapToLong(RecoveryTrial::openNanos).toArray());
        StringBuilder json = new StringBuilder(4_096);
        json.append("{\n");
        field(json, 1, "schemaVersion", "1", false);
        field(json, 1, "collectedAt", quote(Instant.now().toString()), false);
        field(json, 1, "aetherCommit", quote(command("git", "rev-parse", "HEAD")), false);
        field(json, 1, "workingTreeDirty", Boolean.toString(!command("git", "status", "--porcelain").isBlank()), false);
        json.append("  \"environment\": {\n");
        field(json, 2, "os", quote(System.getProperty("os.name") + " " + System.getProperty("os.version")), false);
        field(json, 2, "architecture", quote(System.getProperty("os.arch")), false);
        field(json, 2, "availableProcessors", Integer.toString(Runtime.getRuntime().availableProcessors()), false);
        field(json, 2, "cpu", quote(cpuDescription()), false);
        field(json, 2, "jdk", quote(System.getProperty("java.runtime.version")), false);
        field(json, 2, "vm", quote(System.getProperty("java.vm.name")), false);
        field(json, 2, "jvmArguments", quote(String.join(" ", ManagementFactory.getRuntimeMXBean().getInputArguments())), false);
        field(json, 2, "maximumHeapBytes", Long.toString(Runtime.getRuntime().maxMemory()), true);
        json.append("  },\n");
        json.append("  \"configuration\": {\n");
        field(json, 2, "databaseDirectory", quote(config.directory().toAbsolutePath().normalize().toString()), false);
        field(json, 2, "records", Long.toString(config.records()), false);
        field(json, 2, "keyBytes", "16", false);
        field(json, 2, "valueBytes", Integer.toString(config.valueBytes()), false);
        field(json, 2, "measuredReads", Integer.toString(config.reads()), false);
        field(json, 2, "readThreads", "1", false);
        field(json, 2, "batchSize", Integer.toString(config.batchSize()), false);
        field(json, 2, "durability", quote(config.durability().name()), false);
        field(json, 2, "cacheMode", quote(config.cacheMode().name()), false);
        field(json, 2, "crashModel", quote("child Runtime.halt after acknowledged durable marker write"), false);
        field(json, 2, "crashTrials", Integer.toString(config.crashPoints()), true);
        json.append("  },\n");
        json.append("  \"writeWorkload\": {\n");
        field(json, 2, "totalRecords", Long.toString(config.records()), false);
        field(json, 2, "elapsedNanos", Long.toString(writeElapsedNanos), false);
        field(json, 2, "throughputRecordsPerSecond", decimal(writesPerSecond), false);
        field(json, 2, "latencyScope", quote("atomic batch submission"), false);
        appendLatency(json, writes, 2, false);
        field(json, 2, "logicalKeyValueBytes", Long.toString(logicalBytes), false);
        field(json, 2, "databaseBytesAfterLoad", Long.toString(databaseBytesAfterLoad), true);
        json.append("  },\n");
        json.append("  \"pointReadWorkload\": {\n");
        field(json, 2, "cacheState", quote(config.cacheMode().description()), false);
        field(json, 2, "databaseOpenNanos", Long.toString(readDatabaseOpenNanos), false);
        field(json, 2, "storagePath", quote("checkpoint is fully materialized into heap during open; measured point reads are heap-resident"), false);
        field(json, 2, "operations", Integer.toString(config.reads()), false);
        field(json, 2, "elapsedNanos", Long.toString(readElapsedNanos), false);
        field(json, 2, "throughputOperationsPerSecond", decimal(readsPerSecond), false);
        field(json, 2, "hits", Long.toString(hits), false);
        field(json, 2, "hitRate", decimal((double) hits / config.reads()), false);
        appendLatency(json, reads, 2, true);
        json.append("  },\n");
        json.append("  \"recoveryWorkload\": {\n");
        field(json, 2, "successfulTrials", Long.toString(recoveries.stream().filter(RecoveryTrial::success).count()), false);
        field(json, 2, "acknowledgedWritesLost", "0", false);
        field(json, 2, "medianDatabaseOpenNanos", Long.toString(medianRecoveryNanos), false);
        field(json, 2, "walBytesReplayed", "null", false);
        json.append("    \"trials\": [\n");
        for (int index = 0; index < recoveries.size(); index++) {
            RecoveryTrial trial = recoveries.get(index);
            json.append("      {\"trial\": ").append(trial.trial())
                    .append(", \"databaseOpenNanos\": ").append(trial.openNanos())
                    .append(", \"validationNanos\": ").append(trial.validationNanos())
                    .append(", \"acknowledgedMarkersVerified\": ").append(trial.markersVerified())
                    .append(", \"postRecoveryWriteVerified\": ").append(trial.success()).append('}');
            json.append(index + 1 == recoveries.size() ? "\n" : ",\n");
        }
        json.append("    ],\n");
        field(json, 2, "note", quote("WAL replay byte accounting is not exposed by the current engine"), true);
        json.append("  },\n");
        field(json, 1, "finalDatabaseBytes", Long.toString(finalDatabaseBytes), true);
        json.append('}');
        return json.toString();
    }

    private static void appendLatency(StringBuilder json, Histogram histogram, int indent, boolean last) {
        json.append("  ".repeat(indent)).append("\"latencyNanos\": {\n");
        field(json, indent + 1, "count", Long.toString(histogram.getTotalCount()), false);
        field(json, indent + 1, "minimum", Long.toString(histogram.getMinValue()), false);
        field(json, indent + 1, "mean", decimal(histogram.getMean()), false);
        field(json, indent + 1, "p50", Long.toString(histogram.getValueAtPercentile(50.0)), false);
        field(json, indent + 1, "p95", Long.toString(histogram.getValueAtPercentile(95.0)), false);
        field(json, indent + 1, "p99", Long.toString(histogram.getValueAtPercentile(99.0)), false);
        field(json, indent + 1, "p999", Long.toString(histogram.getValueAtPercentile(99.9)), false);
        field(json, indent + 1, "maximum", Long.toString(histogram.getMaxValue()), true);
        json.append("  ".repeat(indent)).append('}').append(last ? "\n" : ",\n");
    }

    private static void field(StringBuilder json, int indent, String name, String value, boolean last) {
        json.append("  ".repeat(indent)).append(quote(name)).append(": ").append(value)
                .append(last ? "\n" : ",\n");
    }

    private static String quote(String value) {
        String escaped = value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r");
        return '"' + escaped + '"';
    }

    private static String decimal(double value) { return String.format(Locale.ROOT, "%.3f", value); }
    private static double seconds(long nanos) { return Math.max(1, nanos) / 1_000_000_000.0; }
    private static Histogram histogram() { return new Histogram(MAX_LATENCY_NANOS, 3); }
    private static void record(Histogram histogram, long nanos) { histogram.recordValue(Math.min(MAX_LATENCY_NANOS, Math.max(0, nanos))); }

    private static Process crashWorker(Path directory, long markerKey, int point, int valueBytes) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        List<String> command = new ArrayList<>();
        command.add(java);
        command.add("--enable-preview");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(CvBenchmark.class.getName());
        command.add("crash-worker");
        command.add(directory.toString());
        command.add(Long.toString(markerKey));
        command.add(Integer.toString(point));
        command.add(Integer.toString(valueBytes));
        return new ProcessBuilder(command).inheritIO().start();
    }

    private static void runCrashWorker(Path directory, long markerKey, int point, int valueBytes) {
        var database = Aether.open(directory);
        database.put(key(markerKey), value(markerKey, valueBytes));
        System.out.printf(Locale.ROOT, "  forcing abrupt process halt for trial %d%n", point);
        System.out.flush();
        Runtime.getRuntime().halt(CRASH_EXIT_CODE);
    }

    private static byte[] key(long record) {
        return ByteBuffer.allocate(16).putLong(mix64(record)).putLong(mix64(record ^ 0xA37E_2026L)).array();
    }

    private static byte[] value(long record, int bytes) {
        byte[] value = new byte[bytes];
        long state = mix64(record ^ 0xD1B5_4A32_D192_ED03L);
        for (int index = 0; index < bytes; index++) {
            if ((index & 7) == 0) state = mix64(state + index);
            value[index] = (byte) (state >>> ((index & 7) * 8));
        }
        return value;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58_476D_1CE4_E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D0_49BB_1331_11EBL;
        return value ^ (value >>> 31);
    }

    private static void requireValue(LookupResult result, byte[] expected) {
        if (!result.isFound() || !Arrays.equals(result.value(), expected)) {
            throw new IllegalStateException("recovery/read validation failed: value is absent or corrupt");
        }
    }

    private static long directoryBytes(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            return paths.filter(Files::isRegularFile).mapToLong(path -> {
                try { return Files.size(path); }
                catch (Exception failure) { throw new IllegalStateException("cannot size " + path, failure); }
            }).sum();
        }
    }

    private static long median(long[] values) {
        if (values.length == 0) return 0;
        Arrays.sort(values);
        return values[values.length / 2];
    }

    private static String cpuDescription() {
        String mac = command("sysctl", "-n", "machdep.cpu.brand_string");
        if (!mac.isBlank()) return mac;
        String environment = System.getenv("PROCESSOR_IDENTIFIER");
        return environment == null || environment.isBlank() ? System.getProperty("os.arch") : environment;
    }

    private static void purgeMacFileSystemCache() throws Exception {
        if (!System.getProperty("os.name").equals("Mac OS X")) {
            throw new IllegalStateException("COLD_OPEN currently supports macOS only");
        }
        Process purge = new ProcessBuilder(
                "/usr/bin/osascript",
                "-e",
                "do shell script \"/usr/sbin/purge\" with administrator privileges")
                .inheritIO()
                .start();
        if (purge.waitFor() != 0) {
            throw new IllegalStateException("cache purge was denied or failed in the macOS authorization dialog");
        }
    }

    private static String command(String... command) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
            return process.waitFor() == 0 ? output : "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static void requireFreshDirectory(Path directory) throws Exception {
        Files.createDirectories(directory);
        try (var entries = Files.list(directory)) {
            if (entries.findAny().isPresent()) throw new IllegalArgumentException("benchmark directory must be empty: " + directory);
        }
    }

    private record RecoveryTrial(int trial, long openNanos, long validationNanos, int markersVerified, boolean success) {}

    private enum CacheMode {
        REOPENED_WARMUP("database reopened and read warm-up completed; OS page cache uncontrolled"),
        COLD_OPEN("macOS filesystem cache purged before timed open; point reads remain heap-resident after open");

        private final String description;
        CacheMode(String description) { this.description = description; }
        private String description() { return description; }
    }

    private record Config(Path directory, Path output, long records, int reads, int crashPoints,
                          int batchSize, int valueBytes, DurabilityMode durability, CacheMode cacheMode) {
        private static Config parse(String[] arguments) {
            Path directory = null;
            Path output = null;
            long records = 100_000;
            int reads = 100_000;
            int crashPoints = 4;
            int batchSize = 1_000;
            int valueBytes = 256;
            DurabilityMode durability = DurabilityMode.GROUP_SYNC;
            CacheMode cacheMode = CacheMode.REOPENED_WARMUP;
            for (int index = 0; index < arguments.length; index++) {
                String option = arguments[index];
                if (index + 1 >= arguments.length) throw new IllegalArgumentException("missing value for " + option);
                String argument = arguments[++index];
                switch (option) {
                    case "--directory" -> directory = Path.of(argument);
                    case "--output" -> output = Path.of(argument);
                    case "--records" -> records = Long.parseLong(argument);
                    case "--reads" -> reads = Integer.parseInt(argument);
                    case "--crash-points" -> crashPoints = Integer.parseInt(argument);
                    case "--batch-size" -> batchSize = Integer.parseInt(argument);
                    case "--value-bytes" -> valueBytes = Integer.parseInt(argument);
                    case "--durability" -> durability = DurabilityMode.valueOf(argument.toUpperCase(Locale.ROOT).replace('-', '_'));
                    case "--cache-mode" -> cacheMode = CacheMode.valueOf(argument.toUpperCase(Locale.ROOT).replace('-', '_'));
                    default -> throw new IllegalArgumentException("unknown option: " + option);
                }
            }
            if (directory == null) throw new IllegalArgumentException("--directory is required");
            if (output == null) output = directory.toAbsolutePath().normalize().resolveSibling(directory.getFileName() + "-results.json");
            if (records < 1 || reads < 1 || crashPoints < 0 || batchSize < 1
                    || batchSize > WriteBatch.MAX_OPERATIONS || valueBytes < 0) {
                throw new IllegalArgumentException("benchmark counts and sizes are out of range");
            }
            return new Config(directory, output, records, reads, crashPoints, batchSize, valueBytes, durability, cacheMode);
        }
    }
}
