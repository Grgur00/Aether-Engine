package io.aetherdb.api.result;

import java.util.Arrays;
import java.util.NoSuchElementException;

/** A found value or ordinary logical absence. */
public final class LookupResult {
    private static final LookupResult NOT_FOUND = new LookupResult(null);
    private final byte[] value;

    private LookupResult(byte[] value) {
        this.value = value;
    }

    public static LookupResult found(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new LookupResult(Arrays.copyOf(value, value.length));
    }

    public static LookupResult notFound() {
        return NOT_FOUND;
    }

    public boolean isFound() {
        return value != null;
    }

    public byte[] value() {
        if (value == null) {
            throw new NoSuchElementException("lookup result is NOT_FOUND");
        }
        return Arrays.copyOf(value, value.length);
    }
}
