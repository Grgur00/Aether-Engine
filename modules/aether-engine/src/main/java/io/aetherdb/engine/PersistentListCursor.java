package io.aetherdb.engine;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.exceptions.AetherClosedException;
import io.aetherdb.memtable.reference.VersionedKeyValueStore;
import java.util.List;

/** Cursor over a stable materialized merged LSM scan. */
final class PersistentListCursor implements AetherCursor {
    private final PersistentAetherDatabase database;
    private final List<VersionedKeyValueStore.VisibleEntry> entries;
    private int index = -1;
    private boolean closed;

    /** Creates a cursor over defensively copied visible rows. */
    PersistentListCursor(PersistentAetherDatabase database, List<VersionedKeyValueStore.VisibleEntry> entries) {
        this.database = database; this.entries = List.copyOf(entries);
    }

    @Override public boolean next() {
        ensureUsable();
        if (index + 1 >= entries.size()) { index = entries.size(); return false; }
        index++; return true;
    }
    @Override public byte[] key() { return current().key(); }
    @Override public byte[] value() { return current().value(); }
    @Override public boolean isClosed() { return closed; }
    @Override public void close() { closed = true; }

    private VersionedKeyValueStore.VisibleEntry current() {
        ensureUsable();
        if (index < 0 || index >= entries.size()) throw new IllegalStateException("cursor is not positioned on a row");
        return entries.get(index);
    }
    private void ensureUsable() {
        if (database.isClosed()) throw new AetherClosedException("database is closed");
        if (closed) throw new AetherClosedException("cursor is closed");
    }
}
