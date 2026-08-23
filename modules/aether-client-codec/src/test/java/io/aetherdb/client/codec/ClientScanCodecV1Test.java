package io.aetherdb.client.codec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.client.api.ClientScanEntry;
import io.aetherdb.client.api.ClientScanRequest;
import io.aetherdb.client.api.ClientScanResponse;
import io.aetherdb.client.api.ClientStatus;

import org.junit.jupiter.api.Test;

import java.util.List;

class ClientScanCodecV1Test {
    @Test
    void requestAndResponseRoundTrip() {
        ClientScanRequest request =
                ClientScanCodecV1.decodeRequest(
                        ClientScanCodecV1.encodeRequest(
                                new ClientScanRequest(7, new byte[] {1}, new byte[] {2}, 128, new byte[] {3})));
        ClientScanResponse response =
                ClientScanCodecV1.decodeResponse(
                        ClientScanCodecV1.encodeResponse(
                                new ClientScanResponse(
                                        ClientStatus.OK,
                                        List.of(new ClientScanEntry(new byte[] {4}, new byte[] {5})),
                                        new byte[] {6},
                                        "")));

        assertThat(request.configurationVersion()).isEqualTo(7);
        assertThat(request.pageToken()).containsExactly(3);
        assertThat(response.entries()).hasSize(1);
        assertThat(response.entries().getFirst().key()).containsExactly(4);
        assertThat(response.nextPageToken()).containsExactly(6);
    }

    @Test
    void nonOkResponseCannotCarryEntriesAndCorruptionIsRejected() {
        assertThatThrownBy(
                        () ->
                                new ClientScanResponse(
                                        ClientStatus.SCAN_EXPIRED,
                                        List.of(new ClientScanEntry(new byte[] {1}, new byte[0])),
                                        new byte[0],
                                        "expired"))
                .isInstanceOf(IllegalArgumentException.class);
        byte[] encoded =
                ClientScanCodecV1.encodeResponse(
                        new ClientScanResponse(ClientStatus.OK, List.of(), new byte[0], ""));
        encoded[12] ^= 1;
        assertThatThrownBy(() -> ClientScanCodecV1.decodeResponse(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
