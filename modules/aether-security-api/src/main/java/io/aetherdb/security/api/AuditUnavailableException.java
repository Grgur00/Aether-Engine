package io.aetherdb.security.api;

/** Raised when a mandatory audit event cannot be durably or externally recorded. */
public final class AuditUnavailableException extends Exception {
    private static final long serialVersionUID = 1L;

    public AuditUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }

    public AuditUnavailableException(String message) {
        super(message);
    }
}
