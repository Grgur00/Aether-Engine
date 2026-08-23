package io.aetherdb.client.codec;

import static org.assertj.core.api.Assertions.*;

import io.aetherdb.client.api.*;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

final class ClientWriteCodecV1Test {
    @Test
    void orderedWriteRoundTrips() {
        var request =
                new ClientWriteRequest(
                        7,
                        List.of(
                                new ClientWriteOperation(
                                        ClientWriteOperation.Type.PUT,
                                        bytes("profile/1/name"),
                                        bytes("Ada")),
                                new ClientWriteOperation(
                                        ClientWriteOperation.Type.DELETE,
                                        bytes("profile/1/draft"),
                                        new byte[0])));
        byte[] encoded = ClientWriteCodecV1.encode(request);
        assertThat(encoded).hasSize(128 + 16 + 14 + 3 + 16 + 15);
        var decoded = ClientWriteCodecV1.decode(encoded);
        assertThat(decoded.configurationVersion()).isEqualTo(7);
        assertThat(decoded.commandId()).isEqualTo(ClientWriteRequest.NO_COMMAND_ID);
        assertThat(decoded.deduplicated()).isFalse();
        assertThat(decoded.operations()).hasSize(2);
        assertThat(decoded.operations().get(0).value()).isEqualTo(bytes("Ada"));
        assertThat(decoded.operations().get(1).type()).isEqualTo(ClientWriteOperation.Type.DELETE);
    }

    @Test
    void deduplicatedCommandIdentityRoundTrips() {
        UUID commandId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        var request =
                ClientWriteRequest.deduplicated(
                        9,
                        commandId,
                        List.of(
                                new ClientWriteOperation(
                                        ClientWriteOperation.Type.PUT, bytes("k"), bytes("v"))));

        var decoded = ClientWriteCodecV1.decode(ClientWriteCodecV1.encode(request));

        assertThat(decoded.configurationVersion()).isEqualTo(9);
        assertThat(decoded.commandId()).isEqualTo(commandId);
        assertThat(decoded.deduplicated()).isTrue();
        assertThat(decoded.operations().getFirst().key()).isEqualTo(bytes("k"));
    }

    @Test
    void corruptionIsRejected() {
        byte[] encoded =
                ClientWriteCodecV1.encode(
                        new ClientWriteRequest(
                                0,
                                List.of(
                                        new ClientWriteOperation(
                                                ClientWriteOperation.Type.PUT,
                                                bytes("k"),
                                                bytes("v")))));
        encoded[130] ^= 1;
        assertThatThrownBy(() -> ClientWriteCodecV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
