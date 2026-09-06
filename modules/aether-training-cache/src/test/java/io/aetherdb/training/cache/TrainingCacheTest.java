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
}
