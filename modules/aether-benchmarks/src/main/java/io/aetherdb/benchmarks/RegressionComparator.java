package io.aetherdb.benchmarks;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Comparator for Chapter 30 benchmark regression gates. */
public final class RegressionComparator {
    private RegressionComparator() {}

    /** Compares a candidate result against a baseline using the supplied gate policy. */
    public static RegressionComparison compare(
            BenchmarkResultV1 baseline,
            BenchmarkResultV1 candidate,
            RegressionGatePolicy policy) {
        if (baseline == null || candidate == null || policy == null) {
            throw new IllegalArgumentException("comparison inputs are required");
        }
        List<String> failures = new ArrayList<>();
        if (!baseline.benchmarkId().equals(candidate.benchmarkId())) {
            failures.add("benchmark id mismatch");
        }
        if (!baseline.workload().equals(candidate.workload())) {
            failures.add("workload semantics mismatch");
        }
        if (!baseline.aetherConfig().equals(candidate.aetherConfig())) {
            failures.add("Aether configuration mismatch");
        }
        if (regressedDown(
                baseline.throughputOpsPerSecond(),
                candidate.throughputOpsPerSecond(),
                policy.throughputRegressionFraction())) {
            failures.add("throughput regression exceeds gate");
        }
        if (regressedUp(
                baseline.latencyHistogram().p50Nanos(),
                candidate.latencyHistogram().p50Nanos(),
                policy.latencyRegressionFraction())) {
            failures.add("p50 latency regression exceeds gate");
        }
        if (regressedUp(
                baseline.latencyHistogram().p95Nanos(),
                candidate.latencyHistogram().p95Nanos(),
                policy.latencyRegressionFraction())) {
            failures.add("p95 latency regression exceeds gate");
        }
        if (regressedUp(
                baseline.latencyHistogram().p99Nanos(),
                candidate.latencyHistogram().p99Nanos(),
                policy.latencyRegressionFraction())) {
            failures.add("p99 latency regression exceeds gate");
        }
        if (policy.failOnAcknowledgedWriteLoss()
                && candidate.counters().acknowledged() < baseline.counters().acknowledged()) {
            failures.add("acknowledged operation count decreased");
        }
        return new RegressionComparison(failures.isEmpty(), failures);
    }

    /** Selects the median run by p50 latency after validating semantic equivalence. */
    public static BenchmarkResultV1 medianByP50(List<BenchmarkResultV1> runs) {
        if (runs == null || runs.isEmpty()) throw new IllegalArgumentException("runs are required");
        BenchmarkResultV1 first = runs.getFirst();
        for (BenchmarkResultV1 run : runs) {
            if (!first.benchmarkId().equals(run.benchmarkId())
                    || !first.workload().equals(run.workload())
                    || !first.aetherConfig().equals(run.aetherConfig())) {
                throw new IllegalArgumentException("cannot take median of different benchmark semantics");
            }
        }
        BenchmarkResultV1[] sorted = runs.toArray(BenchmarkResultV1[]::new);
        Arrays.sort(
                sorted,
                java.util.Comparator.comparingLong(
                        result -> result.latencyHistogram().p50Nanos()));
        return sorted[sorted.length / 2];
    }

    private static boolean regressedDown(double baseline, double candidate, double fraction) {
        if (baseline <= 0.0d) return false;
        return candidate < baseline * (1.0d - fraction);
    }

    private static boolean regressedUp(long baseline, long candidate, double fraction) {
        if (baseline <= 0) return false;
        return candidate > Math.ceil(baseline * (1.0d + fraction));
    }
}
