package io.aetherdb.training.cache;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.io.IOException;

/** Optional per-request durations. Nested stages are inclusive, not additive. */
final class TrainingCacheRequestTrace {
    private static final ThreadLocal<TrainingCacheRequestTrace> ACTIVE = new ThreadLocal<>();
    private final String id;
    private final long started = System.nanoTime();
    private final Map<String, Long> durations = new LinkedHashMap<>();

    private TrainingCacheRequestTrace(byte[] id) { this.id = HexFormat.of().formatHex(id); }

    static void begin(byte[] id) { ACTIVE.set(new TrainingCacheRequestTrace(id)); }
    static void clear() { ACTIVE.remove(); }
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
        byte[] metadata = json.append("}}").toString().getBytes(StandardCharsets.UTF_8);
        return ByteBuffer.allocate(4 + metadata.length + payload.length)
                .putInt(metadata.length).put(metadata).put(payload).array();
    }
}
