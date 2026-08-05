package io.aetherdb.api;

/** Closeable cursor positioned by calling {@link #next()}. */
public interface AetherCursor extends AutoCloseable {
    /** Advances to the next visible entry.
     * @return {@code true} when the cursor is positioned on an entry */
    boolean next();

    /** Returns the current key.
     * @return defensive copy of the key
     * @throws IllegalStateException if the cursor is not positioned */
    byte[] key();

    /** Returns the current value.
     * @return defensive copy of the value
     * @throws IllegalStateException if the cursor is not positioned */
    byte[] value();

    /** Reports whether this cursor has released its resources.
     * @return {@code true} after close */
    boolean isClosed();

    /** Releases resources held by this cursor. */
    @Override
    void close();
}
