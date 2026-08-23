package io.aetherdb.client;

import io.aetherdb.client.api.ClientStatus;

import java.util.Arrays;

/** Terminal remote point-read result returned by the Java client facade. */
public record RemoteReadResult(ClientStatus status, int attempts, byte[] value, String detail) {
    /** Validates and defensively copies response value bytes. */
    public RemoteReadResult {
        if (status == null
                || attempts <= 0
                || value == null
                || value.length > 16 * 1024 * 1024
                || detail == null
                || detail.length() > 4096) {
            throw new IllegalArgumentException("invalid remote read result");
        }
        if (status != ClientStatus.OK && value.length != 0) {
            throw new IllegalArgumentException("non-OK read result cannot carry value");
        }
        value = Arrays.copyOf(value, value.length);
    }

    /** Returns a defensive value copy. */
    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }
}
