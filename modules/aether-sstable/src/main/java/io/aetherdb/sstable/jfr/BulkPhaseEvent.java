package io.aetherdb.sstable.jfr;

import jdk.jfr.Category;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/** Opt-in coarse markers shared by the bulk writer and its lower-level storage code. */
@Name("aether.BulkPhase")
@Label("Aether Bulk Phase")
@Category({"Aether", "Training Cache"})
@StackTrace(false)
public final class BulkPhaseEvent extends Event implements AutoCloseable {
    public String phase;
    public long records;
    public long bytes;
    public int tableIndex;
    public static final boolean ENABLED = Boolean.getBoolean("aether.bulk.jfr");

    public static BulkPhaseEvent start(String phase, long records, long bytes, int tableIndex) {
        if (!ENABLED) return null;
        var event = new BulkPhaseEvent();
        event.phase = phase;
        event.records = records;
        event.bytes = bytes;
        event.tableIndex = tableIndex;
        event.begin();
        return event;
    }

    @Override public void close() { end(); commit(); }
}
