package io.aetherdb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.client.api.ClientEndpointHint;
import io.aetherdb.client.api.ClientGetRequest;
import io.aetherdb.client.api.ClientGetResponse;
import io.aetherdb.client.api.ClientProtocol;
import io.aetherdb.client.api.ClientScanEntry;
import io.aetherdb.client.api.ClientScanRequest;
import io.aetherdb.client.api.ClientScanResponse;
import io.aetherdb.client.api.ClientWriteOperation;
import io.aetherdb.client.api.ClientWriteRequest;
import io.aetherdb.client.api.ClientWriteResponse;
import io.aetherdb.client.codec.ClientGetCodecV1;
import io.aetherdb.client.codec.ClientScanCodecV1;
import io.aetherdb.client.codec.ClientWriteResponseCodecV1;
import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcClient;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcResponse;
import io.aetherdb.rpc.api.RpcRetryClass;
import io.aetherdb.rpc.api.RpcStatus;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class RemoteAetherClientTest {
    private static final RpcEndpoint ENDPOINT = RpcEndpoint.of("127.0.0.1", 1001);
    private static final RpcEndpoint LEADER = RpcEndpoint.of("127.0.0.1", 1002);
    private static final UUID COMMAND_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void deduplicatedWriteRetriesTransientFailureAndReturnsOk() {
        ScriptedRpcClient transport =
                new ScriptedRpcClient(
                        List.of(
                                new RpcResponse(RpcStatus.RESOURCE_EXHAUSTED, COMMAND_ID, new byte[0], "busy"),
                                RpcResponse.ok(COMMAND_ID, appliedBody(2, 10, 11))));
        RemoteAetherClient client = client(transport, 3, List.of(ENDPOINT));

        RemoteWriteResult result =
                client.write(ClientWriteRequest.deduplicated(1, COMMAND_ID, List.of(put()))).join();

        assertThat(result.status()).isEqualTo(ClientStatus.OK);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.commandId()).isEqualTo(COMMAND_ID);
        assertThat(transport.retryClasses).containsExactly(RpcRetryClass.DEDUP_REQUIRED, RpcRetryClass.DEDUP_REQUIRED);
    }

    @Test
    void plainWriteDoesNotRetryTransientFailure() {
        ScriptedRpcClient transport =
                new ScriptedRpcClient(
                        List.of(
                                new RpcResponse(
                                        RpcStatus.RESOURCE_EXHAUSTED, COMMAND_ID, new byte[0], "busy"),
                                RpcResponse.ok(COMMAND_ID, appliedBody(1, 1, 1))));
        RemoteAetherClient client = client(transport, 3, List.of(ENDPOINT));

        RemoteWriteResult result = client.write(new ClientWriteRequest(1, List.of(put()))).join();

        assertThat(result.status()).isEqualTo(ClientStatus.RESOURCE_EXHAUSTED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(transport.retryClasses).containsExactly(RpcRetryClass.NEVER);
    }

    @Test
    void leaderRedirectBodyUpdatesResolverAndRetriesLeader() {
        ScriptedRpcClient transport =
                new ScriptedRpcClient(
                        List.of(
                                RpcResponse.ok(COMMAND_ID, redirectBody(LEADER)),
                                RpcResponse.ok(COMMAND_ID, appliedBody(1, 20, 20))));
        RemoteAetherClient client = client(transport, 3, List.of(ENDPOINT, LEADER));

        RemoteWriteResult result =
                client.write(ClientWriteRequest.deduplicated(1, COMMAND_ID, List.of(put()))).join();

        assertThat(result.status()).isEqualTo(ClientStatus.OK);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(transport.endpoints).containsExactly(ENDPOINT, LEADER);
    }

    @Test
    void getDecodesReadResponseBody() {
        ScriptedRpcClient transport =
                new ScriptedRpcClient(
                        List.of(
                                RpcResponse.ok(
                                        COMMAND_ID,
                                        ClientGetCodecV1.encodeResponse(
                                                new ClientGetResponse(
                                                        ClientStatus.OK, new byte[] {5, 6}, "")))));
        RemoteAetherClient client = client(transport, 1, List.of(ENDPOINT));

        RemoteReadResult result = client.get(new ClientGetRequest(1, new byte[] {1, 2})).join();

        assertThat(result.status()).isEqualTo(ClientStatus.OK);
        assertThat(result.value()).containsExactly(5, 6);
        assertThat(transport.retryClasses).containsExactly(RpcRetryClass.IDEMPOTENT);
    }

    @Test
    void scanUsesOpenThenNextAndDecodesPages() {
        ScriptedRpcClient transport =
                new ScriptedRpcClient(
                        List.of(
                                RpcResponse.ok(
                                        COMMAND_ID,
                                        ClientScanCodecV1.encodeResponse(
                                                new ClientScanResponse(
                                                        ClientStatus.OK,
                                                        List.of(new ClientScanEntry(new byte[] {1}, new byte[] {2})),
                                                        new byte[] {9},
                                                        ""))),
                                RpcResponse.ok(
                                        COMMAND_ID,
                                        ClientScanCodecV1.encodeResponse(
                                                new ClientScanResponse(
                                                        ClientStatus.OK,
                                                        List.of(new ClientScanEntry(new byte[] {3}, new byte[] {4})),
                                                        new byte[0],
                                                        "")))));
        RemoteAetherClient client = client(transport, 1, List.of(ENDPOINT));

        RemoteScanResult first =
                client.scan(new ClientScanRequest(1, new byte[] {1}, new byte[] {9}, 128, new byte[0]))
                        .join();
        RemoteScanResult second =
                client.scan(new ClientScanRequest(1, new byte[] {1}, new byte[] {9}, 128, first.nextPageToken()))
                        .join();

        assertThat(first.entries().getFirst().value()).containsExactly(2);
        assertThat(second.entries().getFirst().value()).containsExactly(4);
        assertThat(transport.operationCodes)
                .containsExactly(ClientProtocol.SCAN_OPEN, ClientProtocol.SCAN_NEXT);
    }

    private static RemoteAetherClient client(
            ScriptedRpcClient transport, int maximumAttempts, List<RpcEndpoint> endpoints) {
        RemoteEndpointResolver resolver = new RemoteEndpointResolver(endpoints, endpoints.size());
        return new RemoteAetherClient(
                new RemoteConnectionPool(
                        transport, resolver, new RemoteClientIdentity("app/service"), 4, 4),
                resolver,
                new RemoteRetryPolicy(),
                RemoteAetherClient.defaultWriteOptions(),
                maximumAttempts);
    }

    private static byte[] appliedBody(int operations, long firstSequence, long lastSequence) {
        return ClientWriteResponseCodecV1.encode(
                new ClientWriteResponse(
                        ClientStatus.OK, COMMAND_ID, operations, firstSequence, lastSequence, null, ""));
    }

    private static byte[] redirectBody(RpcEndpoint leader) {
        return ClientWriteResponseCodecV1.encode(
                new ClientWriteResponse(
                        ClientStatus.NOT_LEADER,
                        COMMAND_ID,
                        0,
                        0,
                        0,
                        new ClientEndpointHint(
                                leader.host(), leader.port(), leader.expectedNodeId()),
                        "redirect"));
    }

    private static ClientWriteOperation put() {
        return new ClientWriteOperation(ClientWriteOperation.Type.PUT, new byte[] {1}, new byte[] {2});
    }

    private static final class ScriptedRpcClient implements RpcClient {
        private final Queue<RpcResponse> responses;
        final List<RpcEndpoint> endpoints = new ArrayList<>();
        final List<RpcRetryClass> retryClasses = new ArrayList<>();
        final List<Integer> operationCodes = new ArrayList<>();

        ScriptedRpcClient(List<RpcResponse> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public CompletableFuture<RpcResponse> call(
                RpcEndpoint peer,
                RpcOperationDescriptor operation,
                byte[] body,
                RpcCallOptions options) {
            endpoints.add(peer);
            retryClasses.add(operation.retryClass());
            operationCodes.add(operation.operationCode());
            return CompletableFuture.completedFuture(responses.remove());
        }

        @Override
        public void close() {}
    }
}
