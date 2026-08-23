package io.aetherdb.client.api;

import java.util.Arrays;
import java.util.List;

/** Stable scan page response body carried inside successful RPC envelopes. */
public record ClientScanResponse(
        ClientStatus status, List<ClientScanEntry> entries, byte[] nextPageToken, String detail) {
    /** Validates page size, continuation token, and detail text. */
    public ClientScanResponse {
        if (status == null
                || entries == null
                || entries.size() > 10_000
                || nextPageToken == null
                || nextPageToken.length > 4096
                || detail == null
                || detail.length() > 4096) {
            throw new IllegalArgumentException("invalid client scan response");
        }
        entries = List.copyOf(entries);
        if (status != ClientStatus.OK && (!entries.isEmpty() || nextPageToken.length != 0)) {
            throw new IllegalArgumentException("non-OK scan responses cannot carry page data");
        }
        nextPageToken = Arrays.copyOf(nextPageToken, nextPageToken.length);
    }

    @Override
    public byte[] nextPageToken() {
        return Arrays.copyOf(nextPageToken, nextPageToken.length);
    }
}
