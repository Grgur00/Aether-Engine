package io.aetherdb.observability.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class ObservabilityApiTest {
    @Test
    void healthStatesExposeServingSemantics() {
        assertThat(HealthState.LEADER_SERVING.readsAllowed()).isTrue();
        assertThat(HealthState.LEADER_SERVING.writesAllowed()).isTrue();
        assertThat(HealthState.FOLLOWER_SERVING.readsAllowed()).isTrue();
        assertThat(HealthState.FOLLOWER_SERVING.writesAllowed()).isFalse();
        assertThat(HealthState.UNHEALTHY.readsAllowed()).isFalse();
    }

    @Test
    void metricDescriptorRejectsNonAetherPrefix() {
        assertThatThrownBy(
                        () ->
                                new MetricDescriptor(
                                        "http_requests_total",
                                        MetricKind.COUNTER,
                                        "1",
                                        List.of("result")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("metric name");
    }

    @Test
    void metricDescriptorRejectsSensitiveLabels() {
        assertThatThrownBy(
                        () ->
                                new MetricDescriptor(
                                        "aether_rpc_requests_total",
                                        MetricKind.COUNTER,
                                        "1",
                                        List.of("principal_id")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("forbidden");
    }

    @Test
    void healthStatusValidatesBoundedDetails() {
        HealthStatus status =
                new HealthStatus(
                        HealthState.SERVING,
                        "OK",
                        Instant.EPOCH,
                        Map.of("role", "leader", "result", "ok"));

        assertThat(status.details()).containsEntry("role", "leader");
    }

    @Test
    void registryRecordsValidatedObservations() {
        InMemoryMetricRegistry registry =
                new InMemoryMetricRegistry(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
        MetricDescriptor descriptor =
                new MetricDescriptor(
                        "aether_db_operations_total",
                        MetricKind.COUNTER,
                        "1",
                        List.of("operation", "result"));

        registry.observe(
                descriptor,
                new MetricLabels(Map.of("operation", "get", "result", "ok")),
                3);

        assertThat(registry.snapshot())
                .containsExactly(
                        new MetricObservation(
                                descriptor,
                                new MetricLabels(Map.of("operation", "get", "result", "ok")),
                                3,
                                Instant.EPOCH));
    }

    @Test
    void observationRejectsUndeclaredLabels() {
        MetricDescriptor descriptor =
                new MetricDescriptor(
                        "aether_db_operations_total",
                        MetricKind.COUNTER,
                        "1",
                        List.of("operation"));

        assertThatThrownBy(
                        () ->
                                new MetricObservation(
                                        descriptor,
                                        new MetricLabels(
                                                Map.of("operation", "get", "result", "ok")),
                                        1,
                                        Instant.EPOCH))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not declared");
    }
}
