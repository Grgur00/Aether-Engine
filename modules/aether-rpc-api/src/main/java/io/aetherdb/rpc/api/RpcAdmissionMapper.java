package io.aetherdb.rpc.api;

import io.aetherdb.admission.AdmissionDecision;
import io.aetherdb.admission.AdmissionOutcome;

/** Maps reusable Chapter 31 admission decisions to stable RPC statuses. */
public final class RpcAdmissionMapper {
    private RpcAdmissionMapper() {}

    public static RpcStatus status(AdmissionDecision decision) {
        if (decision == null) throw new IllegalArgumentException("decision must not be null");
        return switch (decision.outcome()) {
            case ACCEPTED -> RpcStatus.OK;
            case REJECTED_BEFORE_ACK, RESOURCE_EXHAUSTED -> RpcStatus.RESOURCE_EXHAUSTED;
            case DRAINING_REJECTED -> RpcStatus.UNAVAILABLE;
            case UNCERTAIN -> RpcStatus.FAILED_PRECONDITION;
        };
    }

    public static boolean retryable(AdmissionDecision decision) {
        if (decision == null) throw new IllegalArgumentException("decision must not be null");
        return decision.outcome() == AdmissionOutcome.REJECTED_BEFORE_ACK
                || decision.outcome() == AdmissionOutcome.DRAINING_REJECTED
                || decision.outcome() == AdmissionOutcome.RESOURCE_EXHAUSTED;
    }
}
