package io.aetherdb.api.exceptions;

/** Base exception for Aether contract failures. */
public class AetherException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AetherException(String message) {
        super(message);
    }

    public AetherException(String message, Throwable cause) {
        super(message, cause);
    }
}
