package io.aetherdb.engine;

import io.aetherdb.observability.api.InMemoryMetricRegistry;
import io.aetherdb.observability.api.MetricDescriptor;
import io.aetherdb.observability.api.MetricKind;
import io.aetherdb.observability.api.MetricLabels;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Exports local database metrics into stable Chapter 28 metric descriptors. */
public final class DatabaseMetricExporter {
    public static final MetricDescriptor OPERATIONS_TOTAL =
            new MetricDescriptor(
                    "aether_db_operations_total",
                    MetricKind.COUNTER,
                    "1",
                    List.of("operation", "result"));
    public static final MetricDescriptor OPERATION_DURATION_SECONDS =
            new MetricDescriptor(
                    "aether_db_operation_duration_seconds",
                    MetricKind.HISTOGRAM,
                    "s",
                    List.of("operation", "quantile"));
    public static final MetricDescriptor OPERATION_ERRORS_TOTAL =
            new MetricDescriptor(
                    "aether_db_operation_errors_total",
                    MetricKind.COUNTER,
                    "1",
                    List.of("operation"));

    private DatabaseMetricExporter() {}

    public static void export(DatabaseMetrics metrics, InMemoryMetricRegistry registry) {
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(registry, "registry");
        for (DatabaseOperation operation : DatabaseOperation.values()) {
            OperationMetrics operationMetrics = metrics.operation(operation);
            String name = operation.name().toLowerCase(Locale.ROOT);
            registry.observe(
                    OPERATIONS_TOTAL,
                    new MetricLabels(Map.of("operation", name, "result", "completed")),
                    operationMetrics.count());
            registry.observe(
                    OPERATION_ERRORS_TOTAL,
                    MetricLabels.of("operation", name),
                    operationMetrics.errors());
            observeLatency(registry, name, "p50", operationMetrics.p50LatencyNanos());
            observeLatency(registry, name, "p95", operationMetrics.p95LatencyNanos());
            observeLatency(registry, name, "p99", operationMetrics.p99LatencyNanos());
        }
    }

    private static void observeLatency(
            InMemoryMetricRegistry registry, String operation, String quantile, long nanos) {
        registry.observe(
                OPERATION_DURATION_SECONDS,
                new MetricLabels(Map.of("operation", operation, "quantile", quantile)),
                nanos / 1_000_000_000.0);
    }
}
