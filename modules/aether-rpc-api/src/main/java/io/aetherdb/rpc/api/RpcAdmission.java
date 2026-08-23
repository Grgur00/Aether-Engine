package io.aetherdb.rpc.api;

import io.aetherdb.admission.AdmissionRequest;
import io.aetherdb.admission.AdmissionResource;

import java.util.EnumMap;
import java.util.Map;

/** Builds Chapter 31 admission requests for RPC send/receive resource accounting. */
public final class RpcAdmission {
    private RpcAdmission() {}

    public static AdmissionRequest outboundRequest(
            RpcOperationDescriptor operation, int bodyBytes, int outboundPermits, boolean draining) {
        if (operation == null) throw new IllegalArgumentException("operation must not be null");
        if (bodyBytes < 0 || outboundPermits < 0)
            throw new IllegalArgumentException("RPC admission charges must be non-negative");
        if (bodyBytes > operation.requestLimit())
            throw new IllegalArgumentException("request exceeds operation limit");
        EnumMap<AdmissionResource, Long> charges = new EnumMap<>(AdmissionResource.class);
        charges.put(AdmissionResource.RPC_OUTBOUND_BYTES, (long) bodyBytes);
        charges.put(AdmissionResource.RPC_INFLIGHT_STREAMS, 1L);
        if (outboundPermits > 0)
            charges.put(AdmissionResource.VIRTUAL_THREAD_INFLIGHT, (long) outboundPermits);
        return new AdmissionRequest(charges, draining);
    }

    public static AdmissionRequest inboundRequest(
            RpcOperationDescriptor operation, int bodyBytes, boolean draining) {
        if (operation == null) throw new IllegalArgumentException("operation must not be null");
        if (bodyBytes < 0) throw new IllegalArgumentException("RPC body bytes must be non-negative");
        if (bodyBytes > operation.requestLimit())
            throw new IllegalArgumentException("request exceeds operation limit");
        return inboundRequest(bodyBytes, draining);
    }

    public static AdmissionRequest inboundRequest(int bodyBytes, boolean draining) {
        if (bodyBytes < 0) throw new IllegalArgumentException("RPC body bytes must be non-negative");
        return new AdmissionRequest(
                Map.of(
                        AdmissionResource.RPC_INBOUND_BYTES,
                        (long) bodyBytes,
                        AdmissionResource.RPC_INFLIGHT_STREAMS,
                        1L),
                draining);
    }
}
