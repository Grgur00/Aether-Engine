package io.aetherdb.api.exceptions;

/** Snapshot creation exceeded the configured active-handle limit. */
public final class SnapshotLimitExceededException extends AetherException {
    private static final long serialVersionUID = 1L;
    public SnapshotLimitExceededException(String message) { super(message); }
}
