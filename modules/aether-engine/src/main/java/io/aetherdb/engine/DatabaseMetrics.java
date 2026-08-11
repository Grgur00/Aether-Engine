package io.aetherdb.engine;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable point-in-time metrics from an instrumented database.
 *
 * @param collectedAt wall-clock time at which the snapshot was collected
 * @param collectionInterval elapsed interval represented by cumulative values
 * @param operations measurements grouped by database operation
 */
public record DatabaseMetrics(
        Instant collectedAt,
        Duration collectionInterval,
        Map<DatabaseOperation, OperationMetrics> operations) {

    /** Creates a defensively copied metrics snapshot. */
    public DatabaseMetrics {
        Objects.requireNonNull(collectedAt, "collectedAt");
        Objects.requireNonNull(collectionInterval, "collectionInterval");
        Objects.requireNonNull(operations, "operations");
        operations = Collections.unmodifiableMap(new EnumMap<>(operations));
    }

    /**
     * Returns metrics for an operation, including an all-zero value when the operation has not yet
     * been invoked.
     *
     * @param operation operation to inspect
     * @return operation measurements
     */
    public OperationMetrics operation(DatabaseOperation operation) {
        Objects.requireNonNull(operation, "operation");
        return operations.getOrDefault(operation, new OperationMetrics(0, 0, 0, 0, 0, 0, 0, 0, 0));
    }
}
