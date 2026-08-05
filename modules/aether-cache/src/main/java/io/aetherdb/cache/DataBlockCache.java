package io.aetherdb.cache;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.LongAdder;

/** Bounded, sharded segmented-LRU cache for verified immutable data blocks. */
public final class DataBlockCache implements AutoCloseable {
    public static final long DEFAULT_CAPACITY = 128L * 1024 * 1024;
    public static final long MIN_CAPACITY = 16L * 1024 * 1024;
    public static final long MAX_CAPACITY = 4L * 1024 * 1024 * 1024;
    public static final int DEFAULT_SHARDS = 16;
    private static final long ENTRY_OVERHEAD = 96;

    private final Shard[] shards;
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder loads = new LongAdder();
    private final LongAdder loadFailures = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final LongAdder admissionBypasses = new LongAdder();
    private volatile boolean closed;

    public DataBlockCache() { this(DEFAULT_CAPACITY, DEFAULT_SHARDS); }

    public DataBlockCache(long capacity, int shardCount) {
        if (capacity < MIN_CAPACITY || capacity > MAX_CAPACITY)
            throw new IllegalArgumentException("capacity must be between 16 MiB and 4 GiB");
        if (shardCount <= 0 || Integer.bitCount(shardCount) != 1)
            throw new IllegalArgumentException("shard count must be a positive power of two");
        shards = new Shard[shardCount];
        long base = capacity / shardCount;
        long remainder = capacity % shardCount;
        for (int i = 0; i < shardCount; i++) shards[i] = new Shard(base + (i < remainder ? 1 : 0));
    }

    public BlockLease acquireOrLoad(BlockCacheKey key, BlockLoader loader, boolean fillCache) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        ensureOpen();
        Shard shard = shard(key);
        Entry cached = shard.acquire(key);
        if (cached != null) {
            hits.increment();
            return lease(shard, cached);
        }
        misses.increment();

        CompletableFuture<byte[]> future;
        boolean owner;
        synchronized (shard) {
            ensureOpen();
            cached = shard.acquireLocked(key);
            if (cached != null) {
                hits.increment();
                return lease(shard, cached);
            }
            future = shard.inflight.get(key);
            owner = future == null;
            if (owner) {
                future = new CompletableFuture<>();
                shard.inflight.put(key, future);
            }
        }

        if (owner) {
            try {
                byte[] loaded = Objects.requireNonNull(loader.load(), "loader returned null");
                if (loaded.length != key.blockLength())
                    throw new IllegalStateException("loaded block length does not match cache key");
                loads.increment();
                future.complete(loaded);
            } catch (Throwable failure) {
                loadFailures.increment();
                future.completeExceptionally(failure);
            }
        }

        byte[] loaded;
        try { loaded = future.join(); }
        catch (CompletionException failure) {
            if (owner) synchronized (shard) { shard.inflight.remove(key); }
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IllegalStateException("block load failed", cause);
        }
        if (!fillCache) {
            if (owner) synchronized (shard) { shard.inflight.remove(key); }
            return new BlockLease(loaded, () -> {});
        }
        Entry admitted = shard.admitAndAcquire(key, loaded);
        if (owner) synchronized (shard) { shard.inflight.remove(key); }
        if (admitted == null) {
            admissionBypasses.increment();
            return new BlockLease(loaded, () -> {});
        }
        return lease(shard, admitted);
    }

    public void invalidateFile(long fileNumber) {
        if (fileNumber <= 0) throw new IllegalArgumentException("fileNumber must be positive");
        for (Shard shard : shards) shard.invalidateFile(fileNumber);
    }

    public BlockCacheMetrics metrics() {
        long resident = 0, pinned = 0;
        for (Shard shard : shards) {
            synchronized (shard) { resident += shard.weight; pinned += shard.pinnedCount(); }
        }
        return new BlockCacheMetrics(hits.sum(), misses.sum(), loads.sum(), loadFailures.sum(),
                evictions.sum(), admissionBypasses.sum(), resident, pinned);
    }

    @Override public void close() {
        closed = true;
        for (Shard shard : shards) shard.clear();
    }

    private BlockLease lease(Shard shard, Entry entry) {
        return new BlockLease(entry.bytes, () -> shard.release(entry));
    }
    private Shard shard(BlockCacheKey key) {
        int hash = key.hashCode();
        hash ^= hash >>> 16;
        return shards[hash & (shards.length - 1)];
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("block cache is closed"); }

    private final class Shard {
        private final long capacity;
        private final long probationLimit;
        private final long admissionMaximum;
        private final LinkedHashMap<BlockCacheKey, Entry> probation = new LinkedHashMap<>(16, .75f, true);
        private final LinkedHashMap<BlockCacheKey, Entry> protectedEntries = new LinkedHashMap<>(16, .75f, true);
        private final Map<BlockCacheKey, CompletableFuture<byte[]>> inflight = new HashMap<>();
        private long weight;
        private long probationWeight;

        Shard(long capacity) {
            this.capacity = capacity;
            probationLimit = capacity / 5;
            admissionMaximum = capacity / 4;
        }

        synchronized Entry acquire(BlockCacheKey key) { return acquireLocked(key); }
        Entry acquireLocked(BlockCacheKey key) {
            Entry entry = protectedEntries.get(key);
            if (entry != null) { entry.pins++; return entry; }
            entry = probation.remove(key);
            if (entry != null) {
                probationWeight -= entry.weight;
                protectedEntries.put(key, entry);
                entry.pins++;
                rebalanceProtected();
            }
            return entry;
        }

        synchronized Entry admitAndAcquire(BlockCacheKey key, byte[] bytes) {
            Entry existing = acquireLocked(key);
            if (existing != null) return existing;
            long charge = bytes.length + ENTRY_OVERHEAD;
            if (charge > admissionMaximum) return null;
            Entry entry = new Entry(key, bytes, charge);
            entry.pins = 1;
            probation.put(key, entry);
            probationWeight += charge;
            weight += charge;
            evictToCapacity();
            if (weight > capacity) {
                probation.remove(key);
                probationWeight -= charge;
                weight -= charge;
                return null;
            }
            return entry;
        }

        synchronized void release(Entry entry) {
            if (entry.pins <= 0) throw new IllegalStateException("block pin underflow");
            entry.pins--;
            evictToCapacity();
        }

        synchronized void invalidateFile(long fileNumber) {
            invalidate(probation, fileNumber, true);
            invalidate(protectedEntries, fileNumber, false);
        }

        private void invalidate(Map<BlockCacheKey, Entry> entries, long fileNumber, boolean isProbation) {
            Iterator<Entry> iterator = entries.values().iterator();
            while (iterator.hasNext()) {
                Entry entry = iterator.next();
                if (entry.key.fileNumber() == fileNumber) {
                    iterator.remove();
                    entry.resident = false;
                    weight -= entry.weight;
                    if (isProbation) probationWeight -= entry.weight;
                }
            }
        }

        private void rebalanceProtected() {
            long protectedLimit = capacity - probationLimit;
            long protectedWeight = weight - probationWeight;
            Iterator<Entry> iterator = protectedEntries.values().iterator();
            while (protectedWeight > protectedLimit && iterator.hasNext()) {
                Entry demoted = iterator.next(); iterator.remove();
                probation.put(demoted.key, demoted);
                probationWeight += demoted.weight;
                protectedWeight -= demoted.weight;
            }
        }

        private void evictToCapacity() {
            while (weight > capacity && evictOne(probation, true)) {}
            while (weight > capacity && evictOne(protectedEntries, false)) {}
        }

        private boolean evictOne(LinkedHashMap<BlockCacheKey, Entry> entries, boolean isProbation) {
            for (Iterator<Entry> iterator = entries.values().iterator(); iterator.hasNext();) {
                Entry candidate = iterator.next();
                if (candidate.pins == 0) {
                    iterator.remove(); candidate.resident = false; weight -= candidate.weight;
                    if (isProbation) probationWeight -= candidate.weight;
                    evictions.increment(); return true;
                }
            }
            return false;
        }

        synchronized void clear() {
            for (Entry entry : probation.values()) entry.resident = false;
            for (Entry entry : protectedEntries.values()) entry.resident = false;
            probation.clear(); protectedEntries.clear(); weight = 0; probationWeight = 0;
            for (CompletableFuture<byte[]> future : new ArrayList<>(inflight.values()))
                future.completeExceptionally(new IllegalStateException("block cache is closed"));
            inflight.clear();
        }
        long pinnedCount() {
            return probation.values().stream().filter(e -> e.pins > 0).count()
                    + protectedEntries.values().stream().filter(e -> e.pins > 0).count();
        }
    }

    private static final class Entry {
        final BlockCacheKey key; final byte[] bytes; final long weight;
        int pins; boolean resident = true;
        Entry(BlockCacheKey key, byte[] bytes, long weight) { this.key = key; this.bytes = bytes; this.weight = weight; }
    }
}
