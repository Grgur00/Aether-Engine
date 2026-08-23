package io.aetherdb.benchmarks;

/** Execution environment required by a benchmark profile. */
public enum BenchmarkProfileScope {
    /** Single-process local benchmark profile. */
    LOCAL,
    /** Distributed/RPC benchmark profile that requires a server or cluster runtime. */
    DISTRIBUTED
}
