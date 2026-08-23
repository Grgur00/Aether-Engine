package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

class ExternalBenchmarkComparatorTest {
    @Test
    void comparesOnlyWhenSemanticsMatch() {
        BenchmarkSemanticsManifest aether = manifest(ExternalEngine.AETHER, "GROUP_SYNC", 16, 100);
        BenchmarkSemanticsManifest rocks = manifest(ExternalEngine.ROCKSDB, "GROUP_SYNC", 16, 100);

        ExternalBenchmarkComparison comparison =
                ExternalBenchmarkComparator.compare(
                        aether, result(1000.0), rocks, result(500.0));

        assertThat(comparison.comparable()).isTrue();
        assertThat(comparison.throughputRatio()).isEqualTo(2.0d);
    }

    @Test
    void blocksDifferentDurabilityOrKeyValueShape() {
        BenchmarkSemanticsManifest aether = manifest(ExternalEngine.AETHER, "GROUP_SYNC", 16, 100);
        BenchmarkSemanticsManifest sqlite = manifest(ExternalEngine.SQLITE, "SYNC", 64, 100);

        ExternalBenchmarkComparison comparison =
                ExternalBenchmarkComparator.compare(
                        aether, result(1000.0), sqlite, result(500.0));

        assertThat(comparison.comparable()).isFalse();
        assertThat(comparison.blockers()).contains("benchmark semantics mismatch");
        assertThat(comparison.throughputRatio()).isZero();
    }

    @Test
    void blocksNonExternalRightSideAndResultManifestMismatch() {
        BenchmarkSemanticsManifest aether = manifest(ExternalEngine.AETHER, "GROUP_SYNC", 16, 100);

        ExternalBenchmarkComparison comparison =
                ExternalBenchmarkComparator.compare(
                        aether,
                        result(1000.0),
                        aether,
                        new BenchmarkResultV1(
                                "local.read.point_warm",
                                "abc",
                                false,
                                Map.of("os", "test"),
                                Map.of("durability", "GROUP_SYNC"),
                                Map.of("records", "100"),
                                new BenchmarkCounters(100, 100, 0, 0, 0),
                                500.0,
                                new BenchmarkLatencyHistogram(100, 1, 1, 1, 1),
                                Map.of(),
                                List.of()));

        assertThat(comparison.blockers())
                .contains(
                        "right manifest is not external",
                        "external result benchmark id does not match manifest");
    }

    private static BenchmarkSemanticsManifest manifest(
            ExternalEngine engine, String durability, int keyBytes, int valueBytes) {
        return new BenchmarkSemanticsManifest(
                engine,
                "local.write.sequential.group_sync",
                "machine-a",
                durability,
                keyBytes,
                valueBytes,
                100,
                1,
                Map.of("accessPattern", "sequential", "api", "put"));
    }

    private static BenchmarkResultV1 result(double throughput) {
        return new BenchmarkResultV1(
                "local.write.sequential.group_sync",
                "abc",
                false,
                Map.of("os", "test"),
                Map.of("durability", "GROUP_SYNC"),
                Map.of("records", "100"),
                new BenchmarkCounters(100, 100, 0, 0, 0),
                throughput,
                new BenchmarkLatencyHistogram(100, 1, 1, 1, 1),
                Map.of(),
                List.of());
    }
}
