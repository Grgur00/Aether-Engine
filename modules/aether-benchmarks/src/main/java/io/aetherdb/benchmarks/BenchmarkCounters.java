package io.aetherdb.benchmarks;

/** Operation outcome counters required by the Chapter 30 benchmark schema. */
public record BenchmarkCounters(
        long submitted, long acknowledged, long rejectedBeforeAck, long uncertain, long failed) {
    /** Validates non-negative counters and bounded outcome accounting. */
    public BenchmarkCounters {
        if (submitted < 0
                || acknowledged < 0
                || rejectedBeforeAck < 0
                || uncertain < 0
                || failed < 0
                || acknowledged + rejectedBeforeAck + uncertain + failed > submitted) {
            throw new IllegalArgumentException("invalid benchmark counters");
        }
    }
}
