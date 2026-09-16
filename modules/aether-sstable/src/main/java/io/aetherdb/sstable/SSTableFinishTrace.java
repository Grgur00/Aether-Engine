package io.aetherdb.sstable;

import java.util.LinkedHashMap;
import java.util.Map;

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

    void start(long entries) {
        entryCount = entries;
        started = previous = System.nanoTime();
    }

    /** Switches exclusive stages, returning the previous stage for nested codec work. */
    public static String enter(SSTableFinishTrace trace, String stage) {
        if (trace == null) return null;
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
