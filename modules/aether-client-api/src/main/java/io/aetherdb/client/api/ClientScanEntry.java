package io.aetherdb.client.api;

import java.util.Arrays;

/** One physical key/value entry returned by a client scan page. */
public record ClientScanEntry(byte[] key, byte[] value) {
    /** Validates bounds and defensively copies key/value bytes. */
    public ClientScanEntry {
        if (key == null
                || key.length == 0
                || key.length > 65_536
                || value == null
                || value.length > 16 * 1024 * 1024) {
            throw new IllegalArgumentException("invalid scan entry");
        }
        key = Arrays.copyOf(key, key.length);
        value = Arrays.copyOf(value, value.length);
    }

    @Override
    public byte[] key() {
        return Arrays.copyOf(key, key.length);
    }

    @Override
    public byte[] value() {
        return Arrays.copyOf(value, value.length);
    }
}
