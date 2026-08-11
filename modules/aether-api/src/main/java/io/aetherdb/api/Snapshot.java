package io.aetherdb.api;

/** Immutable visibility boundary owned by one database. */
public interface Snapshot extends AutoCloseable {
    /**
     * Returns the handle identity.
     *
     * @return database-local snapshot ID
     */
    long id();

    /**
     * Returns the visibility boundary.
     *
     * @return highest sequence visible through this snapshot
     */
    long sequence();

    /**
     * Reports whether the handle is closed.
     *
     * @return {@code true} after close or invalidation
     */
    boolean isClosed();

    /** Releases the pinned visibility boundary. */
    @Override
    void close();
}
