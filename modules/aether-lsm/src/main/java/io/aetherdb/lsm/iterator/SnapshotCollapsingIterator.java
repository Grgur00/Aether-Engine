package io.aetherdb.lsm.iterator;

import java.util.Arrays;

/** Collapses internal versions into visible user values and masks tombstones. */
public final class SnapshotCollapsingIterator implements AutoCloseable {
    private final InternalIterator source;
    private final long visibleSequence;
    private final byte[] startInclusive;
    private final byte[] endExclusive;
    private InternalEntry buffered;
    private byte[] key;
    private byte[] value;
    private boolean closed;

    public SnapshotCollapsingIterator(InternalIterator source, long visibleSequence, byte[] startInclusive, byte[] endExclusive) {
        if (source == null || startInclusive == null || endExclusive == null || visibleSequence < 0)
            throw new IllegalArgumentException("invalid collapsing iterator arguments");
        if (Arrays.compareUnsigned(startInclusive, endExclusive) > 0) throw new IllegalArgumentException("reversed range");
        this.source = source; this.visibleSequence = visibleSequence;
        this.startInclusive = startInclusive.clone(); this.endExclusive = endExclusive.clone();
    }
    public boolean next() {
        ensureOpen(); key = null; value = null;
        while (true) {
            InternalEntry candidate = take();
            if (candidate == null) return false;
            byte[] userKey = candidate.userKey();
            InternalEntry visible = null;
            do {
                if (visible == null && candidate.sequence() <= visibleSequence) visible = candidate;
                candidate = take();
            } while (candidate != null && Arrays.equals(userKey, candidate.userKey()));
            buffered = candidate;
            if (Arrays.compareUnsigned(userKey, startInclusive) < 0) continue;
            if (Arrays.compareUnsigned(userKey, endExclusive) >= 0) return false;
            if (visible == null || visible.type() == InternalEntry.Type.TOMBSTONE) continue;
            key = userKey; value = visible.value(); return true;
        }
    }
    private InternalEntry take() { if (buffered != null) { InternalEntry result = buffered; buffered = null; return result; } return source.next() ? source.current() : null; }
    public byte[] key() { ensurePositioned(); return key.clone(); }
    public byte[] value() { ensurePositioned(); return value.clone(); }
    @Override public void close() { if (!closed) { closed = true; source.close(); } }
    private void ensurePositioned() { ensureOpen(); if (key == null) throw new IllegalStateException("iterator is not positioned"); }
    private void ensureOpen() { if (closed) throw new IllegalStateException("iterator is closed"); }
}
