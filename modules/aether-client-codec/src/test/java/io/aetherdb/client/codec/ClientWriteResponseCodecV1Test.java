package io.aetherdb.client.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.client.api.ClientEndpointHint;
import io.aetherdb.client.api.ClientStatus;
import io.aetherdb.client.api.ClientWriteResponse;

import org.junit.jupiter.api.Test;

import java.util.UUID;

class ClientWriteResponseCodecV1Test {
    private static final UUID COMMAND_ID = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");

    @Test
    void appliedResponseRoundTrips() {
        ClientWriteResponse response =
                new ClientWriteResponse(ClientStatus.OK, COMMAND_ID, 2, 10, 11, null, "");

        ClientWriteResponse decoded = ClientWriteResponseCodecV1.decode(ClientWriteResponseCodecV1.encode(response));

        assertThat(decoded.status()).isEqualTo(ClientStatus.OK);
        assertThat(decoded.commandId()).isEqualTo(COMMAND_ID);
        assertThat(decoded.operationCount()).isEqualTo(2);
        assertThat(decoded.firstSequence()).isEqualTo(10);
        assertThat(decoded.lastSequence()).isEqualTo(11);
    }

    @Test
    void leaderRedirectHintRoundTrips() {
        UUID nodeId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb");
        ClientWriteResponse response =
                new ClientWriteResponse(
                        ClientStatus.NOT_LEADER,
                        COMMAND_ID,
                        0,
                        0,
                        0,
                        new ClientEndpointHint("127.0.0.1", 1002, nodeId),
                        "leader moved");

        ClientWriteResponse decoded = ClientWriteResponseCodecV1.decode(ClientWriteResponseCodecV1.encode(response));

        assertThat(decoded.status()).isEqualTo(ClientStatus.NOT_LEADER);
        assertThat(decoded.leaderHint().host()).isEqualTo("127.0.0.1");
        assertThat(decoded.leaderHint().port()).isEqualTo(1002);
        assertThat(decoded.leaderHint().expectedNodeId()).isEqualTo(nodeId);
        assertThat(decoded.detail()).isEqualTo("leader moved");
    }

    @Test
    void corruptedResponseIsRejected() {
        byte[] encoded =
                ClientWriteResponseCodecV1.encode(
                        new ClientWriteResponse(ClientStatus.OK, COMMAND_ID, 1, 1, 1, null, ""));
        encoded[12] ^= 1;

        assertThatThrownBy(() -> ClientWriteResponseCodecV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
