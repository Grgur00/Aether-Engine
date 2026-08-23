package io.aetherdb.rpc.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.admission.AdmissionDecision;
import io.aetherdb.admission.AdmissionOutcome;
import io.aetherdb.admission.AdmissionRequest;
import io.aetherdb.admission.AdmissionResource;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

class RpcAdmissionTest {
    @Test
    void outboundRequestChargesBytesStreamAndPermitBudget() {
        AdmissionRequest request = RpcAdmission.outboundRequest(operation(), 128, 2, false);

        assertThat(request.draining()).isFalse();
        assertThat(request.charges())
                .containsEntry(AdmissionResource.RPC_OUTBOUND_BYTES, 128L)
                .containsEntry(AdmissionResource.RPC_INFLIGHT_STREAMS, 1L)
                .containsEntry(AdmissionResource.VIRTUAL_THREAD_INFLIGHT, 2L);
    }

    @Test
    void inboundRequestChargesAssemblyBytesAndStream() {
        AdmissionRequest request = RpcAdmission.inboundRequest(operation(), 64, true);

        assertThat(request.draining()).isTrue();
        assertThat(request.charges())
                .containsEntry(AdmissionResource.RPC_INBOUND_BYTES, 64L)
                .containsEntry(AdmissionResource.RPC_INFLIGHT_STREAMS, 1L);
    }

    @Test
    void oversizedRequestIsRejectedBeforeAdmissionAllocation() {
        assertThatThrownBy(() -> RpcAdmission.outboundRequest(operation(), 257, 1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request exceeds operation limit");
        assertThatThrownBy(() -> RpcAdmission.inboundRequest(operation(), 257, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request exceeds operation limit");
    }

    @Test
    void mapsAdmissionOutcomesToStableRpcStatuses() {
        assertThat(RpcAdmissionMapper.status(decision(AdmissionOutcome.ACCEPTED)))
                .isEqualTo(RpcStatus.OK);
        assertThat(RpcAdmissionMapper.status(decision(AdmissionOutcome.REJECTED_BEFORE_ACK)))
                .isEqualTo(RpcStatus.RESOURCE_EXHAUSTED);
        assertThat(RpcAdmissionMapper.status(decision(AdmissionOutcome.RESOURCE_EXHAUSTED)))
                .isEqualTo(RpcStatus.RESOURCE_EXHAUSTED);
        assertThat(RpcAdmissionMapper.status(decision(AdmissionOutcome.DRAINING_REJECTED)))
                .isEqualTo(RpcStatus.UNAVAILABLE);
        assertThat(RpcAdmissionMapper.status(decision(AdmissionOutcome.UNCERTAIN)))
                .isEqualTo(RpcStatus.FAILED_PRECONDITION);
    }

    @Test
    void retryClassificationMatchesPreAckAndUnavailableOutcomes() {
        assertThat(RpcAdmissionMapper.retryable(decision(AdmissionOutcome.ACCEPTED))).isFalse();
        assertThat(RpcAdmissionMapper.retryable(decision(AdmissionOutcome.UNCERTAIN))).isFalse();
        assertThat(RpcAdmissionMapper.retryable(decision(AdmissionOutcome.REJECTED_BEFORE_ACK))).isTrue();
        assertThat(RpcAdmissionMapper.retryable(decision(AdmissionOutcome.RESOURCE_EXHAUSTED))).isTrue();
        assertThat(RpcAdmissionMapper.retryable(decision(AdmissionOutcome.DRAINING_REJECTED))).isTrue();
    }

    private static AdmissionDecision decision(AdmissionOutcome outcome) {
        return new AdmissionDecision(
                outcome,
                outcome == AdmissionOutcome.ACCEPTED ? List.of() : List.of("reason"),
                Duration.ZERO);
    }

    private static RpcOperationDescriptor operation() {
        return new RpcOperationDescriptor(
                7,
                256,
                512,
                RpcRetryClass.IDEMPOTENT,
                RpcExecutionPolicy.STORAGE_WRITE,
                Duration.ofSeconds(5));
    }
}
