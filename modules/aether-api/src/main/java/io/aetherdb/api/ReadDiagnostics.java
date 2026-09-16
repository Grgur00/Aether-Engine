package io.aetherdb.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Optional thread-confined read timings. Nested stages are inclusive, not additive.
 * No clock reads or collector allocations occur when no collector is attached.
 */
public final class ReadDiagnostics {
    private static final ThreadLocal<Collector> ACTIVE = new ThreadLocal<>();
    private ReadDiagnostics() {}

    public static Collector attach(Collector collector) {
        Collector previous = ACTIVE.get();
        if (collector == null) ACTIVE.remove(); else ACTIVE.set(collector);
        return previous;
    }
    public static long start() { return ACTIVE.get() == null ? 0 : System.nanoTime(); }
    public static void end(String stage, long started) {
        Collector collector = ACTIVE.get();
        if (collector != null) collector.stages.merge(stage, System.nanoTime() - started, Long::sum);
    }
    public static void count(String name, long amount) {
        Collector collector = ACTIVE.get();
        if (collector != null) collector.counters.merge(name, amount, Long::sum);
    }
    public static <T> T measure(String stage, Supplier<T> work) {
        long started = start();
        try { return work.get(); } finally { end(stage, started); }
    }
    public static byte[] copy(byte[] value) {
        long started = start();
        try { count("copiedBytes", value.length); return value.clone(); }
        finally { end("copy", started); }
    }

    public static final class Collector {
        private final String traceId;
        private final Map<String, Long> stages = new LinkedHashMap<>();
        private final Map<String, Long> counters = new LinkedHashMap<>();
        public Collector(String traceId) {
            this.traceId = java.util.Objects.requireNonNull(traceId);
            for (String stage : new String[] {"memtable", "tableSelection", "filter", "index",
                    "dataBlockLookup", "blockRead", "checksum", "decode", "copy"}) stages.put(stage, 0L);
        }
        public Map<String, Object> snapshot() {
            return Map.of("traceId", traceId, "stagesNs", new LinkedHashMap<>(stages),
                    "counters", new LinkedHashMap<>(counters));
        }
    }
}
