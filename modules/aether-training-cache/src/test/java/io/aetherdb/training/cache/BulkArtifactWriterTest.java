package io.aetherdb.training.cache;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BulkArtifactWriterTest {
    @TempDir Path temp;

    @Test void bulkAndOnlineUseIdenticalEnvelopesAndIntegrityPolicy() throws Exception {
        var key = new CacheKey("bulk", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        byte[] payload = new byte[196849];
        new java.util.Random(23).nextBytes(payload);
        try (var bulk = new BulkArtifactWriter(temp.resolve("bulk"))) {
            bulk.addAll(List.of(new CacheEntry(key, payload)));
            assertEquals(1L, bulk.finish().get("sha256Calls"));
        }
        try (var online = TrainingCache.open(temp.resolve("online"), TrainingCache.DEFAULT_MAX_BYTES, TrainingCacheDurability.DURABLE);
                var bulk = TrainingCache.open(temp.resolve("bulk"), TrainingCache.DEFAULT_MAX_BYTES, TrainingCacheDurability.DURABLE)) {
            online.put(key, payload);
            assertArrayEquals(payload, bulk.get(key));
            assertArrayEquals(online.benchmarkDatabase().get(key.storageKey()).value(),
                    bulk.benchmarkDatabase().get(key.storageKey()).value());
            assertEquals(online.integrityPolicy(), bulk.integrityPolicy());
            var next = new CacheKey("bulk", "next", key.transform());
            bulk.put(next, payload);
            assertArrayEquals(payload, bulk.get(next));
        }
    }

    @Test void segmentPayloadRejectionPoisonsWriterAndPublishesNothing() throws Exception {
        var key = new CacheKey("bulk", "sample", TransformationFingerprint.ofCanonicalDescriptor("v1"));
        try (var bulk = new BulkArtifactWriter(temp)) {
            bulk.addAll(List.of(new CacheEntry(key, new byte[] {1})));
            assertThrows(IllegalArgumentException.class, () -> bulk.addAll(List.of(
                    new CacheEntry(key, new byte[TrainingCache.INLINE_VALUE_THRESHOLD_BYTES + 1]))));
            assertThrows(IllegalStateException.class, bulk::finish);
        }
        try (var cache = TrainingCache.open(temp)) { assertEquals(0, cache.cacheEntries()); }
    }
}
