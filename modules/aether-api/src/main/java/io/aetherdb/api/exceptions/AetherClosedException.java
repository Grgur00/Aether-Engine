package io.aetherdb.api.exceptions;

/** Operation attempted against a closed engine or handle. */
public final class AetherClosedException extends AetherException {
    private static final long serialVersionUID = 1L;

    public AetherClosedException(String message) {
        super(message);
    }
}
