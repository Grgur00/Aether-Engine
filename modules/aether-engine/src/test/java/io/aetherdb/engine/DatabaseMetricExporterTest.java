package io.aetherdb.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.observability.api.InMemoryMetricRegistry;
import io.aetherdb.observability.api.MetricObservation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.EnumMap;
import org.junit.jupiter.api.Test;

final class DatabaseMetricExporterTest {
    @Test
    void exportsStableAetherMetricNamesAndLabels() {
        EnumMap<DatabaseOperation, OperationMetrics> operations =
                new EnumMap<>(DatabaseOperation.class);
        operations.put(
                DatabaseOperation.GET,
                new OperationMetrics(
                        7,
                        2,
                        10.0,
                        1_000,
                        100,
                        500_000,
                        900_000,
                        1_000_000,
                        2_000_000));
        DatabaseMetrics metrics =
                new DatabaseMetrics(Instant.EPOCH, Duration.ofSeconds(1), operations);
        InMemoryMetricRegistry registry =
                new InMemoryMetricRegistry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

        DatabaseMetricExporter.export(metrics, registry);

        assertThat(registry.descriptors().keySet())
                .contains(
                        "aether_db_operations_total",
                        "aether_db_operation_errors_total",
                        "aether_db_operation_duration_seconds");
        assertThat(registry.snapshot().stream().map(MetricObservation::labels))
                .anySatisfy(labels -> assertThat(labels.values()).containsEntry("operation", "get"))
                .anySatisfy(labels -> assertThat(labels.values()).containsEntry("quantile", "p99"));
        assertThat(
                        registry.snapshot().stream()
                                .filter(
                                        observation ->
                                                observation
                                                        .descriptor()
                                                        .name()
                                                        .equals("aether_db_operations_total"))
                                .filter(
                                        observation ->
                                                "get".equals(
                                                        observation
                                                                .labels()
                                                                .values()
                                                                .get("operation")))
                                .mapToDouble(MetricObservation::value)
                                .sum())
                .isEqualTo(7.0);
    }
}
