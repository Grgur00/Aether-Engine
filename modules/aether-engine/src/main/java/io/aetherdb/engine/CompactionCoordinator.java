package io.aetherdb.engine;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** Single worker with one coalesced wakeup, no queued storage plans and no automatic failure loop. */
final class CompactionCoordinator implements AutoCloseable {
    record Request(long requestedNs, long requestedEpochMillis, String traceId, long flushFileNumber, boolean traced) {}
    @FunctionalInterface interface Work { boolean run(Request request) throws Exception; }
    private final Work work;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "aether-compaction");
        thread.setDaemon(true);
        return thread;
    });
    private boolean active;
    private boolean stopping;
    private Request pending;
    private Throwable failure;

    CompactionCoordinator(Work work) { this.work = work; }

    synchronized boolean requestCompaction(Request request) {
        if (stopping) return false;
        if (pending == null) pending = request;
        if (!active) {
            active = true;
            executor.execute(this::drain);
        }
        return true;
    }

    private void drain() {
        while (true) {
            Request request;
            synchronized (this) {
                if (stopping || pending == null) {
                    active = false;
                    notifyAll();
                    return;
                }
                request = pending;
                pending = null;
            }
            try {
                while (!stopping() && work.run(request)) { /* Replan from current state. */ }
                synchronized (this) { failure = null; }
            } catch (Throwable problem) {
                synchronized (this) {
                    failure = problem;
                    pending = null;
                    active = false;
                    notifyAll();
                    return; // A later scheduling event is required for another attempt.
                }
            }
        }
    }

    private synchronized boolean stopping() { return stopping; }
    synchronized Throwable backgroundFailure() { return failure; }
    synchronized String state() { return stopping ? (active ? "STOPPING" : "CLOSED") : active ? "RUNNING" : "IDLE"; }

    synchronized void awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (active) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new IllegalStateException("background compaction did not become idle");
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
    }

    @Override public void close() {
        synchronized (this) { stopping = true; pending = null; }
        executor.shutdown(); // Never interrupt file construction or manifest publication.
        try {
            if (!executor.awaitTermination(60, TimeUnit.SECONDS))
                throw new IllegalStateException("background compaction shutdown timed out; database resources remain owned");
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for compaction; database resources remain owned", interruption);
        }
    }
}
