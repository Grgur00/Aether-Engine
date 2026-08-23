package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class BenchmarkProfileRunnerTest {
    @Test
    void plansSupportedCvBackedProfilesWithCanonicalArguments() {
        BenchmarkProfileRunPlan plan =
                BenchmarkProfileRunner.plan(
                        new String[] {
                            "--profile",
                            "local.write.sequential.group_sync",
                            "--directory",
                            "bench-db",
                            "--output",
                            "result.json",
                            "--records",
                            "100",
                            "--reads",
                            "5000"
                        });

        assertThat(plan.executable()).isTrue();
        assertThat(plan.profile().id()).isEqualTo("local.write.sequential.group_sync");
        assertThat(plan.cvArguments())
                .containsExactly(
                        "--directory",
                        "bench-db",
                        "--output",
                        "result.json",
                        "--records",
                        "100",
                        "--reads",
                        "1000",
                        "--crash-points",
                        "0",
                        "--batch-size",
                        "1000",
                        "--value-bytes",
                        "256",
                        "--durability",
                        "GROUP_SYNC",
                        "--cache-mode",
                        "REOPENED_WARMUP");
    }

    @Test
    void registeredButUnsupportedProfilesProduceNonExecutablePlans() {
        BenchmarkProfileRunPlan plan =
                BenchmarkProfileRunner.plan(
                        new String[] {
                            "--profile", "local.read.range_scan", "--directory", "bench-db"
                        });

        assertThat(plan.executable()).isFalse();
        assertThat(plan.output()).isEqualTo(Path.of("bench-db").toAbsolutePath().normalize().resolveSibling("bench-db-results.json"));
    }

    @Test
    void rejectsUnknownProfiles() {
        assertThatThrownBy(
                        () ->
                                BenchmarkProfileRunner.plan(
                                        new String[] {
                                            "--profile", "missing", "--directory", "bench-db"
                                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown profile");
    }
}
