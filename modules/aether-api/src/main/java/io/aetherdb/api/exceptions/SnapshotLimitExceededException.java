package io.aetherdb.api.exceptions;

/** Snapshot creation exceeded the configured active-handle limit. */
public final class SnapshotLimitExceededException extends AetherException {
    private static final long serialVersionUID = 1L;
    /** Creates an active-snapshot limit failure.
     * @param message failure description */
    public SnapshotLimitExceededException(String message) { super(message); }
}
