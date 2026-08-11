package io.aetherdb.api.exceptions;

/** Base exception for Aether contract failures. */
public class AetherException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /**
     * Creates an exception with a diagnostic message.
     *
     * @param message failure description
     */
    public AetherException(String message) {
        super(message);
    }

    /**
     * Creates an exception with a diagnostic message and cause.
     *
     * @param message failure description
     * @param cause underlying failure
     */
    public AetherException(String message, Throwable cause) {
        super(message, cause);
    }
}
