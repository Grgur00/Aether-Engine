package io.aetherdb.client;

import io.aetherdb.api.typed.CollectionCapability;
import io.aetherdb.api.typed.CollectionDefinition;
import io.aetherdb.api.typed.ReadResult;
import io.aetherdb.api.typed.TypedAetherCollection;
import io.aetherdb.api.typed.TypedKeyValue;
import io.aetherdb.api.typed.TypedWriteResult;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.client.api.ClientGetRequest;
import io.aetherdb.client.api.ClientScanRequest;
import io.aetherdb.client.api.ClientWriteOperation;
import io.aetherdb.client.api.ClientWriteRequest;
import io.aetherdb.codec.TypedKeyEnvelope;
import io.aetherdb.codec.TypedValueEnvelope;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Typed remote collection adapter that reuses embedded typed key/value envelopes. */
public final class RemoteTypedCollection<K, V> implements TypedAetherCollection<K, V> {
    private final RemoteAetherClient client;
    private final CollectionDefinition<K, V> definition;

    /** Creates a typed remote handle for one validated collection definition. */
    public RemoteTypedCollection(RemoteAetherClient client, CollectionDefinition<K, V> definition) {
        this.client = Objects.requireNonNull(client, "client");
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    /** Returns the collection definition used for remote encoding. */
    @Override
    public CollectionDefinition<K, V> definition() {
        return definition;
    }

    /** Point reads require the Chapter 37 read response contract, which is not implemented yet. */
    @Override
    public ReadResult<V> get(K key) {
        RemoteReadResult result =
                client.get(new ClientGetRequest(0, TypedKeyEnvelope.encode(definition, key))).join();
        if (result.status() == ClientStatus.OK) {
            return new ReadResult.Found<>(
                    TypedValueEnvelope.decode(definition.valueCodec(), result.value()));
        }
        if (result.status() == ClientStatus.NOT_FOUND) {
            return new ReadResult.NotFound<>();
        }
        throw new IllegalStateException(
                result.detail().isBlank() ? result.status().name() : result.detail());
    }

    /** Encodes and submits one deduplicated typed put. */
    @Override
    public TypedWriteResult put(K key, V value) {
        ensurePointWrite();
        return submit(
                new ClientWriteOperation(
                        ClientWriteOperation.Type.PUT,
                        TypedKeyEnvelope.encode(definition, key),
                        TypedValueEnvelope.encode(definition.valueCodec(), value)));
    }

    /** Encodes and submits one deduplicated typed delete. */
    @Override
    public TypedWriteResult delete(K key) {
        ensurePointWrite();
        return submit(
                new ClientWriteOperation(
                        ClientWriteOperation.Type.DELETE,
                        TypedKeyEnvelope.encode(definition, key),
                        new byte[0]));
    }

    /** Materializes all remote entries in key order using paged scan responses. */
    @Override
    public List<TypedKeyValue<K, V>> scanAll() {
        if (!definition.capabilities().contains(CollectionCapability.RANGE_SCAN)) {
            throw new UnsupportedOperationException("collection does not support range scans");
        }
        byte[] token = new byte[0];
        List<TypedKeyValue<K, V>> values = new ArrayList<>();
        do {
            RemoteScanResult page =
                    client.scan(
                                    new ClientScanRequest(
                                            0,
                                            TypedKeyEnvelope.prefix(definition),
                                            TypedKeyEnvelope.prefixEnd(definition),
                                            1024,
                                            token))
                            .join();
            if (page.status() != ClientStatus.OK) {
                throw new IllegalStateException(
                        page.detail().isBlank() ? page.status().name() : page.detail());
            }
            for (var entry : page.entries()) {
                values.add(
                        new TypedKeyValue<>(
                                TypedKeyEnvelope.decode(definition, entry.key()),
                                TypedValueEnvelope.decode(definition.valueCodec(), entry.value())));
            }
            token = page.nextPageToken();
        } while (token.length != 0);
        return List.copyOf(values);
    }

    private TypedWriteResult submit(ClientWriteOperation operation) {
        UUID commandId = UUID.randomUUID();
        RemoteWriteResult result =
                client.write(ClientWriteRequest.deduplicated(0, commandId, List.of(operation))).join();
        if (result.status() == ClientStatus.OK) {
            return new TypedWriteResult.Applied(
                    result.commandId(),
                    result.operationCount(),
                    result.firstSequence(),
                    result.lastSequence());
        }
        if (result.status() == ClientStatus.INDETERMINATE) {
            return new TypedWriteResult.Indeterminate(
                    result.commandId(),
                    "REMOTE_WRITE",
                    result.detail().isBlank() ? "check command id before retrying" : result.detail());
        }
        return new TypedWriteResult.Rejected(result.commandId(), rejectionReason(result), retryable(result.status()));
    }

    private void ensurePointWrite() {
        if (!definition.capabilities().contains(CollectionCapability.POINT_WRITE)) {
            throw new UnsupportedOperationException("collection does not support point writes");
        }
    }

    private static String rejectionReason(RemoteWriteResult result) {
        return result.detail().isBlank() ? result.status().name() : result.detail();
    }

    private static boolean retryable(ClientStatus status) {
        return status == ClientStatus.RESOURCE_EXHAUSTED
                || status == ClientStatus.DEADLINE_EXCEEDED
                || status == ClientStatus.NO_LEADER_KNOWN
                || status == ClientStatus.LEADER_NOT_READY;
    }
}
