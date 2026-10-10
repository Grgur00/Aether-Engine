package io.aetherdb.training.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.io.IOException;
import io.aetherdb.engine.FlushDiagnostics;
import io.aetherdb.api.ReadDiagnostics;

/** Optional per-request durations. Nested stages are inclusive, not additive. */
final class TrainingCacheRequestTrace {
    private static final ThreadLocal<TrainingCacheRequestTrace> ACTIVE = new ThreadLocal<>();
    private final String id;
    private long started = System.nanoTime();
    private final Map<String, Long> durations = new LinkedHashMap<>();
    private final FlushDiagnostics.Collector flushes;
    private FlushDiagnostics.Collector previousFlushes;
    private final ReadDiagnostics.Collector reads;
    private ReadDiagnostics.Collector previousReads;

    private TrainingCacheRequestTrace(byte[] id) {
        this.id = HexFormat.of().formatHex(id);
        this.flushes = new FlushDiagnostics.Collector(this.id);
        this.reads = new ReadDiagnostics.Collector(this.id);
    }

    static void begin(byte[] id) {
        TrainingCacheRequestTrace trace = new TrainingCacheRequestTrace(id);
        trace.previousFlushes = FlushDiagnostics.attach(trace.flushes);
        trace.previousReads = ReadDiagnostics.attach(trace.reads);
        ACTIVE.set(trace);
    }
    static void requestStarted(long started, long readNs) {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace != null) {
            trace.started = started;
            trace.durations.put("requestRead", readNs);
        }
    }
    static void add(String stage, long durationNs) {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace != null) trace.durations.merge(stage, durationNs, Long::sum);
    }
    static Map<String, Object> completed(long completedAt) {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace == null) return null;
        return Map.of("traceId", trace.id, "stagesNs", new LinkedHashMap<>(trace.durations),
                "totalServerNs", completedAt - trace.started, "responseWriteCompleted", true,
                "readDiagnostics", trace.reads.snapshot());
    }
    static void clear() {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace != null) {
            FlushDiagnostics.attach(trace.previousFlushes);
            ReadDiagnostics.attach(trace.previousReads);
        }
        ACTIVE.remove();
    }
    static long start() { return ACTIVE.get() == null ? 0 : System.nanoTime(); }
    static void end(String stage, long started) {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace != null) trace.durations.merge(stage, System.nanoTime() - started, Long::sum);
    }

    @FunctionalInterface interface IoWork<T> { T run() throws IOException; }
    static <T> T measureIo(String stage, IoWork<T> work) throws IOException {
        long start = start();
        try { return work.run(); }
        finally { end(stage, start); }
    }

    static <T> T measure(String stage, Supplier<T> work) {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace == null) return work.get();
        long start = System.nanoTime();
        try { return work.get(); }
        finally { trace.durations.merge(stage, System.nanoTime() - start, Long::sum); }
    }

    static byte[] envelope(byte[] payload) {
        TrainingCacheRequestTrace trace = ACTIVE.get();
        if (trace == null) return payload;
        long elapsed = System.nanoTime() - trace.started;
        StringBuilder json = new StringBuilder("{\"traceId\":\"").append(trace.id)
                .append("\",\"serverDurationNs\":").append(elapsed).append(",\"stagesNs\":{");
        boolean comma = false;
        for (var entry : trace.durations.entrySet()) {
            if (comma) json.append(',');
            comma = true;
            json.append('"').append(entry.getKey()).append("\":").append(entry.getValue());
        }
        json.append("},\"writeDiagnostics\":").append(DiagnosticJson.encode(trace.flushes.writeDiagnostics()))
                .append(",\"readDiagnostics\":").append(DiagnosticJson.encode(trace.reads.snapshot()))
                .append(",\"responseWriteCompleted\":false,\"compactionScheduled\":").append(trace.flushes.compactionScheduled())
                .append(",\"compactionDebtBytes\":").append(trace.flushes.compactionDebtBytes())
                .append(",\"flushes\":[");
        comma = false;
        for (FlushDiagnostics.Event flush : trace.flushes.events()) {
            if (comma) json.append(',');
            comma = true;
            json.append("{\"cause\":\"").append(flush.cause()).append("\",\"completed\":").append(flush.completed());
            for (var counter : flush.counters().entrySet())
                json.append(",\"").append(counter.getKey()).append("\":").append(counter.getValue());
            json.append(",\"totalNs\":").append(flush.totalNs()).append(",\"stagesNs\":{");
            boolean stageComma = false;
            for (var stage : flush.stagesNs().entrySet()) {
                if (stageComma) json.append(',');
                stageComma = true;
                json.append('"').append(stage.getKey()).append("\":").append(stage.getValue());
            }
            json.append("}}");
        }
        json.append("],\"compactions\":[");
        comma = false;
        for (FlushDiagnostics.Compaction compaction : trace.flushes.compactions()) {
            if (comma) json.append(',');
            comma = true;
            json.append("{\"flushIndex\":").append(compaction.flushIndex())
                    .append(",\"completed\":").append(compaction.completed())
                    .append(",\"selectionCount\":").append(compaction.selectionCount())
                    .append(",\"totalNs\":").append(compaction.totalNs()).append(",\"stagesNs\":{");
            boolean stageComma = false;
            for (var stage : compaction.stagesNs().entrySet()) {
                if (stageComma) json.append(',');
                stageComma = true;
                json.append('"').append(stage.getKey()).append("\":").append(stage.getValue());
            }
            json.append("}}");
        }
        json.append("],\"sstableFinishes\":[");
        comma = false;
        for (FlushDiagnostics.TableFinish finish : trace.flushes.tableFinishes()) {
            if (comma) json.append(',');
            comma = true;
            json.append("{\"fileNumber\":").append(finish.fileNumber())
                    .append(",\"flushIndex\":").append(finish.flushIndex())
                    .append(",\"compactionIndex\":").append(finish.compactionIndex())
                    .append(",\"completed\":").append(finish.completed())
                    .append(",\"entryCount\":").append(finish.entryCount())
                    .append(",\"fileBytes\":").append(finish.fileBytes())
                    .append(",\"totalNs\":").append(finish.totalNs()).append(",\"stagesNs\":{");
            boolean stageComma = false;
            for (var stage : finish.stagesNs().entrySet()) {
                if (stageComma) json.append(',');
                stageComma = true;
                json.append('"').append(stage.getKey()).append("\":").append(stage.getValue());
            }
            json.append("}}");
        }
        byte[] metadata = json.append("]}").toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + metadata.length + payload.length)
                .putInt(metadata.length).put(metadata).put(payload).array();
    }
}
