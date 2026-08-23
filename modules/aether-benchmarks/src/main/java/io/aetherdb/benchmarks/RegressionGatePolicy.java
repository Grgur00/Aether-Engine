package io.aetherdb.benchmarks;

/** Chapter 30 regression gate thresholds. */
public record RegressionGatePolicy(
        double throughputRegressionFraction,
        double latencyRegressionFraction,
        boolean failOnAcknowledgedWriteLoss) {
    /** Default end-to-end gate: p95/p99 +20%, throughput -15%, no acknowledged loss. */
    public static final RegressionGatePolicy END_TO_END =
            new RegressionGatePolicy(0.15d, 0.20d, true);

    /** Default microbenchmark median gate: p50 +15%. */
    public static final RegressionGatePolicy MICROBENCHMARK =
            new RegressionGatePolicy(1.0d, 0.15d, false);

    /** Validates fractional thresholds. */
    public RegressionGatePolicy {
        if (!Double.isFinite(throughputRegressionFraction)
                || throughputRegressionFraction < 0.0d
                || throughputRegressionFraction > 1.0d
                || !Double.isFinite(latencyRegressionFraction)
                || latencyRegressionFraction < 0.0d
                || latencyRegressionFraction > 10.0d) {
            throw new IllegalArgumentException("invalid regression gate policy");
        }
    }
}
