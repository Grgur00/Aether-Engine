package io.aetherdb.rpc.transport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.admission.AdmissionOutcome;
import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.rpc.api.RpcBackpressureMode;
import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcExecutionPolicy;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcRetryClass;
import io.aetherdb.rpc.api.RpcStatus;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

class PlaintextDevelopmentRpcTest {
    private static final UUID CLUSTER = UUID.fromString("1fd52d9e-6281-43a3-a2f0-5f675d1493ef");
    private static final RpcOperationDescriptor ECHO =
            new RpcOperationDescriptor(
                    100,
                    4 * 1024 * 1024,
                    4 * 1024 * 1024,
                    RpcRetryClass.IDEMPOTENT,
                    RpcExecutionPolicy.STORAGE_READ,
                    Duration.ofSeconds(5));

    @Test
    void multiplexesConcurrentFragmentedCallsAndPreservesEveryByte() throws Exception {
        RpcIdentity serverIdentity =
                RpcIdentity.start(CLUSTER, UUID.fromString("44444444-4444-4444-8444-444444444444"));
        RpcIdentity clientIdentity =
                RpcIdentity.start(CLUSTER, UUID.fromString("55555555-5555-4555-8555-555555555555"));
        try (var server = PlaintextDevelopmentRpc.bind(serverIdentity, "127.0.0.1", 0);
                var client = PlaintextDevelopmentRpc.client(clientIdentity)) {
            server.register(ECHO, (request, responder) -> responder.success(request.body()));
            byte[] body = new byte[1024 * 1024 + 137];
            new java.util.Random(42).nextBytes(body);
            List<java.util.concurrent.CompletableFuture<io.aetherdb.rpc.api.RpcResponse>> calls =
                    new ArrayList<>();
            for (int index = 0; index < 12; index++)
                calls.add(
                        client.call(server.endpoint(), ECHO, body, RpcCallOptions.defaults(ECHO)));

            for (var call : calls) {
                var response = call.get(10, TimeUnit.SECONDS);
                assertThat(response.status()).isEqualTo(RpcStatus.OK);
                assertThat(response.body()).isEqualTo(body);
            }
        }
    }

    @Test
    void propagatesDeadlineCancellationToTheRunningHandler() throws Exception {
        RpcIdentity serverIdentity =
                RpcIdentity.start(CLUSTER, UUID.fromString("66666666-6666-4666-8666-666666666666"));
        RpcIdentity clientIdentity =
                RpcIdentity.start(CLUSTER, UUID.fromString("77777777-7777-4777-8777-777777777777"));
        CountDownLatch cancelled = new CountDownLatch(1);
        try (var server = PlaintextDevelopmentRpc.bind(serverIdentity, "127.0.0.1", 0);
                var client = PlaintextDevelopmentRpc.client(clientIdentity)) {
            server.register(
                    ECHO,
                    (request, responder) -> request.cancellation().onCancel(cancelled::countDown));
            var response =
                    client.call(
                                    server.endpoint(),
                                    ECHO,
                                    new byte[] {1},
                                    new RpcCallOptions(
                                            Duration.ofMillis(100),
                                            false,
                                            RpcBackpressureMode.FAIL_FAST))
                            .get(5, TimeUnit.SECONDS);

            assertThat(response.status()).isEqualTo(RpcStatus.DEADLINE_EXCEEDED);
            assertThat(cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void rejectsAnotherClusterAndPinnedNodeMismatchDuringHello() {
        RpcIdentity serverIdentity =
                RpcIdentity.start(CLUSTER, UUID.fromString("88888888-8888-4888-8888-888888888888"));
        RpcIdentity wrongCluster =
                RpcIdentity.start(
                        UUID.fromString("99999999-9999-4999-8999-999999999999"),
                        UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        try (var server = PlaintextDevelopmentRpc.bind(serverIdentity, "127.0.0.1", 0);
                var client = PlaintextDevelopmentRpc.client(wrongCluster)) {
            server.register(ECHO, (request, responder) -> responder.success(request.body()));
            assertThatThrownBy(
                            () ->
                                    client.call(
                                                    server.endpoint(),
                                                    ECHO,
                                                    new byte[0],
                                                    RpcCallOptions.defaults(ECHO))
                                            .join())
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        RpcIdentity validClient =
                RpcIdentity.start(CLUSTER, UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
        try (var server = PlaintextDevelopmentRpc.bind(serverIdentity, "127.0.0.1", 0);
                var client = PlaintextDevelopmentRpc.client(validClient)) {
            var wrongPin =
                    new io.aetherdb.rpc.api.RpcEndpoint(
                            server.endpoint().host(),
                            server.endpoint().port(),
                            UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
            assertThatThrownBy(
                            () ->
                                    client.call(
                                                    wrongPin,
                                                    ECHO,
                                                    new byte[0],
                                                    RpcCallOptions.defaults(ECHO))
                                            .join())
                    .hasCauseInstanceOf(RuntimeException.class);
        }
    }

    @Test
    void automaticRetryNeverSilentlyRetriesUnsafeOperations() {
        RpcIdentity identity =
                RpcIdentity.start(CLUSTER, UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd"));
        RpcOperationDescriptor mutation =
                new RpcOperationDescriptor(
                        101,
                        100,
                        100,
                        RpcRetryClass.NEVER,
                        RpcExecutionPolicy.STORAGE_WRITE,
                        Duration.ofSeconds(1));
        try (var client = PlaintextDevelopmentRpc.client(identity)) {
            assertThatThrownBy(
                            () ->
                                    client.call(
                                                    io.aetherdb.rpc.api.RpcEndpoint.of(
                                                            "127.0.0.1", 1),
                                                    mutation,
                                                    new byte[0],
                                                    new RpcCallOptions(
                                                            Duration.ofSeconds(1),
                                                            true,
                                                            RpcBackpressureMode.FAIL_FAST))
                                            .join())
                    .hasCauseInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("IDEMPOTENT");
        }
    }

    @Test
    void outboundAdmissionAcceptsWhenQueueHasCapacity() {
        var decision = PlaintextDevelopmentRpc.outboundAdmission(ECHO, 1024, 1, 0, false);

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.ACCEPTED);
        assertThat(decision.reasons()).isEmpty();
    }

    @Test
    void outboundAdmissionRejectsWhenPermitBudgetWouldBeExhausted() {
        var decision =
                PlaintextDevelopmentRpc.outboundAdmission(
                        ECHO, 1024, 1, 64 * 1024 - 1, false);

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
        assertThat(decision.reasons())
                .containsExactly("emergency limit reached: VIRTUAL_THREAD_INFLIGHT");
    }

    @Test
    void outboundAdmissionRejectsDrainingConnectionBeforeQueueEvaluation() {
        var decision = PlaintextDevelopmentRpc.outboundAdmission(ECHO, 1024, 1, 0, true);

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.DRAINING_REJECTED);
        assertThat(decision.reasons()).containsExactly("node is draining");
    }

    @Test
    void rpcTransportConfigurationUsesChapter32LimitsForOutboundAdmission() {
        RpcTransportConfiguration configuration =
                RpcTransportConfiguration.from(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.profile",
                                        "development",
                                        "aether.rpc.outbound_bytes",
                                        "1048576")));

        var accepted =
                PlaintextDevelopmentRpc.outboundAdmission(ECHO, 1024, 1, 1022, false, configuration);
        var rejected =
                PlaintextDevelopmentRpc.outboundAdmission(ECHO, 1024, 1, 1023, false, configuration);

        assertThat(configuration.outboundPermits()).isEqualTo(1024);
        assertThat(accepted.outcome()).isEqualTo(AdmissionOutcome.ACCEPTED);
        assertThat(rejected.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
    }

    @Test
    void inboundAdmissionAcceptsKnownAndUnknownOperationsWithinWindow() {
        var known = PlaintextDevelopmentRpc.inboundAdmission(ECHO, 1024, 0, 0, false);
        var unknown = PlaintextDevelopmentRpc.inboundAdmission(null, 1024, 0, 0, false);

        assertThat(known.outcome()).isEqualTo(AdmissionOutcome.ACCEPTED);
        assertThat(unknown.outcome()).isEqualTo(AdmissionOutcome.ACCEPTED);
    }

    @Test
    void inboundAdmissionRejectsWhenConnectionWindowWouldBeExhausted() {
        var decision =
                PlaintextDevelopmentRpc.inboundAdmission(
                        ECHO, 1, 64L * 1024 * 1024 - 1, 0, false);

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
        assertThat(decision.reasons()).containsExactly("emergency limit reached: RPC_INBOUND_BYTES");
    }

    @Test
    void inboundAdmissionRejectsWhenStreamLimitWouldBeExhausted() {
        var decision = PlaintextDevelopmentRpc.inboundAdmission(ECHO, 1, 0, 1023, false);

        assertThat(decision.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
        assertThat(decision.reasons()).containsExactly("emergency limit reached: RPC_INFLIGHT_STREAMS");
    }

    @Test
    void rpcTransportConfigurationUsesChapter32LimitsForInboundAdmission() {
        RpcTransportConfiguration configuration =
                RpcTransportConfiguration.from(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.profile",
                                        "development",
                                        "aether.rpc.inbound_bytes",
                                        "1048576",
                                        "aether.rpc.max_streams",
                                        "2")));

        var bytes =
                PlaintextDevelopmentRpc.inboundAdmission(
                        ECHO, 1, 1024L * 1024L - 1, 0, false, configuration);
        var streams = PlaintextDevelopmentRpc.inboundAdmission(ECHO, 1, 0, 2, false, configuration);

        assertThat(bytes.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
        assertThat(streams.outcome()).isEqualTo(AdmissionOutcome.RESOURCE_EXHAUSTED);
    }

    @Test
    void inboundAdmissionRejectsKnownOperationOverDeclaredRequestLimit() {
        assertThatThrownBy(() -> PlaintextDevelopmentRpc.inboundAdmission(ECHO, 4 * 1024 * 1024 + 1, 0, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("request exceeds operation limit");
    }
}
