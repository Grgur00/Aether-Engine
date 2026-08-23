package io.aetherdb.rpc.transport;

import io.aetherdb.config.AetherConfigRegistry;
import io.aetherdb.config.AetherConfigValidator;
import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.rpc.codec.RpcFrameHeaderV1;

import java.util.Map;
import java.util.Objects;

/** Runtime RPC transport limits resolved from Chapter 32 configuration. */
public record RpcTransportConfiguration(
        int frameBytes,
        int messageBytes,
        int streams,
        int inboundBytes,
        int outboundPermits,
        int permitBytes) {
    private static final AetherConfigRegistry REGISTRY = AetherConfigRegistry.defaults();
    private static final int DEFAULT_PERMIT_BYTES = 1024;

    public RpcTransportConfiguration {
        if (frameBytes < 1024 || frameBytes > RpcFrameHeaderV1.MAX_FRAME_PAYLOAD)
            throw new IllegalArgumentException("invalid RPC frame byte limit");
        if (messageBytes < frameBytes || messageBytes > RpcFrameHeaderV1.MAX_MESSAGE_BYTES)
            throw new IllegalArgumentException("invalid RPC message byte limit");
        if (streams <= 0 || inboundBytes < frameBytes || outboundPermits <= 0 || permitBytes <= 0)
            throw new IllegalArgumentException("invalid RPC transport limits");
    }

    public static RpcTransportConfiguration defaults() {
        return from(new AetherConfiguration(Map.of("aether.security.profile", "development")));
    }

    public static RpcTransportConfiguration from(AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        int outboundBytes = intValue(configuration, "aether.rpc.outbound_bytes");
        return new RpcTransportConfiguration(
                intValue(configuration, "aether.rpc.max_frame_bytes"),
                intValue(configuration, "aether.rpc.max_message_bytes"),
                intValue(configuration, "aether.rpc.max_streams"),
                intValue(configuration, "aether.rpc.inbound_bytes"),
                Math.max(1, Math.floorDiv(outboundBytes, DEFAULT_PERMIT_BYTES)),
                DEFAULT_PERMIT_BYTES);
    }

    AdmissionPolicyPair admissionPolicies() {
        return new AdmissionPolicyPair(
                io.aetherdb.admission.AdmissionPolicy.of(
                        new io.aetherdb.admission.ResourceLimit(
                                io.aetherdb.admission.AdmissionResource.VIRTUAL_THREAD_INFLIGHT,
                                outboundPermits,
                                outboundPermits,
                                outboundPermits)),
                new io.aetherdb.admission.AdmissionPolicy(
                        Map.of(
                                io.aetherdb.admission.AdmissionResource.RPC_INBOUND_BYTES,
                                new io.aetherdb.admission.ResourceLimit(
                                        io.aetherdb.admission.AdmissionResource.RPC_INBOUND_BYTES,
                                        inboundBytes,
                                        inboundBytes,
                                        inboundBytes),
                                io.aetherdb.admission.AdmissionResource.RPC_INFLIGHT_STREAMS,
                                new io.aetherdb.admission.ResourceLimit(
                                        io.aetherdb.admission.AdmissionResource.RPC_INFLIGHT_STREAMS,
                                        streams,
                                        streams,
                                        streams)),
                        java.time.Duration.ZERO));
    }

    private static int intValue(AetherConfiguration configuration, String name) {
        return Math.toIntExact(Long.parseLong(configuration.getOrDefault(REGISTRY.require(name))));
    }

    record AdmissionPolicyPair(
            io.aetherdb.admission.AdmissionPolicy outbound,
            io.aetherdb.admission.AdmissionPolicy inbound) {}
}
