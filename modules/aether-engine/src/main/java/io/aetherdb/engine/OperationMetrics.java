package io.aetherdb.engine;

/**
 * Immutable measurements for one database operation.
 *
 * <p>Counts, errors, averages, and throughput cover the complete interval since metrics creation or
 * reset. Percentiles are calculated from the most recent bounded sample, which prevents telemetry
 * memory usage from growing with uptime.
 *
 * @param count completed invocations
 * @param errors invocations that completed by throwing
 * @param operationsPerSecond average completion throughput over the interval
 * @param averageLatencyNanos mean latency over all completed invocations
 * @param minimumLatencyNanos minimum observed latency
 * @param p50LatencyNanos rolling median latency
 * @param p95LatencyNanos rolling 95th-percentile latency
 * @param p99LatencyNanos rolling 99th-percentile latency
 * @param maximumLatencyNanos maximum observed latency
 */
public record OperationMetrics(
        long count,
        long errors,
        double operationsPerSecond,
        double averageLatencyNanos,
        long minimumLatencyNanos,
        long p50LatencyNanos,
        long p95LatencyNanos,
        long p99LatencyNanos,
        long maximumLatencyNanos) {

    /**
     * Calculates the failure ratio for this operation.
     *
     * @return fraction of completed invocations that threw an exception
     */
    public double errorRate() {
        return count == 0 ? 0.0 : (double) errors / count;
    }

    /**
     * Converts the rolling p99 latency to milliseconds.
     *
     * @return rolling p99 latency in milliseconds
     */
    public double p99LatencyMillis() {
        return p99LatencyNanos / 1_000_000.0;
    }
}
