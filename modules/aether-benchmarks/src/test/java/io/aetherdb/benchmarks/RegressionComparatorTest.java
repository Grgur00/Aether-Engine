package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class RegressionComparatorTest {
    @Test
    void endToEndGateDetectsThroughputLatencyAndAcknowledgementRegressions() {
        BenchmarkResultV1 baseline = result(1000.0, 100, 120, 140, 100);
        BenchmarkResultV1 candidate = result(700.0, 100, 200, 210, 99);

        RegressionComparison comparison =
                RegressionComparator.compare(
                        baseline, candidate, RegressionGatePolicy.END_TO_END);

        assertThat(comparison.passed()).isFalse();
        assertThat(comparison.failures())
                .contains(
                        "throughput regression exceeds gate",
                        "p95 latency regression exceeds gate",
                        "p99 latency regression exceeds gate",
                        "acknowledged operation count decreased");
    }

    @Test
    void rejectsSemanticMismatchesBeforeClaimingComparableResults() {
        BenchmarkResultV1 baseline = result(1000.0, 100, 120, 140, 100);
        BenchmarkResultV1 candidate =
                new BenchmarkResultV1(
                        "local.read.point_warm",
                        "def",
                        false,
                        Map.of("os", "test"),
                        Map.of("durability", "GROUP_SYNC"),
                        Map.of("records", "100"),
                        new BenchmarkCounters(100, 100, 0, 0, 0),
                        1000.0,
                        new BenchmarkLatencyHistogram(100, 100, 120, 140, 200),
                        Map.of(),
                        List.of());

        RegressionComparison comparison =
                RegressionComparator.compare(
                        baseline, candidate, RegressionGatePolicy.END_TO_END);

        assertThat(comparison.failures()).contains("benchmark id mismatch");
    }

    @Test
    void medianByP50RequiresEquivalentSemantics() {
        BenchmarkResultV1 slow = result(1000.0, 300, 320, 340, 100);
        BenchmarkResultV1 fast = result(1000.0, 100, 120, 140, 100);
        BenchmarkResultV1 middle = result(1000.0, 200, 220, 240, 100);

        assertThat(RegressionComparator.medianByP50(List.of(slow, fast, middle))).isEqualTo(middle);
        assertThatThrownBy(
                        () ->
                                RegressionComparator.medianByP50(
                                        List.of(
                                                slow,
                                                new BenchmarkResultV1(
                                                        "different",
                                                        "abc",
                                                        false,
                                                        Map.of("os", "test"),
                                                        Map.of("durability", "GROUP_SYNC"),
                                                        Map.of("records", "100"),
                                                        new BenchmarkCounters(100, 100, 0, 0, 0),
                                                        1.0,
                                                        new BenchmarkLatencyHistogram(100, 1, 1, 1, 1),
                                                        Map.of(),
                                                        List.of()))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BenchmarkResultV1 result(
            double throughput, long p50, long p95, long p99, long acknowledged) {
        return new BenchmarkResultV1(
                "local.write.sequential.group_sync",
                "abc",
                false,
                Map.of("os", "test"),
                Map.of("durability", "GROUP_SYNC"),
                Map.of("records", "100"),
                new BenchmarkCounters(100, acknowledged, 0, 0, 100 - acknowledged),
                throughput,
                new BenchmarkLatencyHistogram(100, p50, p95, p99, p99 + 10),
                Map.of(),
                List.of());
    }
}
