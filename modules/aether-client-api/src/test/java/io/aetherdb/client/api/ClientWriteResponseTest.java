package io.aetherdb.client.api;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ClientWriteResponseTest {
    private static final UUID COMMAND_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void appliedResponsesRequireSequenceRange() {
        assertThatThrownBy(
                        () ->
                                new ClientWriteResponse(
                                        ClientStatus.OK, COMMAND_ID, 1, 0, 0, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void leaderRedirectsRequireEndpointHint() {
        assertThatThrownBy(
                        () ->
                                new ClientWriteResponse(
                                        ClientStatus.NOT_LEADER, COMMAND_ID, 0, 0, 0, null, ""))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
