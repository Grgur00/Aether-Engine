package io.aetherdb.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class DataBlockCacheTest {
    @Test void hitPinsExistingBlockAndLoadsOnlyOnce() {
        try (DataBlockCache cache = new DataBlockCache(DataBlockCache.MIN_CAPACITY, 1)) {
            BlockCacheKey key = new BlockCacheKey(1, 0, 3);
            AtomicInteger loads = new AtomicInteger();
            try (BlockLease first = cache.acquireOrLoad(key, () -> { loads.incrementAndGet(); return new byte[] {1, 2, 3}; }, true);
                 BlockLease second = cache.acquireOrLoad(key, () -> fail("cache hit must not load"), true)) {
                assertSame(first.rawBytes(), second.rawBytes());
                assertEquals(1, cache.metrics().pinnedEntries());
            }
            assertEquals(1, loads.get());
            assertEquals(1, cache.metrics().hits());
            assertEquals(1, cache.metrics().misses());
        }
    }

    @Test void invalidationPreventsNewHitsButPreservesExistingLease() {
        try (DataBlockCache cache = new DataBlockCache(DataBlockCache.MIN_CAPACITY, 1)) {
            BlockCacheKey key = new BlockCacheKey(7, 10, 1);
            try (BlockLease pinned = cache.acquireOrLoad(key, () -> new byte[] {4}, true)) {
                cache.invalidateFile(7);
                assertArrayEquals(new byte[] {4}, pinned.rawBytes());
                try (BlockLease replacement = cache.acquireOrLoad(key, () -> new byte[] {5}, true)) {
                    assertArrayEquals(new byte[] {5}, replacement.rawBytes());
                }
            }
        }
    }

    @Test void concurrentMissesAreCoalesced() throws Exception {
        try (DataBlockCache cache = new DataBlockCache(DataBlockCache.MIN_CAPACITY, 1)) {
            BlockCacheKey key = new BlockCacheKey(2, 0, 1);
            AtomicInteger loads = new AtomicInteger();
            CountDownLatch loaderStarted = new CountDownLatch(1);
            CountDownLatch releaseLoader = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(8)) {
                List<java.util.concurrent.Future<BlockLease>> futures = new ArrayList<>();
                for (int i = 0; i < 8; i++) futures.add(executor.submit(() -> cache.acquireOrLoad(key, () -> {
                    loads.incrementAndGet(); loaderStarted.countDown();
                    try { assertTrue(releaseLoader.await(5, TimeUnit.SECONDS)); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                    return new byte[] {9};
                }, true)));
                assertTrue(loaderStarted.await(5, TimeUnit.SECONDS));
                releaseLoader.countDown();
                for (var future : futures) try (BlockLease lease = future.get(5, TimeUnit.SECONDS)) {
                    assertEquals(1, lease.rawLength());
                }
            }
            assertEquals(1, loads.get());
        }
    }

    @Test void oversizedBlocksBypassAdmission() {
        try (DataBlockCache cache = new DataBlockCache(DataBlockCache.MIN_CAPACITY, 16)) {
            int length = (int) (DataBlockCache.MIN_CAPACITY / 16 / 4);
            BlockCacheKey key = new BlockCacheKey(3, 0, length);
            try (BlockLease lease = cache.acquireOrLoad(key, () -> new byte[length], true)) {
                assertEquals(length, lease.rawLength());
            }
            assertEquals(0, cache.metrics().residentBytes());
            assertEquals(1, cache.metrics().admissionBypasses());
        }
    }
}
