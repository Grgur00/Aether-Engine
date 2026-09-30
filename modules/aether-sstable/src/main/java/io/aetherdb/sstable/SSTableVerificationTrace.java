package io.aetherdb.sstable;

import java.util.Map;

/** Thread-scoped counters of actual completed full verifications, not inferred table counts. */
public final class SSTableVerificationTrace implements AutoCloseable {
    private static final ThreadLocal<SSTableVerificationTrace> ACTIVE = new ThreadLocal<>();
    private final Thread owner = Thread.currentThread();
    private long tables;
    private long bytes;
    private long inventories;
    private boolean closed;

    private SSTableVerificationTrace() {}

    /** Starts a non-nestable scope; unrelated reader opens outside it are not counted. */
    public static SSTableVerificationTrace begin() {
        if (ACTIVE.get() != null) throw new IllegalStateException("verification trace already active");
        var trace = new SSTableVerificationTrace();
        ACTIVE.set(trace);
        return trace;
    }

    static void verified(long fileBytes) {
        var trace = ACTIVE.get();
        if (trace != null) {
            trace.tables++;
            trace.bytes = Math.addExact(trace.bytes, fileBytes);
        }
    }

    /** Internal instrumentation at the measured manifest verification boundary. */
    public static void inventoryCall() {
        var trace = ACTIVE.get();
        if (trace != null) trace.inventories++;
    }

    /** Bytes count full physical table sizes, including envelopes and metadata. */
    public Map<String, Long> snapshot() {
        requireOwner();
        return Map.of("inventoryCalls", inventories, "tablesFullyVerified", tables,
                "bytesFullyVerified", bytes);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner) throw new IllegalStateException("verification trace thread mismatch");
    }

    @Override public void close() {
        requireOwner();
        if (!closed) {
            ACTIVE.remove();
            closed = true;
        }
    }
}
