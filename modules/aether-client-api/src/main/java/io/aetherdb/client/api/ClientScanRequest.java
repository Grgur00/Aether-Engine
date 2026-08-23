package io.aetherdb.client.api;

import java.util.Arrays;

/** Bounded scan-open or scan-next request body for the client protocol. */
public record ClientScanRequest(
        long configurationVersion, byte[] startInclusive, byte[] endExclusive, int maxEntries, byte[] pageToken) {
    /** Validates range bounds, page size, and token length. */
    public ClientScanRequest {
        if (configurationVersion < 0
                || startInclusive == null
                || endExclusive == null
                || startInclusive.length == 0
                || endExclusive.length == 0
                || startInclusive.length > 65_536
                || endExclusive.length > 65_536
                || maxEntries <= 0
                || maxEntries > 10_000
                || pageToken == null
                || pageToken.length > 4096) {
            throw new IllegalArgumentException("invalid client scan request");
        }
        startInclusive = Arrays.copyOf(startInclusive, startInclusive.length);
        endExclusive = Arrays.copyOf(endExclusive, endExclusive.length);
        pageToken = Arrays.copyOf(pageToken, pageToken.length);
    }

    @Override
    public byte[] startInclusive() {
        return Arrays.copyOf(startInclusive, startInclusive.length);
    }

    @Override
    public byte[] endExclusive() {
        return Arrays.copyOf(endExclusive, endExclusive.length);
    }

    @Override
    public byte[] pageToken() {
        return Arrays.copyOf(pageToken, pageToken.length);
    }
}
