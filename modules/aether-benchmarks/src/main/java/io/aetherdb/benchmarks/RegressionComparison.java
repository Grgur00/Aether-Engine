package io.aetherdb.benchmarks;

import java.util.List;

/** Deterministic regression-gate evaluation result. */
public record RegressionComparison(boolean passed, List<String> failures) {
    /** Validates and copies failures. */
    public RegressionComparison {
        failures = failures == null ? List.of() : List.copyOf(failures);
        if (passed != failures.isEmpty()) {
            throw new IllegalArgumentException("passed must match failure list");
        }
    }
}
