package io.aetherdb.admission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

class AdmissionControllerTest {
    private final AdmissionController controller = new AdmissionController();

    @Test
    void acceptsWhenProjectedUsageStaysBelowSoftLimits() {
        AdmissionDecision decision =
                controller.evaluate(
                        policy(limit(AdmissionResource.RPC_INBOUND_BYTES, 100, 200, 300)),
                        snapshot(AdmissionResource.RPC_INBOUND_BYTES, 40),
                        request(AdmissionResource.RPC_INBOUND_BYTES, 30));

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.ACCEPTED);
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void softLimitProducesBoundedBeforeAckSlowdownDecision() {
        AdmissionPolicy policy =
                new AdmissionPolicy(
                        Map.of(
                                AdmissionResource.COMPACTION_DEBT_BYTES,
                                limit(AdmissionResource.COMPACTION_DEBT_BYTES, 100, 200, 300)),
                        Duration.ofMillis(50));

        AdmissionDecision decision =
                controller.evaluate(
                        policy,
                        snapshot(AdmissionResource.COMPACTION_DEBT_BYTES, 90),
                        request(AdmissionResource.COMPACTION_DEBT_BYTES, 40));

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.REJECTED_BEFORE_ACK);
        assertThat(decision.reasons()).containsExactly("soft limit reached: COMPACTION_DEBT_BYTES");
        assertThat(decision.suggestedDelay()).isPositive().isLessThanOrEqualTo(Duration.ofMillis(50));
    }

    @Test
    void hardLimitRejectsAsResourceExhaustedBeforeAck() {
        AdmissionDecision decision =
                controller.evaluate(
                        policy(limit(AdmissionResource.WAL_RETAINED_BYTES, 100, 200, 300)),
                        snapshot(AdmissionResource.WAL_RETAINED_BYTES, 180),
                        request(AdmissionResource.WAL_RETAINED_BYTES, 20));

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
        assertThat(decision.reasons()).containsExactly("hard limit reached: WAL_RETAINED_BYTES");
        assertThat(decision.suggestedDelay()).isZero();
    }

    @Test
    void emergencyLimitRejectsBeforeHardReasonsAndPreservesDegradedSignal() {
        AdmissionDecision decision =
                controller.evaluate(
                        policy(limit(AdmissionResource.NATIVE_MEMTABLE_BYTES, 100, 200, 250)),
                        snapshot(AdmissionResource.NATIVE_MEMTABLE_BYTES, 240),
                        request(AdmissionResource.NATIVE_MEMTABLE_BYTES, 10));

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
        assertThat(decision.reasons()).containsExactly("emergency limit reached: NATIVE_MEMTABLE_BYTES");
    }

    @Test
    void drainingRejectsBeforeResourceEvaluation() {
        AdmissionDecision decision =
                controller.evaluate(
                        policy(limit(AdmissionResource.RPC_INFLIGHT_STREAMS, 10, 20, 30)),
                        snapshot(AdmissionResource.RPC_INFLIGHT_STREAMS, 0),
                        new AdmissionRequest(
                                Map.of(AdmissionResource.RPC_INFLIGHT_STREAMS, 1L), true));

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.DRAINING_REJECTED);
        assertThat(decision.reasons()).containsExactly("node is draining");
    }

    @Test
    void evaluatesMultipleResourcesInStableOrder() {
        AdmissionDecision decision =
                controller.evaluate(
                        new AdmissionPolicy(
                                Map.of(
                                        AdmissionResource.RPC_OUTBOUND_BYTES,
                                        limit(AdmissionResource.RPC_OUTBOUND_BYTES, 100, 200, 300),
                                        AdmissionResource.RPC_INBOUND_BYTES,
                                        limit(AdmissionResource.RPC_INBOUND_BYTES, 100, 200, 300)),
                                Duration.ZERO),
                        new AdmissionSnapshot(
                                Map.of(
                                        AdmissionResource.RPC_OUTBOUND_BYTES,
                                        new ResourceMeasurement(AdmissionResource.RPC_OUTBOUND_BYTES, 190),
                                        AdmissionResource.RPC_INBOUND_BYTES,
                                        new ResourceMeasurement(AdmissionResource.RPC_INBOUND_BYTES, 190))),
                        new AdmissionRequest(
                                Map.of(
                                        AdmissionResource.RPC_OUTBOUND_BYTES,
                                        10L,
                                        AdmissionResource.RPC_INBOUND_BYTES,
                                        10L),
                                false));

        assertThat(decision.reasons())
                .containsExactly(
                        "hard limit reached: RPC_INBOUND_BYTES",
                        "hard limit reached: RPC_OUTBOUND_BYTES");
    }

    @Test
    void rejectsInvalidLimitAndDecisionShapes() {
        assertThatThrownBy(
                        () ->
                                new ResourceLimit(
                                        AdmissionResource.HEAP_BYTES,
                                        200,
                                        100,
                                        300))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("soft <= hard <= emergency");
        assertThatThrownBy(
                        () ->
                                new AdmissionDecision(
                                        AdmissionOutcome.ACCEPTED,
                                        java.util.List.of("reason"),
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accepted decision");
    }

    private static AdmissionPolicy policy(ResourceLimit limit) {
        return AdmissionPolicy.of(limit);
    }

    private static ResourceLimit limit(
            AdmissionResource resource, long softLimit, long hardLimit, long emergencyLimit) {
        return new ResourceLimit(resource, softLimit, hardLimit, emergencyLimit);
    }

    private static AdmissionSnapshot snapshot(AdmissionResource resource, long currentValue) {
        return new AdmissionSnapshot(
                Map.of(resource, new ResourceMeasurement(resource, currentValue)));
    }

    private static AdmissionRequest request(AdmissionResource resource, long charge) {
        return new AdmissionRequest(Map.of(resource, charge), false);
    }
}
