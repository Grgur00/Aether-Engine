package io.aetherdb.engine;

import io.aetherdb.sstable.SSTableFinishTrace;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Opt-in request-owned flush evidence. No output or storage policy changes. */
public final class FlushDiagnostics {
    private static final ThreadLocal<Collector> ACTIVE = new ThreadLocal<>();
    private FlushDiagnostics() {}

    /** A collector can follow a queued write onto the group-commit leader thread. */
    public static final class Collector {
        private final String traceId;
        private boolean compactionScheduled;
        private long compactionDebtBytes;
        public Collector() { this(""); }
        public Collector(String traceId) { this.traceId = traceId; }
        public String traceId() { return traceId; }
        public boolean compactionScheduled() { return compactionScheduled; }
        public long compactionDebtBytes() { return compactionDebtBytes; }
        void scheduled(long debt) { compactionScheduled = true; compactionDebtBytes = debt; }
        private final List<Event> events = new ArrayList<>();
        private final List<Compaction> compactions = new ArrayList<>();
        private final List<TableFinish> tableFinishes = new ArrayList<>();
        public List<Event> events() { return List.copyOf(events); }
        public List<Compaction> compactions() { return List.copyOf(compactions); }
        public List<TableFinish> tableFinishes() { return List.copyOf(tableFinishes); }
        Map<String, Object> backgroundTimings() {
            return Map.of("compactions", compactions.stream().map(c -> Map.of(
                    "completed", c.completed(), "selectionCount", c.selectionCount(),
                    "totalNs", c.totalNs(), "stagesNs", c.stagesNs())).toList(),
                    "sstableFinishes", tableFinishes.stream().map(f -> Map.of(
                    "fileNumber", f.fileNumber(), "completed", f.completed(), "entryCount", f.entryCount(),
                    "fileBytes", f.fileBytes(), "totalNs", f.totalNs(), "stagesNs", f.stagesNs())).toList());
        }
    }

    public static Collector current() { return ACTIVE.get(); }
    public static Collector attach(Collector collector) {
        Collector previous = ACTIVE.get();
        if (collector == null) ACTIVE.remove(); else ACTIVE.set(collector);
        return previous;
    }

    public static final class Event {
        private final String cause;
        private final Map<String, Long> counters;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private final long started = System.nanoTime();
        private long previous = started;
        private long total;
        private boolean completed;
        private Event(String cause, Map<String, Long> counters) {
            this.cause = cause;
            this.counters = Map.copyOf(counters);
        }
        public String cause() { return cause; }
        public Map<String, Long> counters() { return counters; }
        public Map<String, Long> stagesNs() { return Map.copyOf(stages); }
        public long totalNs() { return total; }
        public boolean completed() { return completed; }
        void stage(String name) {
            long now = System.nanoTime();
            stages.merge(name, now - previous, Long::sum);
            previous = now;
        }
        void finish(boolean success) {
            stage("other");
            total = previous - started;
            completed = success;
        }
    }

    /** Exclusive stage durations, accumulated across all selections and output files in one call. */
    public static final class Compaction {
        private final int flushIndex;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private final long started = System.nanoTime();
        private long previous = started;
        private String activeStage = "selection";
        private long total;
        private boolean completed;
        private int selectionCount;

        private Compaction(int flushIndex) { this.flushIndex = flushIndex; }
        /** Index into the request's flushes array, or -1 for write-admission compaction. */
        public int flushIndex() { return flushIndex; }
        public Map<String, Long> stagesNs() { return Map.copyOf(stages); }
        public long totalNs() { return total; }
        public boolean completed() { return completed; }
        public int selectionCount() { return selectionCount; }
        void selected() { selectionCount++; }
        void enter(String nextStage) {
            long now = System.nanoTime();
            stages.merge(activeStage, now - previous, Long::sum);
            previous = now;
            activeStage = nextStage;
        }
        void finish(boolean success) {
            enter("other");
            total = previous - started;
            completed = success;
        }
    }

    /** One output file; indexes refer to the same request's flushes and compactions arrays. */
    public static final class TableFinish {
        private final long fileNumber;
        private final int flushIndex;
        private final int compactionIndex;
        private final SSTableFinishTrace timing;

        private TableFinish(long fileNumber, int flushIndex, int compactionIndex, SSTableFinishTrace timing) {
            this.fileNumber = fileNumber;
            this.flushIndex = flushIndex;
            this.compactionIndex = compactionIndex;
            this.timing = timing;
        }

        public long fileNumber() { return fileNumber; }
        public int flushIndex() { return flushIndex; }
        public int compactionIndex() { return compactionIndex; }
        public boolean completed() { return timing.completed(); }
        public long entryCount() { return timing.entryCount(); }
        public long fileBytes() { return timing.fileBytes(); }
        public long totalNs() { return timing.totalNs(); }
        public Map<String, Long> stagesNs() { return timing.stagesNs(); }
    }

    static SSTableFinishTrace beginTableFinish(long fileNumber, Event flush, Compaction compaction) {
        Collector collector = current();
        if (collector == null) return null;
        SSTableFinishTrace timing = new SSTableFinishTrace();
        int flushIndex = compaction != null ? compaction.flushIndex()
                : flush == null ? -1 : collector.events.indexOf(flush);
        int compactionIndex = compaction == null ? -1 : collector.compactions.indexOf(compaction);
        collector.tableFinishes.add(new TableFinish(fileNumber, flushIndex, compactionIndex, timing));
        return timing;
    }

    static Compaction beginCompaction(Event flush) {
        Collector collector = current();
        if (collector == null) return null;
        Compaction compaction = new Compaction(flush == null ? -1 : collector.events.indexOf(flush));
        collector.compactions.add(compaction);
        return compaction;
    }

    static Event begin(String cause, Map<String, Long> counters) {
        Collector collector = current();
        if (collector == null) return null;
        Event event = new Event(cause, counters);
        collector.events.add(event);
        return event;
    }
}
