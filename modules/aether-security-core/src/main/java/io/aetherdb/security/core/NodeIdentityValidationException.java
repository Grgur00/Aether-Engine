package io.aetherdb.security.core;

/** Raised when certificate identity cannot be bound to durable node identity. */
public final class NodeIdentityValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public NodeIdentityValidationException(String message) {
        super(message);
    }

    public NodeIdentityValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
