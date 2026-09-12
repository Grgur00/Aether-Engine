package io.aetherdb.training.cache;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TrainingCacheTest {
    @TempDir Path temp;

    @Test
    void immutableBatchConflictHasNoPartialPublication() {
        var transform = TransformationFingerprint.ofCanonicalDescriptor("immutable-v1");
        CacheKey first = new CacheKey("immutable", "a", transform);
        CacheKey second = new CacheKey("immutable", "b", transform);
        try (TrainingCache cache = TrainingCache.open(temp.resolve("immutable"))) {
            cache.put(first, new byte[] {1});
            assertThrows(IllegalArgumentException.class, () -> cache.putMany(java.util.List.of(
                    new CacheEntry(second, new byte[] {2}), new CacheEntry(first, new byte[] {3}))));
            assertNull(cache.get(second));
            assertArrayEquals(new byte[] {1}, cache.get(first));
            assertEquals(1, cache.metrics().residentEntries());
        }
    }

    @Test
    void idempotentSegmentBatchDoesNotCountOrWriteDuplicates() {
        CacheKey key = new CacheKey("immutable", "large", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] value = new byte[512 * 1024];
        new java.util.Random(74).nextBytes(value);
        try (TrainingCache cache = TrainingCache.open(temp.resolve("idempotent"))) {
            CacheEntry entry = new CacheEntry(key, value);
            cache.putMany(java.util.List.of(entry, entry));
            long written = cache.metrics().bytesWritten();
            long disk = cache.metrics().diskBytes();
            cache.putMany(java.util.List.of(entry));
            assertEquals(value.length, written);
            assertEquals(written, cache.metrics().bytesWritten());
            assertEquals(disk, cache.metrics().diskBytes());
            value[0] ^= 1;
            assertArrayEquals(entry.value(), cache.get(key));
        }
    }

    @Test
    void competingImmutableWritersPublishExactlyOneValue() throws Exception {
        CacheKey key = new CacheKey("immutable", "race", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        try (TrainingCache cache = TrainingCache.open(temp.resolve("race")); var executor = Executors.newFixedThreadPool(8)) {
            var ready = new java.util.concurrent.CountDownLatch(8);
            var start = new java.util.concurrent.CountDownLatch(1);
            var tasks = new java.util.ArrayList<java.util.concurrent.Future<Integer>>();
            for (int i = 0; i < 8; i++) {
                final int candidate = i;
                tasks.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    try { cache.put(key, new byte[] {(byte) candidate}); return candidate; }
                    catch (IllegalArgumentException conflict) { return -1; }
                }));
            }
            boolean allReady;
            try { allReady = ready.await(10, java.util.concurrent.TimeUnit.SECONDS); }
            finally { start.countDown(); }
            assertTrue(allReady);
            int winner = -1;
            int accepted = 0;
            for (var task : tasks) {
                int result = task.get(10, java.util.concurrent.TimeUnit.SECONDS);
                if (result >= 0) { accepted++; winner = result; }
            }
            assertEquals(1, accepted);
            assertArrayEquals(new byte[] {(byte) winner}, cache.get(key));
            assertEquals(1, cache.metrics().residentEntries());
        }
    }

    @Test
    void valuesSurviveReopenAndTransformationChangesMiss() {
        CacheKey original = new CacheKey("train", "sample-1", TransformationFingerprint.of(Map.of("maxLength", 128)));
        CacheKey changed = new CacheKey("train", "sample-1", TransformationFingerprint.of(Map.of("maxLength", 256)));
        try (TrainingCache cache = TrainingCache.open(temp.resolve("cache"))) { cache.put(original, new byte[] {1, 2, 3}); }
        try (TrainingCache cache = TrainingCache.open(temp.resolve("cache"))) {
            assertArrayEquals(new byte[] {1, 2, 3}, cache.get(original));
            assertNull(cache.get(changed));
        }
    }

    @Test
    void concurrentGetOrComputeRunsOnce() throws Exception {
        try (TrainingCache cache = TrainingCache.open(temp.resolve("cache"))) {
            CacheKey key = new CacheKey("train", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
            AtomicInteger calls = new AtomicInteger();
            try (var executor = Executors.newFixedThreadPool(8)) {
                var tasks = new java.util.ArrayList<java.util.concurrent.Future<byte[]>>();
                for (int i = 0; i < 8; i++) tasks.add(executor.submit(() -> cache.getOrCompute(key, () -> { calls.incrementAndGet(); return new byte[] {9}; })));
                for (var task : tasks) assertArrayEquals(new byte[] {9}, task.get());
            }
            assertEquals(1, calls.get());
        }
    }

    @Test
    void batchLookupAndNamespaceLifecycleWork() {
        try (TrainingCache cache = TrainingCache.open(temp.resolve("cache"))) {
            CacheKey first = new CacheKey("one", "a", TransformationFingerprint.ofCanonicalDescriptor("v1"));
            CacheKey second = new CacheKey("two", "b", TransformationFingerprint.ofCanonicalDescriptor("v1"));
            cache.put(first, new byte[] {1});
            cache.put(second, new byte[] {2});
            assertEquals(2, cache.getMany(java.util.List.of(first, second)).size());
            assertEquals(java.util.Set.of("one", "two"), cache.namespaces());
            assertEquals(1L, cache.namespaceStats().get("one"));
            cache.invalidateNamespace("one");
            assertNull(cache.get(first));
            assertArrayEquals(new byte[] {2}, cache.get(second));
        }
    }

    @Test
    void failedComputationReleasesSingleFlight() {
        try (TrainingCache cache = TrainingCache.open(temp.resolve("cache"))) {
            CacheKey key = new CacheKey("train", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
            assertThrows(IllegalStateException.class, () -> cache.getOrCompute(key, () -> { throw new IllegalStateException("failed"); }));
            assertArrayEquals(new byte[] {7}, cache.getOrCompute(key, () -> new byte[] {7}));
        }
    }

    @Test
    void capacityEvictsOldestEntries() {
        try (TrainingCache cache = TrainingCache.open(temp.resolve("cache"), 100)) {
            CacheKey first = new CacheKey("train", "first", TransformationFingerprint.ofCanonicalDescriptor("v1"));
            CacheKey second = new CacheKey("train", "second", TransformationFingerprint.ofCanonicalDescriptor("v1"));
            cache.put(first, new byte[] {1, 2, 3, 4});
            cache.put(second, new byte[] {5, 6, 7, 8});
            assertTrue(cache.metrics().diskBytes() <= 100);
            assertTrue(cache.metrics().evictions() > 0);
        }
    }

    @Test
    void ephemeralModeDoesNotSurviveReopen() {
        CacheKey key = new CacheKey("train", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        try (TrainingCache cache = TrainingCache.open(temp.resolve("ephemeral"), 1_000, TrainingCacheDurability.EPHEMERAL)) {
            cache.put(key, new byte[] {1});
            assertArrayEquals(new byte[] {1}, cache.get(key));
        }
        try (TrainingCache cache = TrainingCache.open(temp.resolve("ephemeral"), 1_000, TrainingCacheDurability.EPHEMERAL)) {
            assertNull(cache.get(key));
        }
    }

    @Test
    void prefetchWarmsAnExistingEntry() throws Exception {
        CacheKey key = new CacheKey("train", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        try (TrainingCache cache = TrainingCache.open(temp.resolve("prefetch"))) {
            cache.put(key, new byte[] {3});
            cache.prefetch(java.util.List.of(key));
            for (int attempt = 0; attempt < 20 && cache.metrics().hits() == 0; attempt++)
                Thread.sleep(10);
            assertTrue(cache.metrics().hits() > 0);
        }
    }

    @Test
    void largeValuesRoundTripThroughAtomicSegment() {
        CacheKey key = new CacheKey("train", "large", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] value = new byte[TrainingCache.INLINE_VALUE_THRESHOLD_BYTES + 1];
        for (int index = 0; index < value.length; index++) value[index] = (byte) index;
        try (TrainingCache cache = TrainingCache.open(temp.resolve("large"), value.length * 2L)) {
            cache.put(key, value);
        }
        try (TrainingCache cache = TrainingCache.open(temp.resolve("large"), value.length * 2L)) {
            assertArrayEquals(value, cache.get(key));
            assertTrue(cache.metrics().diskBytes() > value.length);
        }
    }

    @Test
    void corruptedSegmentBecomesCacheMiss() throws Exception {
        CacheKey key = new CacheKey("train", "corrupt", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] value = new byte[TrainingCache.INLINE_VALUE_THRESHOLD_BYTES + 1];
        try (TrainingCache cache = TrainingCache.open(temp.resolve("corrupt"), value.length * 2L)) {
            cache.put(key, value);
        }
        Path segment = Files.list(temp.resolve("corrupt").resolve("segments")).findFirst().orElseThrow();
        byte[] bytes = Files.readAllBytes(segment);
        bytes[0] ^= 1;
        Files.write(segment, bytes);
        try (TrainingCache cache = TrainingCache.open(temp.resolve("corrupt"), value.length * 2L)) {
            assertNull(cache.get(key));
            assertEquals(1, cache.metrics().corruptEntries());
        }
    }

    @Test
    void largeValueCanBeReadThroughMappedView() {
        CacheKey key = new CacheKey("train", "mapped", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] value = new byte[TrainingCache.INLINE_VALUE_THRESHOLD_BYTES + 1];
        value[0] = 11;
        try (TrainingCache cache = TrainingCache.open(temp.resolve("mapped"), value.length * 2L)) {
            cache.put(key, value);
            var mapped = cache.map(key);
            assertNotNull(mapped);
            assertTrue(mapped.isReadOnly());
            assertEquals(value.length, mapped.remaining());
            assertEquals(11, mapped.get(0));
        }
    }

    @Test
    void storagePolicySelectsRepresentationDynamically() {
        CacheKey key = new CacheKey("policy", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] value = new byte[32];
        try (TrainingCache cache = TrainingCache.open(temp.resolve("inline-policy"), 10_000,
                TrainingCacheDurability.RECOVERABLE, TrainingCacheStoragePolicy.inline())) {
            cache.put(key, value);
            assertEquals(32 + 48, cache.metrics().diskBytes());
        }
        try (TrainingCache cache = TrainingCache.open(temp.resolve("segment-policy"), 10_000,
                TrainingCacheDurability.RECOVERABLE, TrainingCacheStoragePolicy.segment(1))) {
            cache.put(key, value);
            assertArrayEquals(value, cache.get(key));
            assertTrue(cache.metrics().diskBytes() > value.length);
        }
    }

    @Test
    void referencesExistOnlyForSegmentedValues() {
        CacheKey inline = new CacheKey("refs", "inline", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        CacheKey segmented = new CacheKey("refs", "segmented", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] value = new byte[TrainingCache.INLINE_VALUE_THRESHOLD_BYTES + 1];
        try (TrainingCache cache = TrainingCache.open(temp.resolve("refs"), value.length * 2L)) {
            cache.put(inline, new byte[] {1});
            cache.put(segmented, value);
            assertNull(cache.getRef(inline));
            assertNotNull(cache.getRef(segmented));
            assertEquals(1, cache.getManyRefs(java.util.List.of(inline, segmented)).size());
        }
    }

    @Test
    void segmentNamesKeepLegacyFormatAndBatchBuffersCannotMutateStoredValues() {
        var transform = TransformationFingerprint.ofCanonicalDescriptor("hex-compatibility");
        try (var cache = TrainingCache.open(temp.resolve("hex"), 1_000_000,
                TrainingCacheDurability.RECOVERABLE, TrainingCacheStoragePolicy.segment(1))) {
            for (int index = 0; index < 32; index++) {
                CacheKey key = new CacheKey("hex", "sample-" + index, transform);
                cache.put(key, new byte[] {(byte) index, 2});
                var legacy = new StringBuilder();
                for (byte value : TransformationFingerprint.sha256(key.storageKey()))
                    legacy.append(String.format("%02x", value));
                assertEquals(legacy + ".seg", cache.getRef(key).segmentId());
                var batch = cache.getManyValues(java.util.List.of(key));
                assertEquals(BatchValueResult.ValueStatus.HIT_SEGMENT, batch.statuses().get(0));
                assertEquals(2, batch.lengths()[0]);
                assertThrows(java.nio.ReadOnlyBufferException.class, () -> batch.payload().put(0, (byte) 99));
                byte[] returned = cache.get(key);
                returned[0] = 99;
                assertArrayEquals(new byte[] {(byte) index, 2}, cache.get(key));
            }
        }
    }
}
