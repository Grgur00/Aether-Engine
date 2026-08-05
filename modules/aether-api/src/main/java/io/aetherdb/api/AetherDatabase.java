package io.aetherdb.api;

import io.aetherdb.api.result.LookupResult;

/** Byte-oriented v0.1 database contract. Implementations copy all inputs and outputs. */
public interface AetherDatabase extends AutoCloseable {
    /** Inserts or replaces one value.
     * @param key copied key bytes
     * @param value copied value bytes */
    void put(byte[] key, byte[] value);

    /** Deletes one key if present.
     * @param key copied key bytes */
    void delete(byte[] key);

    /** Reads at the latest visible sequence.
     * @param key key bytes
     * @return found or absent result */
    LookupResult get(byte[] key);

    /** Reads at a snapshot's visibility boundary.
     * @param key key bytes
     * @param snapshot snapshot owned by this database
     * @return found or absent result */
    LookupResult get(byte[] key, Snapshot snapshot);

    /** Captures the current visibility boundary.
     * @return snapshot that the caller must close */
    Snapshot newSnapshot();

    /** Scans a half-open key range at the latest sequence.
     * @param startInclusive first included key
     * @param endExclusive first excluded key
     * @return closeable cursor */
    AetherCursor scan(byte[] startInclusive, byte[] endExclusive);

    /** Scans a half-open key range through a snapshot.
     * @param startInclusive first included key
     * @param endExclusive first excluded key
     * @param snapshot snapshot owned by this database
     * @return closeable cursor */
    AetherCursor scan(byte[] startInclusive, byte[] endExclusive, Snapshot snapshot);

    /** Scans all visible keys at the latest sequence.
     * @return closeable cursor */
    AetherCursor scanAll();

    /** Scans all keys visible through a snapshot.
     * @param snapshot snapshot owned by this database
     * @return closeable cursor */
    AetherCursor scanAll(Snapshot snapshot);

    /** Atomically applies a batch using default options.
     * @param batch one-shot mutation batch */
    void write(WriteBatch batch);

    /** Atomically applies a batch with explicit admission and durability behavior.
     * @param batch one-shot mutation batch
     * @param options write options
     * @return successful write metadata */
    WriteResult write(WriteBatch batch, WriteOptions options);

    /** Reports whether the database is closed.
     * @return {@code true} after close */
    boolean isClosed();

    /** Closes database resources and invalidates owned handles. */
    @Override
    void close();
}
