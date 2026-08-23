package io.aetherdb.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.rpc.api.RpcEndpoint;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class RemoteEndpointResolverTest {
    @Test
    void candidatesPreferObservedLeaderWithoutLosingStableEndpoints() {
        RpcEndpoint first = RpcEndpoint.of("127.0.0.1", 1001);
        RpcEndpoint second = RpcEndpoint.of("127.0.0.1", 1002);
        RemoteEndpointResolver resolver = new RemoteEndpointResolver(List.of(first, second), 4);

        resolver.observeLeader(second);

        assertThat(resolver.candidates()).containsExactly(second, first);
    }

    @Test
    void observedLeaderCanBeDiscoveredAndLaterCleared() {
        RpcEndpoint first = RpcEndpoint.of("127.0.0.1", 1001);
        RpcEndpoint discovered =
                new RpcEndpoint("127.0.0.1", 1003, UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"));
        RemoteEndpointResolver resolver = new RemoteEndpointResolver(List.of(first), 2);

        resolver.observeLeader(discovered);
        assertThat(resolver.candidates()).containsExactly(discovered, first);

        resolver.clearLeader(discovered);
        assertThat(resolver.candidates()).containsExactly(first, discovered);
    }

    @Test
    void rejectsEmptyOrOverlargeEndpointSets() {
        assertThatThrownBy(() -> new RemoteEndpointResolver(List.of(), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one endpoint");
        assertThatThrownBy(
                        () ->
                                new RemoteEndpointResolver(
                                        List.of(
                                                RpcEndpoint.of("127.0.0.1", 1001),
                                                RpcEndpoint.of("127.0.0.1", 1002)),
                                        1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds configured maximum");
    }
}
