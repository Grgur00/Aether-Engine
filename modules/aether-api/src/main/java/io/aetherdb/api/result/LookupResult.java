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

    /** Creates a successful lookup result and copies the value.
     * @param value non-null value bytes
     * @return found result */
    public static LookupResult found(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new LookupResult(Arrays.copyOf(value, value.length));
    }

    /** Returns the shared logical-absence result.
     * @return not-found result */
    public static LookupResult notFound() {
        return NOT_FOUND;
    }

    /** Reports whether a value was found.
     * @return {@code true} when this result contains a value */
    public boolean isFound() {
        return value != null;
    }

    /** Returns the found value.
     * @return defensive copy of the value
     * @throws NoSuchElementException when no value was found */
    public byte[] value() {
        if (value == null) {
            throw new NoSuchElementException("lookup result is NOT_FOUND");
        }
        return Arrays.copyOf(value, value.length);
    }
}
