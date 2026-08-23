package io.aetherdb.client.api;

import java.util.Arrays;

/** Bounded point-read request body for the client protocol. */
public record ClientGetRequest(long configurationVersion, byte[] key) {
    /** Validates bounds and defensively copies the physical key. */
    public ClientGetRequest {
        if (configurationVersion < 0 || key == null || key.length == 0 || key.length > 65_536) {
            throw new IllegalArgumentException("invalid client get request");
        }
        key = Arrays.copyOf(key, key.length);
    }

    /** Returns a defensive key copy. */
    @Override
    public byte[] key() {
        return Arrays.copyOf(key, key.length);
    }
}
