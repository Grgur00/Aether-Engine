package io.aetherdb.api.exceptions;

/** Persistent database initialization, locking, recovery, or I/O failure. */
public final class DatabaseOpenException extends AetherException {
    private static final long serialVersionUID = 1L;
    public DatabaseOpenException(String message, Throwable cause) { super(message, cause); }
}
