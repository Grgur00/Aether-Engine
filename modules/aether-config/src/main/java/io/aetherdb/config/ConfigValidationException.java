package io.aetherdb.config;

/** Raised when production configuration is invalid or unsafe. */
public final class ConfigValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public ConfigValidationException(String message) {
        super(message);
    }
}
