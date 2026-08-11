package io.aetherdb.engine;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.Snapshot;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.api.WriteResult;
import io.aetherdb.api.result.LookupResult;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAccumulator;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Default zero-dependency implementation of the metered database decorator. */
final class DefaultMeteredAetherDatabase implements MeteredAetherDatabase {
    private static final int RESERVOIR_SIZE = 16_384;
    private final AetherDatabase delegate;
    private final LongSupplier nanoTime;
    private final AtomicReference<Collector> collector;

    DefaultMeteredAetherDatabase(AetherDatabase delegate) {
        this(delegate, System::nanoTime);
    }

    DefaultMeteredAetherDatabase(AetherDatabase delegate, LongSupplier nanoTime) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        collector = new AtomicReference<>(new Collector(nanoTime.getAsLong()));
    }

    @Override
    public void put(byte[] key, byte[] value) {
        measure(DatabaseOperation.PUT, () -> delegate.put(key, value));
    }

    @Override
    public void delete(byte[] key) {
        measure(DatabaseOperation.DELETE, () -> delegate.delete(key));
    }

    @Override
    public LookupResult get(byte[] key) {
        return measure(DatabaseOperation.GET, () -> delegate.get(key));
    }

    @Override
    public LookupResult get(byte[] key, Snapshot snapshot) {
        return measure(DatabaseOperation.GET, () -> delegate.get(key, snapshot));
    }

    @Override
    public Snapshot newSnapshot() {
        return measure(DatabaseOperation.SNAPSHOT, delegate::newSnapshot);
    }

    @Override
    public AetherCursor scan(byte[] start, byte[] end) {
        return measure(DatabaseOperation.SCAN, () -> delegate.scan(start, end));
    }

    @Override
    public AetherCursor scan(byte[] start, byte[] end, Snapshot snapshot) {
        return measure(DatabaseOperation.SCAN, () -> delegate.scan(start, end, snapshot));
    }

    @Override
    public AetherCursor scanAll() {
        return measure(DatabaseOperation.SCAN, () -> delegate.scanAll());
    }

    @Override
    public AetherCursor scanAll(Snapshot snapshot) {
        return measure(DatabaseOperation.SCAN, () -> delegate.scanAll(snapshot));
    }

    @Override
    public void write(WriteBatch batch) {
        measure(DatabaseOperation.WRITE, () -> delegate.write(batch));
    }

    @Override
    public WriteResult write(WriteBatch batch, WriteOptions options) {
        return measure(DatabaseOperation.WRITE, () -> delegate.write(batch, options));
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public void close() {
        delegate.close();
    }

    @Override
    public DatabaseMetrics metrics() {
        long now = nanoTime.getAsLong();
        Collector current = collector.get();
        EnumMap<DatabaseOperation, OperationMetrics> snapshots =
                new EnumMap<>(DatabaseOperation.class);
        for (DatabaseOperation operation : DatabaseOperation.values()) {
            snapshots.put(
                    operation, current.operation(operation).snapshot(now - current.startedNanos));
        }
        return new DatabaseMetrics(
                Instant.now(),
                Duration.ofNanos(Math.max(0, now - current.startedNanos)),
                snapshots);
    }

    @Override
    public void resetMetrics() {
        collector.set(new Collector(nanoTime.getAsLong()));
    }

    private void measure(DatabaseOperation operation, Runnable action) {
        measure(
                operation,
                () -> {
                    action.run();
                    return null;
                });
    }

    private <T> T measure(DatabaseOperation operation, Supplier<T> action) {
        Collector active = collector.get();
        long started = nanoTime.getAsLong();
        boolean failed = false;
        try {
            return action.get();
        } catch (RuntimeException | Error failure) {
            failed = true;
            throw failure;
        } finally {
            active.operation(operation).record(Math.max(0, nanoTime.getAsLong() - started), failed);
        }
    }

    private static final class Collector {
        private final long startedNanos;
        private final EnumMap<DatabaseOperation, OperationRecorder> operations =
                new EnumMap<>(DatabaseOperation.class);

        private Collector(long startedNanos) {
            this.startedNanos = startedNanos;
            for (DatabaseOperation operation : DatabaseOperation.values())
                operations.put(operation, new OperationRecorder());
        }

        private OperationRecorder operation(DatabaseOperation operation) {
            return operations.get(operation);
        }
    }

    private static final class OperationRecorder {
        private final LongAdder count = new LongAdder();
        private final LongAdder errors = new LongAdder();
        private final LongAdder totalNanos = new LongAdder();
        private final LongAccumulator minimum = new LongAccumulator(Long::min, Long.MAX_VALUE);
        private final LongAccumulator maximum = new LongAccumulator(Long::max, 0);
        private final AtomicLong cursor = new AtomicLong();
        private final AtomicLongArray reservoir = new AtomicLongArray(RESERVOIR_SIZE);

        private void record(long latencyNanos, boolean failed) {
            long position = cursor.getAndIncrement();
            reservoir.set((int) (position & (RESERVOIR_SIZE - 1)), latencyNanos);
            totalNanos.add(latencyNanos);
            minimum.accumulate(latencyNanos);
            maximum.accumulate(latencyNanos);
            if (failed) errors.increment();
            count.increment();
        }

        private OperationMetrics snapshot(long elapsedNanos) {
            long completed = count.sum();
            int sampleSize = (int) Math.min(completed, RESERVOIR_SIZE);
            long[] sample = new long[sampleSize];
            for (int i = 0; i < sampleSize; i++) sample[i] = reservoir.get(i);
            Arrays.sort(sample);
            double seconds = Math.max(1, elapsedNanos) / 1_000_000_000.0;
            return new OperationMetrics(
                    completed,
                    errors.sum(),
                    completed / seconds,
                    completed == 0 ? 0 : (double) totalNanos.sum() / completed,
                    completed == 0 ? 0 : minimum.get(),
                    percentile(sample, 0.50),
                    percentile(sample, 0.95),
                    percentile(sample, 0.99),
                    completed == 0 ? 0 : maximum.get());
        }

        private static long percentile(long[] sorted, double quantile) {
            if (sorted.length == 0) return 0;
            int index = (int) Math.ceil(quantile * sorted.length) - 1;
            return sorted[Math.max(0, index)];
        }
    }
}
