package io.aetherdb.benchmarks;

import java.util.Comparator;
import java.util.Locale;
import java.util.Map;

/** Deterministic JSON writer for Chapter 30 benchmark result schema v1. */
public final class BenchmarkResultJsonV1 {
    private BenchmarkResultJsonV1() {}

    /** Serializes a benchmark result using stable field order and sorted map keys. */
    public static String encode(BenchmarkResultV1 result) {
        StringBuilder json = new StringBuilder(2048);
        json.append("{\n");
        field(json, 1, "schemaVersion", Integer.toString(BenchmarkResultV1.SCHEMA_VERSION), false);
        field(json, 1, "benchmarkId", quote(result.benchmarkId()), false);
        field(json, 1, "gitCommit", quote(result.gitCommit()), false);
        field(json, 1, "dirty", Boolean.toString(result.dirty()), false);
        map(json, "environment", result.environment(), false);
        map(json, "aetherConfig", result.aetherConfig(), false);
        map(json, "workload", result.workload(), false);
        counters(json, result.counters(), false);
        field(
                json,
                1,
                "throughputOpsPerSecond",
                String.format(Locale.ROOT, "%.3f", result.throughputOpsPerSecond()),
                false);
        latency(json, result.latencyHistogram(), false);
        map(json, "storage", result.storage(), false);
        artifacts(json, result);
        json.append('}');
        return json.toString();
    }

    private static void map(
            StringBuilder json, String name, Map<String, String> values, boolean last) {
        json.append("  ").append(quote(name)).append(": {\n");
        int index = 0;
        var entries =
                values.entrySet().stream()
                        .sorted(Comparator.comparing(Map.Entry::getKey))
                        .toList();
        for (Map.Entry<String, String> entry : entries) {
            field(json, 2, entry.getKey(), quote(entry.getValue()), ++index == entries.size());
        }
        json.append("  }").append(last ? "\n" : ",\n");
    }

    private static void counters(StringBuilder json, BenchmarkCounters counters, boolean last) {
        json.append("  \"counters\": {\n");
        field(json, 2, "submitted", Long.toString(counters.submitted()), false);
        field(json, 2, "acknowledged", Long.toString(counters.acknowledged()), false);
        field(json, 2, "rejectedBeforeAck", Long.toString(counters.rejectedBeforeAck()), false);
        field(json, 2, "uncertain", Long.toString(counters.uncertain()), false);
        field(json, 2, "failed", Long.toString(counters.failed()), true);
        json.append("  }").append(last ? "\n" : ",\n");
    }

    private static void latency(
            StringBuilder json, BenchmarkLatencyHistogram latency, boolean last) {
        json.append("  \"latencyHistogram\": {\n");
        field(json, 2, "count", Long.toString(latency.count()), false);
        field(json, 2, "p50Nanos", Long.toString(latency.p50Nanos()), false);
        field(json, 2, "p95Nanos", Long.toString(latency.p95Nanos()), false);
        field(json, 2, "p99Nanos", Long.toString(latency.p99Nanos()), false);
        field(json, 2, "maxNanos", Long.toString(latency.maxNanos()), true);
        json.append("  }").append(last ? "\n" : ",\n");
    }

    private static void artifacts(StringBuilder json, BenchmarkResultV1 result) {
        json.append("  \"artifacts\": [\n");
        for (int index = 0; index < result.artifacts().size(); index++) {
            BenchmarkArtifact artifact = result.artifacts().get(index);
            json.append("    {\"kind\": ")
                    .append(quote(artifact.kind()))
                    .append(", \"uri\": ")
                    .append(quote(artifact.uri().toString()))
                    .append('}')
                    .append(index + 1 == result.artifacts().size() ? "\n" : ",\n");
        }
        json.append("  ]\n");
    }

    private static void field(
            StringBuilder json, int indent, String name, String value, boolean last) {
        json.append("  ".repeat(indent))
                .append(quote(name))
                .append(": ")
                .append(value)
                .append(last ? "\n" : ",\n");
    }

    private static String quote(String value) {
        String escaped =
                value.replace("\\", "\\\\")
                        .replace("\"", "\\\"")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r");
        return '"' + escaped + '"';
    }
}
