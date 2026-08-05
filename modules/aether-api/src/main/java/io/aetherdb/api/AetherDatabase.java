package io.aetherdb.api;

import io.aetherdb.api.result.LookupResult;

/** Byte-oriented v0.1 database contract. Implementations copy all inputs and outputs. */
public interface AetherDatabase extends AutoCloseable {
    void put(byte[] key, byte[] value);

    void delete(byte[] key);

    LookupResult get(byte[] key);

    LookupResult get(byte[] key, Snapshot snapshot);

    Snapshot newSnapshot();

    AetherCursor scan(byte[] startInclusive, byte[] endExclusive);

    AetherCursor scan(byte[] startInclusive, byte[] endExclusive, Snapshot snapshot);

    AetherCursor scanAll();

    AetherCursor scanAll(Snapshot snapshot);

    void write(WriteBatch batch);

    WriteResult write(WriteBatch batch, WriteOptions options);

    boolean isClosed();

    @Override
    void close();
}
