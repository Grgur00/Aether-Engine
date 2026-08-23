package io.aetherdb.benchmarks;

import java.util.List;
import java.util.Map;

/** Chapter 30 canonical benchmark result schema v1. */
public record BenchmarkResultV1(
        String benchmarkId,
        String gitCommit,
        boolean dirty,
        Map<String, String> environment,
        Map<String, String> aetherConfig,
        Map<String, String> workload,
        BenchmarkCounters counters,
        double throughputOpsPerSecond,
        BenchmarkLatencyHistogram latencyHistogram,
        Map<String, String> storage,
        List<BenchmarkArtifact> artifacts) {
    /** Current canonical schema version. */
    public static final int SCHEMA_VERSION = 1;

    /** Validates required identity fields, maps, counters, and artifact bounds. */
    public BenchmarkResultV1 {
        benchmarkId = requireText(benchmarkId, "benchmarkId", 128);
        gitCommit = requireText(gitCommit, "gitCommit", 128);
        environment = copyMap(environment, "environment");
        aetherConfig = copyMap(aetherConfig, "aetherConfig");
        workload = copyMap(workload, "workload");
        if (counters == null || latencyHistogram == null) {
            throw new IllegalArgumentException("benchmark counters and latency histogram are required");
        }
        if (!Double.isFinite(throughputOpsPerSecond) || throughputOpsPerSecond < 0.0d) {
            throw new IllegalArgumentException("invalid throughput");
        }
        storage = copyMap(storage, "storage");
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        if (artifacts.size() > 64) throw new IllegalArgumentException("too many artifacts");
    }

    private static String requireText(String value, String field, int maximumLength) {
        if (value == null
                || value.isBlank()
                || value.length() > maximumLength
                || value.chars().anyMatch(character -> character == 0 || Character.isISOControl(character))) {
            throw new IllegalArgumentException("invalid " + field);
        }
        return value.strip();
    }

    private static Map<String, String> copyMap(Map<String, String> values, String field) {
        if (values == null || values.size() > 256) throw new IllegalArgumentException("invalid " + field);
        for (Map.Entry<String, String> entry : values.entrySet()) {
            requireText(entry.getKey(), field + " key", 128);
            if (entry.getValue() == null
                    || entry.getValue().length() > 4096
                    || entry.getValue().chars().anyMatch(character -> character == 0)) {
                throw new IllegalArgumentException("invalid " + field + " value");
            }
        }
        return Map.copyOf(values);
    }
}
