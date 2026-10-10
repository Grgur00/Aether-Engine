package io.aetherdb.sstable;

import java.util.LinkedHashMap;
import java.util.Map;
import io.aetherdb.sstable.jfr.BulkPhaseEvent;

/** Opt-in, single-use timing evidence for one builder finish; performs no I/O. */
public final class SSTableFinishTrace {
    private final Map<String, Long> stages = new LinkedHashMap<>();
    private long started;
    private long previous;
    private String activeStage = "prepare";
    private long total;
    private boolean completed;
    private long entryCount;
    private long fileBytes;
    private final boolean bulk;
    private BulkPhaseEvent event;

    public SSTableFinishTrace() { this(false); }
    public SSTableFinishTrace(boolean bulk) { this.bulk = bulk; }

    void start(long entries) {
        entryCount = entries;
        started = previous = System.nanoTime();
    }

    /** Switches exclusive stages, returning the previous stage for nested codec work. */
    public static String enter(SSTableFinishTrace trace, String stage) {
        if (trace == null) return null;
        if (trace.event != null) { trace.event.close(); trace.event = null; }
        if (trace.bulk) {
            String phase = switch (stage) {
                case "fileForce" -> "SSTABLE_FORCE";
                case "verificationOpenAndRead" -> "SSTABLE_VERIFY";
                default -> null;
            };
            if (phase != null) trace.event = BulkPhaseEvent.start(phase, trace.entryCount, trace.fileBytes, -1);
        }
        long now = System.nanoTime();
        trace.stages.merge(trace.activeStage, now - trace.previous, Long::sum);
        String previousStage = trace.activeStage;
        trace.previous = now;
        trace.activeStage = stage;
        return previousStage;
    }

    void fileBytes(long bytes) { fileBytes = bytes; }
    void finish(boolean success) {
        enter(this, "other");
        total = previous - started;
        completed = success;
    }

    public Map<String, Long> stagesNs() { return Map.copyOf(stages); }
    public long totalNs() { return total; }
    public boolean completed() { return completed; }
    public long entryCount() { return entryCount; }
    public long fileBytes() { return fileBytes; }
}
