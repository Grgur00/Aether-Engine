package io.aetherdb.memory;

/** Native bytes violate the v1 record invariant. */
public final class NativeRecordCorruptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NativeRecordCorruptionException(String message) {
        super(message);
    }
}
