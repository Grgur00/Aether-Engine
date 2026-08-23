package io.aetherdb.client;

import io.aetherdb.client.api.ClientScanEntry;
import io.aetherdb.client.api.ClientStatus;

import java.util.Arrays;
import java.util.List;

/** Terminal remote scan page result returned by the Java client facade. */
public record RemoteScanResult(ClientStatus status, int attempts, List<ClientScanEntry> entries, byte[] nextPageToken, String detail) {
    /** Validates and defensively copies the continuation token. */
    public RemoteScanResult {
        if (status == null
                || attempts <= 0
                || entries == null
                || entries.size() > 10_000
                || nextPageToken == null
                || nextPageToken.length > 4096
                || detail == null
                || detail.length() > 4096) {
            throw new IllegalArgumentException("invalid remote scan result");
        }
        entries = List.copyOf(entries);
        nextPageToken = Arrays.copyOf(nextPageToken, nextPageToken.length);
    }

    @Override
    public byte[] nextPageToken() {
        return Arrays.copyOf(nextPageToken, nextPageToken.length);
    }
}
