package io.aetherdb.lsm.iterator;

import java.util.List;

/** In-memory source iterator useful for MemTable adapters and semantic tests. */
public final class ListInternalIterator implements InternalIterator {
    private final List<InternalEntry> entries;
    private int index = -1;
    private boolean closed;

    /**
     * Creates an iterator after validating strict internal ordering.
     *
     * @param entries ordered entries
     */
    public ListInternalIterator(List<InternalEntry> entries) {
        this.entries = List.copyOf(entries);
        for (int i = 1; i < this.entries.size(); i++)
            if (this.entries.get(i - 1).compareTo(this.entries.get(i)) >= 0)
                throw new IllegalArgumentException("entries must be strictly internally ordered");
    }

    @Override
    public boolean next() {
        ensureOpen();
        if (++index < entries.size()) return true;
        index = entries.size();
        return false;
    }

    @Override
    public InternalEntry current() {
        ensureOpen();
        if (index < 0 || index >= entries.size())
            throw new IllegalStateException("iterator is not positioned");
        return entries.get(index);
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("iterator is closed");
    }
}
