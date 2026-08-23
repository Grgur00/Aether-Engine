package io.aetherdb.benchmarks;

import java.nio.file.Path;
import java.util.List;

/** Deterministic execution plan for one benchmark profile invocation. */
public record BenchmarkProfileRunPlan(BenchmarkProfile profile, Path directory, Path output, List<String> cvArguments) {
    /** Validates paths and immutable CV argument list. */
    public BenchmarkProfileRunPlan {
        if (profile == null || directory == null || output == null || cvArguments == null) {
            throw new IllegalArgumentException("invalid benchmark run plan");
        }
        cvArguments = List.copyOf(cvArguments);
    }

    /** Returns whether this plan can execute using the current in-process runner. */
    public boolean executable() {
        return !cvArguments.isEmpty();
    }
}
