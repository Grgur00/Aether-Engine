package io.aetherdb.lsm.iterator;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** K-way merge that rejects duplicate physical internal keys across sources. */
public final class MergingInternalIterator implements InternalIterator {
    private final List<InternalIterator> sources;
    private final PriorityQueue<Head> heap = new PriorityQueue<>(Comparator.comparing(Head::entry));
    private InternalEntry current;
    private boolean initialized;
    private boolean closed;

    public MergingInternalIterator(List<? extends InternalIterator> sources) { this.sources = new ArrayList<>(sources); }
    @Override public boolean next() {
        ensureOpen();
        if (!initialized) { initialized = true; for (InternalIterator source : sources) if (source.next()) heap.add(new Head(source, source.current())); }
        else if (current != null) advance(heap.remove().source());
        if (heap.isEmpty()) { current = null; return false; }
        Head first = heap.peek();
        for (Head head : heap) if (head != first && first.entry().sameIdentity(head.entry()))
            throw new IllegalStateException("duplicate internal key across sources");
        current = first.entry(); return true;
    }
    private void advance(InternalIterator source) { if (source.next()) heap.add(new Head(source, source.current())); }
    @Override public InternalEntry current() { ensureOpen(); if (current == null) throw new IllegalStateException("iterator is not positioned"); return current; }
    @Override public void close() { if (!closed) { closed = true; for (InternalIterator source : sources) source.close(); heap.clear(); } }
    private void ensureOpen() { if (closed) throw new IllegalStateException("iterator is closed"); }
    private record Head(InternalIterator source, InternalEntry entry) {}
}
