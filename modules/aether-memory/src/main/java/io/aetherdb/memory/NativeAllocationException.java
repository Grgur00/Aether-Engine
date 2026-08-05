package io.aetherdb.memory;

/** Native budget or physical allocation failed. */
public final class NativeAllocationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public NativeAllocationException(String message) { super(message); }
    public NativeAllocationException(String message, Throwable cause) { super(message, cause); }
}
