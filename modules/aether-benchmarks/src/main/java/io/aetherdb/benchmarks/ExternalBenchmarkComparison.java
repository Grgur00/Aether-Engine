package io.aetherdb.benchmarks;

import java.util.List;

/** Result of comparing Aether with an external engine benchmark result. */
public record ExternalBenchmarkComparison(boolean comparable, List<String> blockers, double throughputRatio) {
    /** Validates comparison consistency. */
    public ExternalBenchmarkComparison {
        blockers = blockers == null ? List.of() : List.copyOf(blockers);
        if (comparable != blockers.isEmpty() || !Double.isFinite(throughputRatio) || throughputRatio < 0) {
            throw new IllegalArgumentException("invalid external benchmark comparison");
        }
    }
}
