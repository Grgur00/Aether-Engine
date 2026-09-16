package io.aetherdb.api.result;

import java.util.Arrays;
import java.util.NoSuchElementException;

/** A found value or ordinary logical absence. */
public final class LookupResult {
    private static final LookupResult NOT_FOUND = new LookupResult(null);
    private final byte[] value;
    private volatile Object validatedBy;

    private LookupResult(byte[] value) {
        this.value = value;
    }

    /**
     * Creates a successful lookup result and copies the value.
     *
     * @param value non-null value bytes
     * @return found result
     */
    public static LookupResult found(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return new LookupResult(io.aetherdb.api.ReadDiagnostics.copy(value));
    }

    /**
     * Returns the shared logical-absence result.
     *
     * @return not-found result
     */
    public static LookupResult notFound() {
        return NOT_FOUND;
    }

    /**
     * Reports whether a value was found.
     *
     * @return {@code true} when this result contains a value
     */
    public boolean isFound() {
        return value != null;
    }

    /**
     * Returns the found value.
     *
     * @return defensive copy of the value
     * @throws NoSuchElementException when no value was found
     */
    public byte[] value() {
        if (value == null) {
            throw new NoSuchElementException("lookup result is NOT_FOUND");
        }
        return io.aetherdb.api.ReadDiagnostics.copy(value);
    }

    /** Read-only view of privately owned immutable bytes; the backing array is never exposed. */
    public java.nio.ByteBuffer readOnlyValue() {
        if (value == null) throw new NoSuchElementException("lookup result is NOT_FOUND");
        return java.nio.ByteBuffer.wrap(value).asReadOnlyBuffer();
    }

    /**
     * Validates this exact immutable representation once for an opaque validator identity.
     * Only successful validation is remembered. A different representation (including a
     * newly loaded block) starts unvalidated. The validator receives a read-only view.
     * One identity slot bounds metadata; alternating validators safely revalidate.
     *
     * @return true when validation ran, false when this identity already validated these bytes
     */
    public boolean validateOnce(Object identity, java.util.function.Consumer<java.nio.ByteBuffer> validator) {
        java.util.Objects.requireNonNull(identity, "identity");
        java.util.Objects.requireNonNull(validator, "validator");
        if (value == null) throw new NoSuchElementException("lookup result is NOT_FOUND");
        if (validatedBy == identity) return false;
        synchronized (this) {
            if (validatedBy == identity) return false;
            validator.accept(readOnlyValue());
            validatedBy = identity;
            return true;
        }
    }
}
