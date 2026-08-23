package io.aetherdb.client;

import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcClient;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcResponse;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Bounded transport-neutral remote connection pool.
 *
 * <p>The underlying {@link RpcClient} owns concrete sockets/channels. This class provides the
 * client SDK's endpoint and authenticated-identity pool boundary: endpoint selection, in-flight
 * admission, and graceful shutdown behavior.
 */
public final class RemoteConnectionPool implements AutoCloseable {
    private final RpcClient transport;
    private final RemoteEndpointResolver resolver;
    private final RemoteClientIdentity identity;
    private final int maximumInflightPerEndpoint;
    private final int maximumInflightTotal;
    private final Map<RpcEndpoint, Integer> inflightByEndpoint = new HashMap<>();
    private int totalInflight;
    private boolean closed;

    /** Creates a bounded pool for one authenticated identity. */
    public RemoteConnectionPool(
            RpcClient transport,
            RemoteEndpointResolver resolver,
            RemoteClientIdentity identity,
            int maximumInflightPerEndpoint,
            int maximumInflightTotal) {
        this.transport = Objects.requireNonNull(transport, "transport");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.identity = Objects.requireNonNull(identity, "identity");
        if (maximumInflightPerEndpoint <= 0 || maximumInflightTotal <= 0) {
            throw new IllegalArgumentException("inflight limits must be positive");
        }
        if (maximumInflightTotal < maximumInflightPerEndpoint) {
            throw new IllegalArgumentException("total inflight limit must cover one endpoint limit");
        }
        this.maximumInflightPerEndpoint = maximumInflightPerEndpoint;
        this.maximumInflightTotal = maximumInflightTotal;
    }

    /** Submits one call through the selected pooled endpoint. */
    public CompletableFuture<RpcResponse> call(
            RpcOperationDescriptor operation, byte[] body, RpcCallOptions options) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(options, "options");

        RpcEndpoint endpoint;
        try {
            endpoint = reserveEndpoint();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<RpcResponse> response;
        try {
            response = transport.call(endpoint, operation, body, options);
        } catch (RuntimeException exception) {
            releaseEndpoint(endpoint);
            return CompletableFuture.failedFuture(exception);
        }
        if (response == null) {
            releaseEndpoint(endpoint);
            return CompletableFuture.failedFuture(new IllegalStateException("transport returned null future"));
        }
        return response.whenComplete((ignored, failure) -> releaseEndpoint(endpoint));
    }

    /** Submits one call to a specific resolver candidate, used after leader redirect decisions. */
    public CompletableFuture<RpcResponse> callOn(
            RpcEndpoint endpoint,
            RpcOperationDescriptor operation,
            byte[] body,
            RpcCallOptions options) {
        Objects.requireNonNull(endpoint, "endpoint");
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(options, "options");
        try {
            reserveEndpoint(endpoint);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }

        CompletableFuture<RpcResponse> response;
        try {
            response = transport.call(endpoint, operation, body, options);
        } catch (RuntimeException exception) {
            releaseEndpoint(endpoint);
            return CompletableFuture.failedFuture(exception);
        }
        if (response == null) {
            releaseEndpoint(endpoint);
            return CompletableFuture.failedFuture(new IllegalStateException("transport returned null future"));
        }
        return response.whenComplete((ignored, failure) -> releaseEndpoint(endpoint));
    }

    /** Authenticated identity this pool is keyed by. */
    public RemoteClientIdentity identity() {
        return identity;
    }

    /** Current in-flight calls for an endpoint, exposed for deterministic pool tests and metrics. */
    public synchronized int inflight(RpcEndpoint endpoint) {
        return inflightByEndpoint.getOrDefault(endpoint, 0);
    }

    /** Total in-flight calls across all endpoints. */
    public synchronized int totalInflight() {
        return totalInflight;
    }

    /** Closes the pool and drains the underlying transport. */
    @Override
    public void close() {
        synchronized (this) {
            closed = true;
        }
        transport.close();
    }

    private synchronized RpcEndpoint reserveEndpoint() {
        if (closed) throw new IllegalStateException("remote connection pool is closed");
        if (totalInflight >= maximumInflightTotal) {
            throw new IllegalStateException("remote connection pool total inflight limit exhausted");
        }

        RpcEndpoint selected = null;
        int selectedInflight = Integer.MAX_VALUE;
        for (RpcEndpoint candidate : resolver.candidates()) {
            int candidateInflight = inflightByEndpoint.getOrDefault(candidate, 0);
            if (candidateInflight < maximumInflightPerEndpoint
                    && candidateInflight < selectedInflight) {
                selected = candidate;
                selectedInflight = candidateInflight;
            }
        }
        if (selected == null) {
            throw new IllegalStateException("remote connection pool endpoint inflight limit exhausted");
        }
        inflightByEndpoint.put(selected, selectedInflight + 1);
        totalInflight++;
        return selected;
    }

    private synchronized void reserveEndpoint(RpcEndpoint endpoint) {
        if (closed) throw new IllegalStateException("remote connection pool is closed");
        if (!resolver.candidates().contains(endpoint)) {
            throw new IllegalArgumentException("endpoint is not a resolver candidate");
        }
        if (totalInflight >= maximumInflightTotal) {
            throw new IllegalStateException("remote connection pool total inflight limit exhausted");
        }
        int current = inflightByEndpoint.getOrDefault(endpoint, 0);
        if (current >= maximumInflightPerEndpoint) {
            throw new IllegalStateException("remote connection pool endpoint inflight limit exhausted");
        }
        inflightByEndpoint.put(endpoint, current + 1);
        totalInflight++;
    }

    private synchronized void releaseEndpoint(RpcEndpoint endpoint) {
        int current = inflightByEndpoint.getOrDefault(endpoint, 0);
        if (current <= 1) {
            inflightByEndpoint.remove(endpoint);
        } else {
            inflightByEndpoint.put(endpoint, current - 1);
        }
        if (totalInflight > 0) totalInflight--;
    }
}
