package io.aetherdb.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.AetherDatabase;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

class MeteredAetherDatabaseTest {
    @Test
    void reportsCountsErrorsLatencyPercentilesAndThroughput() {
        AtomicLong clock = new AtomicLong();
        MeteredAetherDatabase database =
                new DefaultMeteredAetherDatabase(Aether.openInMemory(), () -> clock.getAndAdd(100));

        database.put(bytes("key"), bytes("value"));
        assertThat(database.get(bytes("key")).isFound()).isTrue();
        assertThatThrownBy(() -> database.get(null)).isInstanceOf(IllegalArgumentException.class);

        DatabaseMetrics metrics = database.metrics();
        assertThat(metrics.operation(DatabaseOperation.PUT).count()).isEqualTo(1);
        assertThat(metrics.operation(DatabaseOperation.GET).count()).isEqualTo(2);
        assertThat(metrics.operation(DatabaseOperation.GET).errors()).isEqualTo(1);
        assertThat(metrics.operation(DatabaseOperation.GET).errorRate()).isEqualTo(0.5);
        assertThat(metrics.operation(DatabaseOperation.GET).p50LatencyNanos()).isEqualTo(100);
        assertThat(metrics.operation(DatabaseOperation.GET).p95LatencyNanos()).isEqualTo(100);
        assertThat(metrics.operation(DatabaseOperation.GET).p99LatencyNanos()).isEqualTo(100);
        assertThat(metrics.operation(DatabaseOperation.GET).operationsPerSecond()).isPositive();
        database.close();
    }

    @Test
    void resetStartsFreshIntervalWithoutChangingData() {
        MeteredAetherDatabase database = Aether.openInMemoryWithMetrics();
        database.put(bytes("key"), bytes("value"));

        database.resetMetrics();

        assertThat(database.metrics().operation(DatabaseOperation.PUT).count()).isZero();
        assertThat(database.get(bytes("key")).value()).isEqualTo(bytes("value"));
        database.close();
    }

    @Test
    void meteredPersistentDatabaseSurvivesCloseAndReopen(@TempDir Path directory) {
        try (MeteredAetherDatabase database = Aether.openWithMetrics(directory)) {
            database.put(bytes("key"), bytes("value"));
            assertThat(database.metrics().operation(DatabaseOperation.PUT).count()).isEqualTo(1);
        }

        try (AetherDatabase database = Aether.open(directory)) {
            assertThat(database.get(bytes("key")).value()).isEqualTo(bytes("value"));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
