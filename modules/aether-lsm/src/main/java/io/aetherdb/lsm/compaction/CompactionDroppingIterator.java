package io.aetherdb.lsm.compaction;

import io.aetherdb.lsm.iterator.InternalEntry;
import io.aetherdb.lsm.iterator.InternalIterator;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/** Snapshot-aware version reclamation over a merged strict internal stream. */
public final class CompactionDroppingIterator implements InternalIterator {
    private final InternalIterator source;
    private final long oldestSnapshotSequence;
    private final Predicate<byte[]> baseLevelForKey;
    private InternalEntry buffered;
    private InternalEntry current;
    private final ArrayDeque<InternalEntry> retained = new ArrayDeque<>();
    private long droppedVersions;
    private long droppedTombstones;
    private boolean closed;

    /**
     * Creates snapshot-aware reclamation over a sorted internal stream.
     *
     * @param source merged internal source
     * @param oldestSnapshotSequence oldest protected sequence
     * @param baseLevelForKey predicate proving lower-level absence
     */
    public CompactionDroppingIterator(
            InternalIterator source,
            long oldestSnapshotSequence,
            Predicate<byte[]> baseLevelForKey) {
        this.source = Objects.requireNonNull(source);
        this.baseLevelForKey = Objects.requireNonNull(baseLevelForKey);
        if (oldestSnapshotSequence < 0)
            throw new IllegalArgumentException("snapshot sequence must be non-negative");
        this.oldestSnapshotSequence = oldestSnapshotSequence;
    }

    @Override
    public boolean next() {
        ensureOpen();
        current = null;
        while (retained.isEmpty()) {
            InternalEntry first = take();
            if (first == null) return false;
            byte[] key = first.userKey();
            List<InternalEntry> group = new ArrayList<>();
            InternalEntry candidate = first;
            do {
                group.add(candidate);
                candidate = take();
            } while (candidate != null && Arrays.equals(key, candidate.userKey()));
            buffered = candidate;
            boolean boundaryHandled = false;
            for (InternalEntry entry : group) {
                if (entry.sequence() > oldestSnapshotSequence) retained.add(entry);
                else if (!boundaryHandled) {
                    boundaryHandled = true;
                    if (entry.type() == InternalEntry.Type.TOMBSTONE && baseLevelForKey.test(key))
                        droppedTombstones++;
                    else retained.add(entry);
                } else droppedVersions++;
            }
        }
        current = retained.remove();
        return true;
    }

    private InternalEntry take() {
        if (buffered != null) {
            InternalEntry entry = buffered;
            buffered = null;
            return entry;
        }
        return source.next() ? source.current() : null;
    }

    @Override
    public InternalEntry current() {
        ensureOpen();
        if (current == null) throw new IllegalStateException("iterator is not positioned");
        return current;
    }

    /**
     * Returns discarded obsolete values.
     *
     * @return dropped version count
     */
    public long droppedVersions() {
        return droppedVersions;
    }

    /**
     * Returns discarded base-level tombstones.
     *
     * @return dropped tombstone count
     */
    public long droppedTombstones() {
        return droppedTombstones;
    }

    @Override
    public void close() {
        if (!closed) {
            closed = true;
            source.close();
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("iterator is closed");
    }
}
