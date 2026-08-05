package io.aetherdb.api;

/** Closeable cursor positioned by calling {@link #next()}. */
public interface AetherCursor extends AutoCloseable {
    boolean next();

    byte[] key();

    byte[] value();

    boolean isClosed();

    @Override
    void close();
}
