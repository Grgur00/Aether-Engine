package io.aetherdb.client.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.client.api.ClientGetRequest;
import io.aetherdb.client.api.ClientGetResponse;
import io.aetherdb.client.api.ClientStatus;

import org.junit.jupiter.api.Test;

class ClientGetCodecV1Test {
    @Test
    void requestAndResponseRoundTrip() {
        ClientGetRequest request = ClientGetCodecV1.decodeRequest(ClientGetCodecV1.encodeRequest(new ClientGetRequest(7, new byte[] {1, 2})));
        ClientGetResponse response = ClientGetCodecV1.decodeResponse(ClientGetCodecV1.encodeResponse(new ClientGetResponse(ClientStatus.OK, new byte[] {3, 4}, "")));

        assertThat(request.configurationVersion()).isEqualTo(7);
        assertThat(request.key()).containsExactly(1, 2);
        assertThat(response.status()).isEqualTo(ClientStatus.OK);
        assertThat(response.value()).containsExactly(3, 4);
    }

    @Test
    void missingResponseRejectsValuesAndCorruptionIsRejected() {
        assertThatThrownBy(() -> new ClientGetResponse(ClientStatus.NOT_FOUND, new byte[] {1}, ""))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] encoded = ClientGetCodecV1.encodeResponse(new ClientGetResponse(ClientStatus.NOT_FOUND, new byte[0], ""));
        encoded[12] ^= 1;
        assertThatThrownBy(() -> ClientGetCodecV1.decodeResponse(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
