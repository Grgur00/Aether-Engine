package io.aetherdb.client.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

class ClientWriteRequestTest {
    @Test
    void legacyConstructorCreatesNonDeduplicatedRequest() {
        ClientWriteRequest request = new ClientWriteRequest(7, List.of(put()));

        assertThat(request.commandId()).isEqualTo(ClientWriteRequest.NO_COMMAND_ID);
        assertThat(request.deduplicated()).isFalse();
    }

    @Test
    void deduplicatedRequestsRequireNonZeroCommandId() {
        UUID commandId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

        ClientWriteRequest request = ClientWriteRequest.deduplicated(7, commandId, List.of(put()));

        assertThat(request.commandId()).isEqualTo(commandId);
        assertThat(request.deduplicated()).isTrue();
        assertThatThrownBy(
                        () ->
                                ClientWriteRequest.deduplicated(
                                        7, ClientWriteRequest.NO_COMMAND_ID, List.of(put())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ClientWriteRequest(7, commandId, false, List.of(put())))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static ClientWriteOperation put() {
        return new ClientWriteOperation(ClientWriteOperation.Type.PUT, new byte[] {1}, new byte[] {2});
    }
}
