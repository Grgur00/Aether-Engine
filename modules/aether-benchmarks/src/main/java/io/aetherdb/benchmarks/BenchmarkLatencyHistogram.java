package io.aetherdb.benchmarks;

/** Canonical latency summary used by benchmark reports and regression gates. */
public record BenchmarkLatencyHistogram(
        long count, long p50Nanos, long p95Nanos, long p99Nanos, long maxNanos) {
    /** Validates monotonic non-negative latency percentiles. */
    public BenchmarkLatencyHistogram {
        if (count < 0
                || p50Nanos < 0
                || p95Nanos < p50Nanos
                || p99Nanos < p95Nanos
                || maxNanos < p99Nanos) {
            throw new IllegalArgumentException("invalid latency histogram");
        }
    }
}
