package io.aetherdb.replication.api;

import java.util.List;

/** Durable append/truncate interface consumed by the Raft core. */
public interface ReplicatedLogStore extends AutoCloseable {
    /** Returns the immutable cluster/node binding. */
    ReplicatedLogStoreIdentity identity();

    /** Returns the first retained index, or zero when empty. */
    long firstIndex();

    /** Returns the final retained index, or zero when empty. */
    long lastIndex();

    /** Returns the final retained term, or zero when empty. */
    long lastTerm();

    /** Returns the final assigned state sequence. */
    long lastStateSequence();

    /** Returns the greatest index known forced to stable storage. */
    long durableIndex();

    /** Appends a validated contiguous entry sequence without forcing. */
    void append(List<ReplicatedLogEntry> entries);

    /** Appends and forces a validated contiguous entry sequence. */
    void appendAndForce(List<ReplicatedLogEntry> entries);

    /** Forces all bytes through an already appended index. */
    void forceThrough(long index);

    /** Reads one retained entry. */
    ReplicatedLogEntry read(long index);

    /** Reads a half-open bounded range and returns at least one fitting entry when available. */
    List<ReplicatedLogEntry> readRange(
            long startInclusive, long endExclusive, long byteLimit, int entryLimit);

    /** Returns the term at a retained index. */
    long termAt(long index);

    /** Returns the hash at a retained index defensively. */
    byte[] entryHashAt(long index);

    /** Truncates an uncommitted/unapplied suffix beginning at the supplied retained index. */
    void truncateSuffix(long fromIndex, long commitIndex, long appliedIndex);

    /** Forces and releases the store lock and channels. */
    @Override
    void close();
}
