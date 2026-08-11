package io.aetherdb.api.exceptions;

/** Snapshot is closed, foreign, or otherwise invalid for the operation. */
public final class SnapshotException extends AetherException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an invalid-snapshot failure.
     *
     * @param message failure description
     */
    public SnapshotException(String message) {
        super(message);
    }
}
