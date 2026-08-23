package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class BenchmarkBaselineStoreTest {
    @Test
    void baselineStoreRoundTripsDeterministicallyAndSortsByProfile() {
        BenchmarkBaselineStore store =
                new BenchmarkBaselineStore(
                        List.of(
                                entry("local.read.point_warm", 2000.0, 20, 30, 40),
                                entry("local.write.sequential.group_sync", 1000.0, 10, 20, 30)));

        String encoded = store.encode();
        BenchmarkBaselineStore decoded = BenchmarkBaselineStore.decode(encoded);

        assertThat(encoded).startsWith("schemaVersion=1\nentries=2\n");
        assertThat(decoded.entries()).extracting(BenchmarkBaselineEntry::profileId)
                .containsExactly("local.read.point_warm", "local.write.sequential.group_sync");
        assertThat(decoded.encode()).isEqualTo(encoded);
    }

    @Test
    void rejectsDuplicateProfiles() {
        assertThatThrownBy(
                        () ->
                                new BenchmarkBaselineStore(
                                        List.of(
                                                entry("local.read.point_warm", 1, 1, 1, 1),
                                                entry("local.read.point_warm", 2, 2, 2, 2))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    void gateEvaluatorReportsMissingBaselinesAndDelegatesRegressionChecks() {
        BenchmarkResultV1 candidate = result(700.0, 10, 100, 110, 99);
        BenchmarkBaselineStore store =
                new BenchmarkBaselineStore(
                        List.of(
                                BenchmarkBaselineEntry.fromResult(
                                        result(1000.0, 10, 20, 30, 100), "file:///baseline.json")));

        assertThat(
                        BenchmarkGateEvaluator.evaluate(
                                        new BenchmarkBaselineStore(List.of()),
                                        candidate,
                                        RegressionGatePolicy.END_TO_END)
                                .failures())
                .containsExactly("missing baseline for local.write.sequential.group_sync");
        assertThat(
                        BenchmarkGateEvaluator.evaluate(store, candidate, RegressionGatePolicy.END_TO_END)
                                .failures())
                .contains("throughput regression exceeds gate", "p95 latency regression exceeds gate");
    }

    private static BenchmarkBaselineEntry entry(
            String profileId, double throughput, long p50, long p95, long p99) {
        return new BenchmarkBaselineEntry(
                profileId, "file:///" + profileId + ".json", "abc", throughput, p50, p95, p99, 100);
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
