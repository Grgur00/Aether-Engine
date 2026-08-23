package io.aetherdb.benchmarks;

/** Persisted baseline reference for one benchmark profile. */
public record BenchmarkBaselineEntry(
        String profileId,
        String resultUri,
        String gitCommit,
        double throughputOpsPerSecond,
        long p50Nanos,
        long p95Nanos,
        long p99Nanos,
        long acknowledged) {
    /** Validates stable baseline metadata. */
    public BenchmarkBaselineEntry {
        if (profileId == null
                || profileId.isBlank()
                || resultUri == null
                || resultUri.isBlank()
                || resultUri.length() > 2048
                || gitCommit == null
                || gitCommit.isBlank()
                || !Double.isFinite(throughputOpsPerSecond)
                || throughputOpsPerSecond < 0
                || p50Nanos < 0
                || p95Nanos < p50Nanos
                || p99Nanos < p95Nanos
                || acknowledged < 0) {
            throw new IllegalArgumentException("invalid benchmark baseline entry");
        }
        profileId = profileId.strip();
        resultUri = resultUri.strip();
        gitCommit = gitCommit.strip();
    }

    /** Creates a baseline entry from a canonical benchmark result. */
    public static BenchmarkBaselineEntry fromResult(BenchmarkResultV1 result, String resultUri) {
        return new BenchmarkBaselineEntry(
                result.benchmarkId(),
                resultUri,
                result.gitCommit(),
                result.throughputOpsPerSecond(),
                result.latencyHistogram().p50Nanos(),
                result.latencyHistogram().p95Nanos(),
                result.latencyHistogram().p99Nanos(),
                result.counters().acknowledged());
    }

    /** Converts the stored baseline metrics into a comparable result skeleton. */
    public BenchmarkResultV1 toResultLike(BenchmarkResultV1 candidate) {
        return new BenchmarkResultV1(
                profileId,
                gitCommit,
                false,
                candidate.environment(),
                candidate.aetherConfig(),
                candidate.workload(),
                new BenchmarkCounters(acknowledged, acknowledged, 0, 0, 0),
                throughputOpsPerSecond,
                new BenchmarkLatencyHistogram(acknowledged, p50Nanos, p95Nanos, p99Nanos, p99Nanos),
                candidate.storage(),
                java.util.List.of());
    }
}
