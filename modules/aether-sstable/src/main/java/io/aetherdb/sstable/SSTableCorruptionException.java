package io.aetherdb.sstable;

public final class SSTableCorruptionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    public SSTableCorruptionException(String message) { super(message); }
}
