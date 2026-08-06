package io.aetherdb.sstable.manifest;

/** Signals malformed, inconsistent, or checksum-invalid manifest state. */
public final class ManifestCorruptionException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    /** Creates a corruption failure.
     * @param message actionable validation detail */
    public ManifestCorruptionException(String message) { super(message); }
    /** Creates a corruption failure with its underlying cause.
     * @param message actionable validation detail
     * @param cause underlying field or transition failure */
    public ManifestCorruptionException(String message, Throwable cause) { super(message, cause); }
}
