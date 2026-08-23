package io.aetherdb.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.api.typed.KeyCodec;
import io.aetherdb.api.typed.OrderedKeyCodec;
import io.aetherdb.api.typed.TypedWriteResult;
import io.aetherdb.api.typed.ValueCodec;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.client.api.ClientGetResponse;
import io.aetherdb.client.api.ClientScanEntry;
import io.aetherdb.client.api.ClientScanResponse;
import io.aetherdb.client.api.ClientWriteResponse;
import io.aetherdb.client.codec.ClientGetCodecV1;
import io.aetherdb.client.codec.ClientScanCodecV1;
import io.aetherdb.client.codec.ClientWriteCodecV1;
import io.aetherdb.client.codec.ClientWriteResponseCodecV1;
import io.aetherdb.codec.TypedKeyEnvelope;
import io.aetherdb.codec.TypedValueEnvelope;
import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcClient;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcResponse;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

class RemoteTypedCollectionTest {
    private static final RpcEndpoint ENDPOINT = RpcEndpoint.of("127.0.0.1", 1001);
    private static final UUID INVOCATION_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void putEncodesTypedEnvelopeAndReturnsAppliedResult() {
        CapturingRpcClient transport = new CapturingRpcClient(appliedBody(1, 4, 4));
        RemoteTypedCollection<String, String> collection = collection(transport);

        TypedWriteResult result = collection.put("key-1", "value-1");

        assertThat(result).isInstanceOf(TypedWriteResult.Applied.class);
        TypedWriteResult.Applied applied = (TypedWriteResult.Applied) result;
        assertThat(applied.operationCount()).isEqualTo(1);
        assertThat(applied.firstSequence()).isEqualTo(4);
        var request = ClientWriteCodecV1.decode(transport.lastBody);
        assertThat(request.deduplicated()).isTrue();
        assertThat(request.operations()).hasSize(1);
        assertThat(request.operations().getFirst().type()).isEqualTo(io.aetherdb.client.api.ClientWriteOperation.Type.PUT);
        assertThat(TypedKeyEnvelope.decode(collection.definition(), request.operations().getFirst().key()))
                .isEqualTo("key-1");
        assertThat(TypedValueEnvelope.decode(collection.definition().valueCodec(), request.operations().getFirst().value()))
                .isEqualTo("value-1");
    }

    @Test
    void rejectedRemoteWriteMapsToTypedRejected() {
        CapturingRpcClient transport = new CapturingRpcClient(rejectedBody("busy"));
        RemoteTypedCollection<String, String> collection = collection(transport);

        TypedWriteResult result = collection.delete("key-1");

        assertThat(result).isInstanceOf(TypedWriteResult.Rejected.class);
        TypedWriteResult.Rejected rejected = (TypedWriteResult.Rejected) result;
        assertThat(rejected.reason()).isEqualTo("busy");
        assertThat(rejected.retryable()).isTrue();
    }

    @Test
    void getDecodesTypedValueEnvelopeAndNotFoundMapsToReadResult() {
        byte[] foundValue = TypedValueEnvelope.encode(definition().valueCodec(), "value-1");
        CapturingRpcClient transport =
                new CapturingRpcClient(
                        ClientGetCodecV1.encodeResponse(
                                new ClientGetResponse(ClientStatus.OK, foundValue, "")),
                        ClientGetCodecV1.encodeResponse(
                                new ClientGetResponse(ClientStatus.NOT_FOUND, new byte[0], "")));
        RemoteTypedCollection<String, String> collection = collection(transport);

        assertThat(collection.get("key-1").requireValue()).isEqualTo("value-1");
        assertThat(collection.get("missing").value()).isEmpty();
    }

    @Test
    void scanAllDecodesPagedTypedEntries() {
        var definition = definitionWithRangeScan();
        CapturingRpcClient transport =
                new CapturingRpcClient(
                        ClientScanCodecV1.encodeResponse(
                                new ClientScanResponse(
                                        ClientStatus.OK,
                                        List.of(scanEntry(definition, "a", "one")),
                                        new byte[] {1},
                                        "")),
                        ClientScanCodecV1.encodeResponse(
                                new ClientScanResponse(
                                        ClientStatus.OK,
                                        List.of(scanEntry(definition, "b", "two")),
                                        new byte[0],
                                        "")));
        RemoteTypedCollection<String, String> collection = collection(transport, definition);

        List<io.aetherdb.api.typed.TypedKeyValue<String, String>> values = collection.scanAll();

        assertThat(values).extracting("key").containsExactly("a", "b");
        assertThat(values).extracting("value").containsExactly("one", "two");
    }

    private static RemoteTypedCollection<String, String> collection(CapturingRpcClient transport) {
        return collection(transport, definition());
    }

    private static RemoteTypedCollection<String, String> collection(
            CapturingRpcClient transport, CollectionDefinition<String, String> definition) {
        RemoteEndpointResolver resolver = new RemoteEndpointResolver(List.of(ENDPOINT), 1);
        RemoteAetherClient client =
                new RemoteAetherClient(
                        new RemoteConnectionPool(
                                transport, resolver, new RemoteClientIdentity("app/service"), 4, 4),
                        resolver,
                        new RemoteRetryPolicy(),
                        RemoteAetherClient.defaultWriteOptions(),
                        1);
        return new RemoteTypedCollection<>(client, definition);
    }

    private static CollectionDefinition<String, String> definition() {
        return CollectionDefinition.of(
                CollectionId.fromName("profiles"), "profiles", new StringKeyCodec(), new StringValueCodec());
    }

    private static CollectionDefinition<String, String> definitionWithRangeScan() {
        return new CollectionDefinition<>(
                CollectionId.fromName("profiles"),
                "profiles",
                new OrderedStringKeyCodec(),
                new StringValueCodec(),
                java.util.Set.of(
                        io.aetherdb.api.typed.CollectionCapability.POINT_READ,
                        io.aetherdb.api.typed.CollectionCapability.POINT_WRITE,
                        io.aetherdb.api.typed.CollectionCapability.RANGE_SCAN));
    }

    private static ClientScanEntry scanEntry(
            CollectionDefinition<String, String> definition, String key, String value) {
        return new ClientScanEntry(
                TypedKeyEnvelope.encode(definition, key),
                TypedValueEnvelope.encode(definition.valueCodec(), value));
    }

    private static byte[] appliedBody(int operations, long firstSequence, long lastSequence) {
        return ClientWriteResponseCodecV1.encode(
                new ClientWriteResponse(
                        ClientStatus.OK, INVOCATION_ID, operations, firstSequence, lastSequence, null, ""));
    }

    private static byte[] rejectedBody(String detail) {
        return ClientWriteResponseCodecV1.encode(
                new ClientWriteResponse(
                        ClientStatus.RESOURCE_EXHAUSTED, INVOCATION_ID, 0, 0, 0, null, detail));
    }

    private static final class CapturingRpcClient implements RpcClient {
        private final Queue<byte[]> responseBodies;
        byte[] lastBody;

        CapturingRpcClient(byte[] responseBody, byte[]... moreResponseBodies) {
            this.responseBodies = new ArrayDeque<>();
            this.responseBodies.add(responseBody);
            this.responseBodies.addAll(Arrays.asList(moreResponseBodies));
        }

        @Override
        public CompletableFuture<RpcResponse> call(
                RpcEndpoint peer,
                RpcOperationDescriptor operation,
                byte[] body,
                RpcCallOptions options) {
            lastBody = body.clone();
            return CompletableFuture.completedFuture(RpcResponse.ok(INVOCATION_ID, responseBodies.remove()));
        }

        @Override
        public void close() {}
    }

    private static class StringKeyCodec implements KeyCodec<String> {
        @Override
        public String codecId() {
            return "string-key";
        }

        @Override
        public int encodingVersion() {
            return 1;
        }

        @Override
        public int maximumEncodedSize() {
            return 256;
        }

        @Override
        public byte[] fingerprint() {
            return RemoteTypedCollectionTest.fingerprint(1);
        }

        @Override
        public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(byte[] encoded) {
            return new String(encoded, StandardCharsets.UTF_8);
        }
    }

    private static final class OrderedStringKeyCodec extends StringKeyCodec implements OrderedKeyCodec<String> {
        @Override
        public java.util.Comparator<String> comparator() {
            return java.util.Comparator.naturalOrder();
        }
    }

    private static final class StringValueCodec implements ValueCodec<String> {
        @Override
        public UUID schemaId() {
            return UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        }

        @Override
        public int currentSchemaVersion() {
            return 1;
        }

        @Override
        public int maximumEncodedSize(String value) {
            return 256;
        }

        @Override
        public byte[] fingerprint() {
            return RemoteTypedCollectionTest.fingerprint(2);
        }

        @Override
        public byte[] encode(String value) {
            return value.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public String decode(int schemaVersion, byte[] encoded) {
            return new String(encoded, StandardCharsets.UTF_8);
        }
    }

    private static byte[] fingerprint(int marker) {
        byte[] value = new byte[32];
        Arrays.fill(value, (byte) marker);
        return value;
    }
}
