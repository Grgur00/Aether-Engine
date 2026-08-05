package io.aetherdb.api;

/** Immutable visibility boundary owned by one database. */
public interface Snapshot extends AutoCloseable {
    long id();

    long sequence();

    boolean isClosed();

    @Override
    void close();
}
