package io.aetherdb.client;

import io.aetherdb.client.api.ClientProtocol;
import io.aetherdb.client.api.ClientGetRequest;
import io.aetherdb.client.api.ClientGetResponse;
import io.aetherdb.client.api.ClientScanRequest;
import io.aetherdb.client.api.ClientScanResponse;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.client.api.ClientWriteResponse;
import io.aetherdb.client.api.ClientWriteRequest;
import io.aetherdb.client.codec.ClientGetCodecV1;
import io.aetherdb.client.codec.ClientScanCodecV1;
import io.aetherdb.client.codec.ClientWriteCodecV1;
import io.aetherdb.client.codec.ClientWriteResponseCodecV1;
import io.aetherdb.rpc.api.RpcBackpressureMode;
import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcExecutionPolicy;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcResponse;
import io.aetherdb.rpc.api.RpcRetryClass;
import io.aetherdb.rpc.api.RpcStatus;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Minimal remote Java client facade for write submission over the pooled RPC transport. */
public final class RemoteAetherClient implements AutoCloseable {
    private static final int MAX_WRITE_REQUEST_BYTES = 32 * 1024 * 1024 + ClientWriteCodecV1.HEADER_BYTES;
    private static final RpcOperationDescriptor IDEMPOTENT_WRITE_OPERATION =
            new RpcOperationDescriptor(
                    ClientProtocol.CLIENT_WRITE,
                    MAX_WRITE_REQUEST_BYTES,
                    1024 * 1024,
                    RpcRetryClass.DEDUP_REQUIRED,
                    RpcExecutionPolicy.STORAGE_WRITE,
                    Duration.ofSeconds(10));
    private static final RpcOperationDescriptor PLAIN_WRITE_OPERATION =
            new RpcOperationDescriptor(
                    ClientProtocol.CLIENT_WRITE,
                    MAX_WRITE_REQUEST_BYTES,
                    1024 * 1024,
                    RpcRetryClass.NEVER,
                    RpcExecutionPolicy.STORAGE_WRITE,
                    Duration.ofSeconds(10));
    private static final RpcOperationDescriptor GET_OPERATION =
            new RpcOperationDescriptor(
                    ClientProtocol.CLIENT_GET,
                    65_536 + 64,
                    16 * 1024 * 1024 + 64,
                    RpcRetryClass.IDEMPOTENT,
                    RpcExecutionPolicy.STORAGE_READ,
                    Duration.ofSeconds(5));
    private static final RpcOperationDescriptor SCAN_OPEN_OPERATION =
            new RpcOperationDescriptor(
                    ClientProtocol.SCAN_OPEN,
                    140_000,
                    16 * 1024 * 1024 + 128,
                    RpcRetryClass.IDEMPOTENT,
                    RpcExecutionPolicy.STORAGE_READ,
                    Duration.ofSeconds(10));
    private static final RpcOperationDescriptor SCAN_NEXT_OPERATION =
            new RpcOperationDescriptor(
                    ClientProtocol.SCAN_NEXT,
                    140_000,
                    16 * 1024 * 1024 + 128,
                    RpcRetryClass.IDEMPOTENT,
                    RpcExecutionPolicy.STORAGE_READ,
                    Duration.ofSeconds(10));

    private final RemoteConnectionPool pool;
    private final RemoteEndpointResolver resolver;
    private final RemoteRetryPolicy retryPolicy;
    private final RpcCallOptions writeOptions;
    private final int maximumAttempts;

    /** Creates a remote client facade over an existing connection pool and resolver. */
    public RemoteAetherClient(
            RemoteConnectionPool pool,
            RemoteEndpointResolver resolver,
            RemoteRetryPolicy retryPolicy,
            RpcCallOptions writeOptions,
            int maximumAttempts) {
        this.pool = Objects.requireNonNull(pool, "pool");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.writeOptions = Objects.requireNonNull(writeOptions, "writeOptions");
        if (maximumAttempts <= 0 || maximumAttempts > 16) {
            throw new IllegalArgumentException("invalid maximum attempts");
        }
        this.maximumAttempts = maximumAttempts;
    }

    /** Creates conservative default write options for remote clients. */
    public static RpcCallOptions defaultWriteOptions() {
        return new RpcCallOptions(Duration.ofSeconds(10), true, RpcBackpressureMode.FAIL_FAST);
    }

    /** Submits a write, applying Chapter 37 retry rules for deduplicated requests. */
    public CompletableFuture<RemoteWriteResult> write(ClientWriteRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] body = ClientWriteCodecV1.encode(request);
        RpcOperationDescriptor operation =
                request.deduplicated() ? IDEMPOTENT_WRITE_OPERATION : PLAIN_WRITE_OPERATION;
        RpcEndpoint firstEndpoint = resolver.candidates().getFirst();
        return writeAttempt(request, operation, body, firstEndpoint, 1);
    }

    /** Submits one idempotent point read through the remote client protocol. */
    public CompletableFuture<RemoteReadResult> get(ClientGetRequest request) {
        Objects.requireNonNull(request, "request");
        byte[] body = ClientGetCodecV1.encodeRequest(request);
        RpcEndpoint firstEndpoint = resolver.candidates().getFirst();
        return getAttempt(request, body, firstEndpoint, 1);
    }

    /** Submits one scan page request through the remote client protocol. */
    public CompletableFuture<RemoteScanResult> scan(ClientScanRequest request) {
        Objects.requireNonNull(request, "request");
        RpcOperationDescriptor operation =
                request.pageToken().length == 0 ? SCAN_OPEN_OPERATION : SCAN_NEXT_OPERATION;
        byte[] body = ClientScanCodecV1.encodeRequest(request);
        RpcEndpoint firstEndpoint = resolver.candidates().getFirst();
        return scanAttempt(request, operation, body, firstEndpoint, 1);
    }

    /** Closes the underlying connection pool. */
    @Override
    public void close() {
        pool.close();
    }

    private CompletableFuture<RemoteWriteResult> writeAttempt(
            ClientWriteRequest request,
            RpcOperationDescriptor operation,
            byte[] body,
            RpcEndpoint endpoint,
            int attempt) {
        return pool.callOn(endpoint, operation, body, writeOptions)
                .handle(
                        (response, failure) -> {
                            if (failure != null) {
                                return new AttemptOutcome(
                                        ClientStatus.DEADLINE_EXCEEDED,
                                        null,
                                        null,
                                        0,
                                        0,
                                        0,
                                        failure.getMessage(),
                                        new byte[0]);
                            }
                            if (response.status() == RpcStatus.OK && response.body().length > 0) {
                                ClientWriteResponse writeResponse =
                                        ClientWriteResponseCodecV1.decode(response.body());
                                return new AttemptOutcome(
                                        writeResponse.status(),
                                        leaderHint(writeResponse),
                                        response,
                                        writeResponse.operationCount(),
                                        writeResponse.firstSequence(),
                                        writeResponse.lastSequence(),
                                        writeResponse.detail(),
                                        response.body());
                            }
                            return new AttemptOutcome(
                                    mapStatus(response.status()),
                                    null,
                                    response,
                                    0,
                                    0,
                                    0,
                                    response.errorDetail(),
                                    response.body());
                        })
                .thenCompose(
                        outcome -> {
                            RemoteRetryDecision decision =
                                    retryPolicy.decide(
                                            outcome.status,
                                            operation.retryClass(),
                                            endpoint,
                                            outcome.leaderHint,
                                            request.deduplicated(),
                                            attempt,
                                            maximumAttempts);
                            if (decision.action() == RetryAction.RETRY_SAME_ENDPOINT) {
                                return writeAttempt(request, operation, body, endpoint, attempt + 1);
                            }
                            if (decision.action() == RetryAction.RETRY_PREFERRED_LEADER) {
                                resolver.observeLeader(decision.nextEndpoint());
                                return writeAttempt(
                                        request, operation, body, decision.nextEndpoint(), attempt + 1);
                            }
                            return CompletableFuture.completedFuture(
                                    new RemoteWriteResult(
                                            outcome.status,
                                            request.commandId(),
                                            attempt,
                                            outcome.operationCount,
                                            outcome.firstSequence,
                                            outcome.lastSequence,
                                            outcome.body,
                                            outcome.detail == null ? "" : outcome.detail));
                        });
    }

    private CompletableFuture<RemoteReadResult> getAttempt(
            ClientGetRequest request, byte[] body, RpcEndpoint endpoint, int attempt) {
        return pool.callOn(endpoint, GET_OPERATION, body, writeOptions)
                .handle(
                        (response, failure) -> {
                            if (failure != null) {
                                return new ReadAttemptOutcome(
                                        ClientStatus.DEADLINE_EXCEEDED,
                                        failure.getMessage(),
                                        new byte[0]);
                            }
                            if (response.status() == RpcStatus.OK && response.body().length > 0) {
                                ClientGetResponse getResponse =
                                        ClientGetCodecV1.decodeResponse(response.body());
                                return new ReadAttemptOutcome(
                                        getResponse.status(),
                                        getResponse.detail(),
                                        getResponse.value());
                            }
                            return new ReadAttemptOutcome(
                                    mapStatus(response.status()), response.errorDetail(), new byte[0]);
                        })
                .thenCompose(
                        outcome -> {
                            RemoteRetryDecision decision =
                                    retryPolicy.decide(
                                            outcome.status,
                                            GET_OPERATION.retryClass(),
                                            endpoint,
                                            null,
                                            false,
                                            attempt,
                                            maximumAttempts);
                            if (decision.action() == RetryAction.RETRY_SAME_ENDPOINT) {
                                return getAttempt(request, body, endpoint, attempt + 1);
                            }
                            return CompletableFuture.completedFuture(
                                    new RemoteReadResult(
                                            outcome.status,
                                            attempt,
                                            outcome.value,
                                            outcome.detail == null ? "" : outcome.detail));
                        });
    }

    private CompletableFuture<RemoteScanResult> scanAttempt(
            ClientScanRequest request,
            RpcOperationDescriptor operation,
            byte[] body,
            RpcEndpoint endpoint,
            int attempt) {
        return pool.callOn(endpoint, operation, body, writeOptions)
                .handle(
                        (response, failure) -> {
                            if (failure != null) {
                                return new ScanAttemptOutcome(
                                        ClientStatus.DEADLINE_EXCEEDED,
                                        List.of(),
                                        new byte[0],
                                        failure.getMessage());
                            }
                            if (response.status() == RpcStatus.OK && response.body().length > 0) {
                                ClientScanResponse scanResponse =
                                        ClientScanCodecV1.decodeResponse(response.body());
                                return new ScanAttemptOutcome(
                                        scanResponse.status(),
                                        scanResponse.entries(),
                                        scanResponse.nextPageToken(),
                                        scanResponse.detail());
                            }
                            return new ScanAttemptOutcome(
                                    mapStatus(response.status()),
                                    List.of(),
                                    new byte[0],
                                    response.errorDetail());
                        })
                .thenCompose(
                        outcome -> {
                            RemoteRetryDecision decision =
                                    retryPolicy.decide(
                                            outcome.status,
                                            operation.retryClass(),
                                            endpoint,
                                            null,
                                            false,
                                            attempt,
                                            maximumAttempts);
                            if (decision.action() == RetryAction.RETRY_SAME_ENDPOINT) {
                                return scanAttempt(request, operation, body, endpoint, attempt + 1);
                            }
                            return CompletableFuture.completedFuture(
                                    new RemoteScanResult(
                                            outcome.status,
                                            attempt,
                                            outcome.entries,
                                            outcome.nextPageToken,
                                            outcome.detail == null ? "" : outcome.detail));
                        });
    }

    private static RpcEndpoint leaderHint(ClientWriteResponse response) {
        if (response.leaderHint() == null) return null;
        return new RpcEndpoint(
                response.leaderHint().host(),
                response.leaderHint().port(),
                response.leaderHint().expectedNodeId());
    }

    private static ClientStatus mapStatus(RpcStatus status) {
        return switch (status) {
            case OK -> ClientStatus.OK;
            case INVALID_ARGUMENT, PROTOCOL_ERROR -> ClientStatus.INVALID_ARGUMENT;
            case DEADLINE_EXCEEDED, CANCELLED -> ClientStatus.DEADLINE_EXCEEDED;
            case NOT_FOUND -> ClientStatus.NOT_FOUND;
            case RESOURCE_EXHAUSTED -> ClientStatus.RESOURCE_EXHAUSTED;
            case FAILED_PRECONDITION -> ClientStatus.INDETERMINATE;
            case UNAVAILABLE -> ClientStatus.NO_LEADER_KNOWN;
            case INTERNAL, ALREADY_EXISTS -> ClientStatus.ENGINE_FAILED;
            case UNAUTHENTICATED -> ClientStatus.UNAUTHENTICATED;
        };
    }

    private record AttemptOutcome(
            ClientStatus status,
            RpcEndpoint leaderHint,
            RpcResponse response,
            int operationCount,
            long firstSequence,
            long lastSequence,
            String detail,
            byte[] body) {}

    private record ReadAttemptOutcome(ClientStatus status, String detail, byte[] value) {}

    private record ScanAttemptOutcome(
            ClientStatus status, List<io.aetherdb.client.api.ClientScanEntry> entries, byte[] nextPageToken, String detail) {}
}
