package io.aetherdb.sstable;

/** Signals malformed or integrity-invalid immutable table content. */
public final class SSTableCorruptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** Creates an SSTable corruption failure.
     * @param message corruption description */
    public SSTableCorruptionException(String message) { super(message); }
    /** Creates an SSTable corruption failure with a cause. */
    public SSTableCorruptionException(String message, Throwable cause) { super(message, cause); }
}
