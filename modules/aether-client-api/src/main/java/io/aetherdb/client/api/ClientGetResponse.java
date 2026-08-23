package io.aetherdb.client.api;

import java.util.Arrays;

/** Stable point-read response body carried inside successful RPC envelopes. */
public record ClientGetResponse(ClientStatus status, byte[] value, String detail) {
    /** Validates response consistency and defensively copies the value. */
    public ClientGetResponse {
        if (status == null || value == null || value.length > 16 * 1024 * 1024 || detail == null || detail.length() > 4096) {
            throw new IllegalArgumentException("invalid client get response");
        }
        if (status != ClientStatus.OK && value.length != 0) {
            throw new IllegalArgumentException("non-OK get responses cannot carry values");
        }
        value = Arrays.copyOf(value, value.length);
    }

    /** Returns a defensive value copy. */
    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }
}
