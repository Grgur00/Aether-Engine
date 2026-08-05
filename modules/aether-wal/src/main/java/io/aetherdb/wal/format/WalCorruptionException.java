package io.aetherdb.wal.format;

public final class WalCorruptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public WalCorruptionException(String message) { super(message); }
}
