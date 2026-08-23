package io.aetherdb.benchmarks;

import java.util.List;

/** Evaluates benchmark results against persisted baseline storage. */
public final class BenchmarkGateEvaluator {
    private BenchmarkGateEvaluator() {}

    /** Evaluates one candidate result against its stored baseline. */
    public static RegressionComparison evaluate(
            BenchmarkBaselineStore baselines,
            BenchmarkResultV1 candidate,
            RegressionGatePolicy policy) {
        if (baselines == null || candidate == null || policy == null) {
            throw new IllegalArgumentException("gate inputs are required");
        }
        return baselines
                .find(candidate.benchmarkId())
                .map(entry -> RegressionComparator.compare(entry.toResultLike(candidate), candidate, policy))
                .orElseGet(
                        () ->
                                new RegressionComparison(
                                        false,
                                        List.of("missing baseline for " + candidate.benchmarkId())));
    }
}
