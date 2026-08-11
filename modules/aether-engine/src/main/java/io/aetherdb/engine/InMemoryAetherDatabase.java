package io.aetherdb.engine;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.Snapshot;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.api.WriteResult;
import io.aetherdb.api.exceptions.AetherClosedException;
import io.aetherdb.api.exceptions.SnapshotException;
import io.aetherdb.api.exceptions.SnapshotLimitExceededException;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.memtable.reference.ByteKey;
import io.aetherdb.memtable.reference.SequenceSource;
import io.aetherdb.memtable.reference.VersionedKeyValueStore;
import io.aetherdb.memtable.reference.VersionedRecord;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/** Single-threaded heap reference implementation for the v0.1 semantic oracle. */
public final class InMemoryAetherDatabase implements AetherDatabase {
    private final Object identity = new Object();
    private final VersionedKeyValueStore store = new VersionedKeyValueStore();
    private final SequenceSource sequences;
    private final int maximumSnapshots;
    private final Set<SnapshotHandle> snapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private long lastVisibleSequence;
    private long nextSnapshotId = 1;
    private boolean closed;

    /** Creates an empty database with the default snapshot limit. */
    public InMemoryAetherDatabase() {
        this(0, 1_024);
    }

    /**
     * Creates a database starting at a specified visible sequence, primarily for overflow
     * verification.
     *
     * @param initialSequence initial visible sequence
     */
    public InMemoryAetherDatabase(long initialSequence) {
        this(initialSequence, 1_024);
    }

    /**
     * Creates a database with explicit sequence and snapshot limits.
     *
     * @param initialSequence initial visible sequence
     * @param maximumSnapshots maximum concurrently active snapshots
     */
    public InMemoryAetherDatabase(long initialSequence, int maximumSnapshots) {
        if (maximumSnapshots < 1 || maximumSnapshots > 65_536)
            throw new IllegalArgumentException("maximum snapshots must be between 1 and 65,536");
        sequences = new SequenceSource(initialSequence);
        this.maximumSnapshots = maximumSnapshots;
        lastVisibleSequence = initialSequence;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        ensureOpen();
        ByteKey copiedKey = key(key);
        byte[] copiedValue = value(value);
        long sequence = sequences.reserveOne();
        store.insert(copiedKey, VersionedRecord.value(sequence, copiedValue));
        lastVisibleSequence = sequence;
    }

    @Override
    public void delete(byte[] key) {
        ensureOpen();
        ByteKey copiedKey = key(key);
        long sequence = sequences.reserveOne();
        store.insert(copiedKey, VersionedRecord.tombstone(sequence));
        lastVisibleSequence = sequence;
    }

    @Override
    public LookupResult get(byte[] key) {
        ensureOpen();
        return resolve(key(key), lastVisibleSequence);
    }

    @Override
    public LookupResult get(byte[] key, Snapshot snapshot) {
        ensureOpen();
        return resolve(key(key), validateSnapshot(snapshot).sequence());
    }

    @Override
    public Snapshot newSnapshot() {
        ensureOpen();
        if (snapshots.size() >= maximumSnapshots)
            throw new SnapshotLimitExceededException("active snapshot limit exceeded");
        if (nextSnapshotId <= 0) throw new SnapshotException("snapshot ID exhausted");
        long id = nextSnapshotId++;
        SnapshotHandle[] holder = new SnapshotHandle[1];
        SnapshotHandle handle =
                new SnapshotHandle(
                        identity, id, lastVisibleSequence, () -> snapshots.remove(holder[0]));
        holder[0] = handle;
        snapshots.add(handle);
        return handle;
    }

    @Override
    public AetherCursor scan(byte[] startInclusive, byte[] endExclusive) {
        ensureOpen();
        return scanAt(startInclusive, endExclusive, lastVisibleSequence);
    }

    @Override
    public AetherCursor scan(byte[] startInclusive, byte[] endExclusive, Snapshot snapshot) {
        ensureOpen();
        return scanAt(startInclusive, endExclusive, validateSnapshot(snapshot).sequence());
    }

    @Override
    public AetherCursor scanAll() {
        ensureOpen();
        return new ListCursor(this, store.scanAll(lastVisibleSequence));
    }

    @Override
    public AetherCursor scanAll(Snapshot snapshot) {
        ensureOpen();
        return new ListCursor(this, store.scanAll(validateSnapshot(snapshot).sequence()));
    }

    private AetherCursor scanAt(byte[] startInclusive, byte[] endExclusive, long sequence) {
        ByteKey start = key(startInclusive);
        ByteKey end = key(endExclusive);
        if (start.compareTo(end) > 0) {
            throw new IllegalArgumentException("scan start must not be greater than end");
        }
        List<VersionedKeyValueStore.VisibleEntry> entries =
                start.equals(end) ? List.of() : store.scan(start, end, sequence);
        return new ListCursor(this, entries);
    }

    @Override
    public void write(WriteBatch batch) {
        write(batch, WriteOptions.defaults());
    }

    @Override
    public WriteResult write(WriteBatch batch, WriteOptions options) {
        ensureOpen();
        if (batch == null) {
            throw new IllegalArgumentException("batch must not be null");
        }
        if (options == null) throw new IllegalArgumentException("options must not be null");
        batch.sealForSubmission();

        List<PreparedMutation> prepared = new ArrayList<>();
        try {
            for (WriteBatch.Mutation mutation : batch.mutations()) {
                if (mutation instanceof WriteBatch.Put put) {
                    prepared.add(new PreparedMutation(key(put.key()), value(put.value()), false));
                } else if (mutation instanceof WriteBatch.Delete delete) {
                    prepared.add(new PreparedMutation(key(delete.key()), null, true));
                } else {
                    throw new IllegalArgumentException("unsupported batch mutation");
                }
            }
        } catch (RuntimeException | Error failure) {
            batch.markFailed();
            throw failure;
        }

        if (prepared.isEmpty()) {
            batch.markSucceeded();
            return new WriteResult(0, 0, 0, options.durabilityMode(), false);
        }

        SequenceSource.SequenceRange range;
        try {
            range = sequences.reserve(prepared.size());
        } catch (RuntimeException | Error failure) {
            batch.markFailed();
            throw failure;
        }
        batch.markSubmitted();
        long sequence = range.first();
        for (PreparedMutation mutation : prepared) {
            VersionedRecord record =
                    mutation.tombstone
                            ? VersionedRecord.tombstone(sequence)
                            : VersionedRecord.value(sequence, mutation.value);
            store.insert(mutation.key, record);
            sequence++;
        }
        lastVisibleSequence = range.last();
        batch.markCommitted();
        return new WriteResult(
                prepared.size(), range.first(), range.last(), options.durabilityMode(), false);
    }

    /**
     * Returns the latest sequence visible to unsnapshotted reads.
     *
     * @return latest visible sequence
     */
    public long lastVisibleSequence() {
        ensureOpen();
        return lastVisibleSequence;
    }

    /**
     * Counts retained MVCC versions for a key.
     *
     * @param key key to inspect
     * @return retained version count
     */
    public int retainedVersionCount(byte[] key) {
        ensureOpen();
        return store.versionCount(key(key));
    }

    /** Recovery hook for a visible checkpoint image; does not allocate a new sequence. */
    void restoreVisible(byte[] key, byte[] value, long sequence) {
        ensureOpen();
        if (sequence < 0 || sequence > lastVisibleSequence)
            throw new IllegalArgumentException("invalid recovery sequence");
        store.insert(key(key), VersionedRecord.value(sequence, value(value)));
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        for (SnapshotHandle snapshot : List.copyOf(snapshots)) snapshot.invalidate();
        snapshots.clear();
        closed = true;
    }

    void ensureOpen() {
        if (closed) {
            throw new AetherClosedException("database is closed");
        }
    }

    private LookupResult resolve(ByteKey key, long sequence) {
        VersionedRecord record = store.resolve(key, sequence);
        return record == null || record.type() == VersionedRecord.Type.TOMBSTONE
                ? LookupResult.notFound()
                : LookupResult.found(record.copyValue());
    }

    private SnapshotHandle validateSnapshot(Snapshot snapshot) {
        if (!(snapshot instanceof SnapshotHandle handle) || handle.identity() != identity) {
            throw new SnapshotException("snapshot belongs to another database");
        }
        handle.ensureOpen();
        return handle;
    }

    private static ByteKey key(byte[] key) {
        return ByteKey.copyOf(key);
    }

    private static byte[] value(byte[] value) {
        if (value == null) {
            throw new IllegalArgumentException("value must not be null");
        }
        return Arrays.copyOf(value, value.length);
    }

    private record PreparedMutation(ByteKey key, byte[] value, boolean tombstone) {}
}
