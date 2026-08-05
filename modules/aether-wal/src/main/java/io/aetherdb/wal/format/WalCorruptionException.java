package io.aetherdb.wal.format;

/** Signals malformed, truncated, or integrity-invalid WAL content. */
public final class WalCorruptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** Creates a WAL corruption failure.
     * @param message corruption description */
    public WalCorruptionException(String message) { super(message); }
}
