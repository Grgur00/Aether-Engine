package io.aetherdb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.rpc.api.RpcBackpressureMode;
import io.aetherdb.rpc.api.RpcCallOptions;
import io.aetherdb.rpc.api.RpcClient;
import io.aetherdb.rpc.api.RpcEndpoint;
import io.aetherdb.rpc.api.RpcExecutionPolicy;
import io.aetherdb.rpc.api.RpcOperationDescriptor;
import io.aetherdb.rpc.api.RpcResponse;
import io.aetherdb.rpc.api.RpcRetryClass;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

class RemoteConnectionPoolTest {
    private static final RpcEndpoint FIRST = RpcEndpoint.of("127.0.0.1", 1001);
    private static final RpcEndpoint SECOND = RpcEndpoint.of("127.0.0.1", 1002);
    private static final RpcOperationDescriptor OPERATION =
            new RpcOperationDescriptor(
                    1,
                    1024,
                    1024,
                    RpcRetryClass.IDEMPOTENT,
                    RpcExecutionPolicy.CONTROL,
                    Duration.ofSeconds(1));
    private static final RpcCallOptions OPTIONS =
            new RpcCallOptions(Duration.ofSeconds(1), false, RpcBackpressureMode.FAIL_FAST);

    @Test
    void selectsLeastBusyEndpointAndReleasesOnCompletion() {
        FakeRpcClient transport = new FakeRpcClient();
        RemoteConnectionPool pool = pool(transport, 1, 2);

        CompletableFuture<RpcResponse> first = pool.call(OPERATION, new byte[] {1}, OPTIONS);
        CompletableFuture<RpcResponse> second = pool.call(OPERATION, new byte[] {2}, OPTIONS);

        assertThat(transport.endpoints).containsExactly(FIRST, SECOND);
        assertThat(pool.inflight(FIRST)).isEqualTo(1);
        assertThat(pool.inflight(SECOND)).isEqualTo(1);

        transport.responses.get(0).complete(ok());

        RpcResponse completed = first.join();
        assertThat(completed.status()).isEqualTo(io.aetherdb.rpc.api.RpcStatus.OK);
        assertThat(completed.body()).containsExactly(9);
        assertThat(pool.inflight(FIRST)).isZero();
        assertThat(pool.inflight(SECOND)).isEqualTo(1);

        pool.call(OPERATION, new byte[] {3}, OPTIONS);

        assertThat(transport.endpoints).containsExactly(FIRST, SECOND, FIRST);
        second.cancel(true);
    }

    @Test
    void rejectsCallsWhenPoolBoundsAreExhausted() {
        FakeRpcClient transport = new FakeRpcClient();
        RemoteConnectionPool pool = pool(transport, 1, 1);

        pool.call(OPERATION, new byte[] {1}, OPTIONS);
        CompletableFuture<RpcResponse> rejected = pool.call(OPERATION, new byte[] {2}, OPTIONS);

        assertThatThrownBy(rejected::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("total inflight limit exhausted");
        assertThat(transport.endpoints).containsExactly(FIRST);
    }

    @Test
    void closeRejectsNewCallsAndClosesTransport() {
        FakeRpcClient transport = new FakeRpcClient();
        RemoteConnectionPool pool = pool(transport, 1, 2);

        pool.close();
        CompletableFuture<RpcResponse> rejected = pool.call(OPERATION, new byte[] {1}, OPTIONS);

        assertThat(transport.closed).isTrue();
        assertThatThrownBy(rejected::join)
                .isInstanceOf(CompletionException.class)
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    void callOnUsesRequestedResolverCandidate() {
        FakeRpcClient transport = new FakeRpcClient();
        RemoteConnectionPool pool = pool(transport, 1, 2);

        CompletableFuture<RpcResponse> response = pool.callOn(SECOND, OPERATION, new byte[] {1}, OPTIONS);

        assertThat(transport.endpoints).containsExactly(SECOND);
        transport.responses.getFirst().complete(ok());
        assertThat(response.join().body()).containsExactly(9);
    }

    private static RemoteConnectionPool pool(
            FakeRpcClient transport, int maximumInflightPerEndpoint, int maximumInflightTotal) {
        return new RemoteConnectionPool(
                transport,
                new RemoteEndpointResolver(List.of(FIRST, SECOND), 2),
                new RemoteClientIdentity("app/service"),
                maximumInflightPerEndpoint,
                maximumInflightTotal);
    }

    private static RpcResponse ok() {
        return RpcResponse.ok(UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"), new byte[] {9});
    }

    private static final class FakeRpcClient implements RpcClient {
        final List<RpcEndpoint> endpoints = new ArrayList<>();
        final List<CompletableFuture<RpcResponse>> responses = new ArrayList<>();
        boolean closed;

        @Override
        public CompletableFuture<RpcResponse> call(
                RpcEndpoint peer,
                RpcOperationDescriptor operation,
                byte[] body,
                RpcCallOptions options) {
            endpoints.add(peer);
            CompletableFuture<RpcResponse> response = new CompletableFuture<>();
            responses.add(response);
            return response;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
