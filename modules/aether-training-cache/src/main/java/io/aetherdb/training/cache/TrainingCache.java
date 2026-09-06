package io.aetherdb.training.cache;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.DurabilityMode;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.engine.Aether;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.time.Duration;
import java.util.zip.CRC32C;

/** Persistent local cache for immutable preprocessing results. */
public final class TrainingCache implements AutoCloseable {
    public static final long DEFAULT_MAX_BYTES = 10L * 1024 * 1024 * 1024;
    public static final int INLINE_VALUE_THRESHOLD_BYTES = 256 * 1024;
    private static final int HEADER_BYTES = 4 + 4 + 4 + TransformationFingerprint.BYTES;
    private static final int MAGIC = 0xAE7CA001;
    private static final int SEGMENT_MAGIC = 0xAE7CA002;
    private static final int SEGMENT_METADATA_BYTES = 4 + 4 + 4 + TransformationFingerprint.BYTES + 68 + 4;
    private final AetherDatabase database;
    private final long maximumBytes;
    private final TrainingCacheDurability durability;
    private final TrainingCacheStoragePolicy storagePolicy;
    private final ExecutorService prefetchExecutor;
    private final TrainingCacheSegmentStore segmentStore;
    private final LinkedHashMap<String, Integer> entries = new LinkedHashMap<>(16, .75f, true);
    private final Map<String, CompletableFuture<byte[]>> inflight = new ConcurrentHashMap<>();
    private final LongAdder hits = new LongAdder();
    private final LongAdder misses = new LongAdder();
    private final LongAdder corruptEntries = new LongAdder();
    private final LongAdder bytesServed = new LongAdder();
    private final LongAdder bytesWritten = new LongAdder();
    private final LongAdder evictions = new LongAdder();
    private final TrainingCacheLatency getLatency = new TrainingCacheLatency();
    private final TrainingCacheLatency putLatency = new TrainingCacheLatency();
    private long diskBytes;

    public static TrainingCache open(Path directory) { return open(directory, DEFAULT_MAX_BYTES, TrainingCacheDurability.RECOVERABLE, TrainingCacheStoragePolicy.auto()); }

    public static TrainingCache open(Path directory, long maximumBytes) {
        return open(directory, maximumBytes, TrainingCacheDurability.RECOVERABLE, TrainingCacheStoragePolicy.auto());
    }

    public static TrainingCache open(Path directory, long maximumBytes, TrainingCacheDurability durability) {
        return open(directory, maximumBytes, durability, TrainingCacheStoragePolicy.auto());
    }

    public static TrainingCache open(Path directory, long maximumBytes, TrainingCacheDurability durability,
            TrainingCacheStoragePolicy storagePolicy) {
        if (maximumBytes <= 0) throw new IllegalArgumentException("maximumBytes must be positive");
        Objects.requireNonNull(durability, "durability");
        Objects.requireNonNull(storagePolicy, "storagePolicy");
        if (durability == TrainingCacheDurability.EPHEMERAL)
            return new TrainingCache(Aether.openInMemory(), maximumBytes, durability, storagePolicy, null);
        return new TrainingCache(
            Aether.open(Objects.requireNonNull(directory, "directory"),
                new AetherConfiguration(Map.of(
                    "aether.security.profile", "development",
                    "aether.storage.disk_pressure.enabled", "false"))),
            maximumBytes, durability, storagePolicy, directory);
    }

    TrainingCache(AetherDatabase database, long maximumBytes) {
        this(database, maximumBytes, TrainingCacheDurability.RECOVERABLE, TrainingCacheStoragePolicy.auto(), null);
    }

    TrainingCache(AetherDatabase database, long maximumBytes, TrainingCacheDurability durability) {
        this(database, maximumBytes, durability, TrainingCacheStoragePolicy.auto(), null);
    }

        TrainingCache(AetherDatabase database, long maximumBytes, TrainingCacheDurability durability,
            TrainingCacheStoragePolicy storagePolicy, Path directory) {
        this.database = database;
        this.maximumBytes = maximumBytes;
        this.durability = durability;
        this.storagePolicy = storagePolicy;
        this.segmentStore = directory == null ? null : new TrainingCacheSegmentStore(directory);
        this.prefetchExecutor = Executors.newFixedThreadPool(2);
        loadIndex();
    }

    public byte[] get(CacheKey key) {
        Objects.requireNonNull(key, "key");
        byte[] storageKey = key.storageKey();
        long started = System.nanoTime();
        LookupResult result = database.get(storageKey);
        getLatency.record(System.nanoTime() - started);
        if (!result.isFound()) { misses.increment(); return null; }
        try {
            byte[] payload = decode(result.value(), storageKey);
            hits.increment();
            bytesServed.add(payload.length);
            synchronized (entries) { entries.get(keyString(storageKey)); }
            return payload;
        } catch (IllegalArgumentException corrupt) {
            corruptEntries.increment();
            misses.increment();
            database.delete(storageKey);
            synchronized (entries) { removeIndex(keyString(storageKey)); }
            return null;
        }
    }

    public SegmentReference getRef(CacheKey key) {
        Objects.requireNonNull(key, "key");
        byte[] storageKey = key.storageKey();
        LookupResult result = database.get(storageKey);
        if (!result.isFound()) return null;
        byte[] encoded = result.value();
        if (encoded.length < 8 || ByteBuffer.wrap(encoded).getInt() != SEGMENT_MAGIC) return null;
        try {
            ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
            input.getInt();
            input.getInt();
            int length = input.getInt();
            byte[] checksum = new byte[TransformationFingerprint.BYTES];
            input.get(checksum);
            byte[] segment = new byte[68];
            input.get(segment);
            CRC32C crc = new CRC32C();
            crc.update(encoded, 0, encoded.length - 4);
            if ((int) crc.getValue() != input.getInt()) throw new IllegalArgumentException("metadata checksum mismatch");
            String segmentId = new String(segment, StandardCharsets.US_ASCII);
            if (!segmentId.equals(segmentName(storageKey))) throw new IllegalArgumentException("invalid segment reference");
            return new SegmentReference(segmentId, 1, 0, length, checksum);
        } catch (RuntimeException failure) {
            corruptEntries.increment();
            return null;
        }
    }

    public Map<CacheKey, SegmentReference> getManyRefs(Iterable<CacheKey> keys) {
        Map<CacheKey, SegmentReference> results = new LinkedHashMap<>();
        for (CacheKey key : keys) {
            SegmentReference reference = getRef(key);
            if (reference != null) results.put(key, reference);
        }
        return results;
    }

    /** Returns an immutable memory-mapped view for a large value, or a read-only wrapped view for inline values. */
    public ByteBuffer map(CacheKey key) {
        Objects.requireNonNull(key, "key");
        byte[] storageKey = key.storageKey();
        LookupResult result = database.get(storageKey);
        if (!result.isFound()) { misses.increment(); return null; }
        try {
            byte[] encoded = result.value();
            ByteBuffer mapped;
            if (encoded.length >= 8 && ByteBuffer.wrap(encoded).getInt() == SEGMENT_MAGIC)
                mapped = validateMappedSegment(encoded, storageKey);
            else mapped = ByteBuffer.wrap(decode(encoded, storageKey)).asReadOnlyBuffer();
            hits.increment();
            bytesServed.add(mapped.remaining());
            return mapped;
        } catch (IllegalArgumentException corrupt) {
            corruptEntries.increment();
            misses.increment();
            database.delete(storageKey);
            synchronized (entries) { removeIndex(keyString(storageKey)); }
            return null;
        }
    }

    public Map<CacheKey, byte[]> getMany(Iterable<CacheKey> keys) {
        Map<CacheKey, byte[]> results = new LinkedHashMap<>();
        for (CacheKey key : keys) {
            byte[] value = get(key);
            if (value != null) results.put(key, value);
        }
        return results;
    }

    public BatchValueResult getManyValues(Iterable<CacheKey> keys) {
        java.util.ArrayList<BatchValueResult.ValueStatus> statuses = new java.util.ArrayList<>();
        java.util.ArrayList<byte[]> values = new java.util.ArrayList<>();
        int totalBytes = 0;
        for (CacheKey key : keys) {
            byte[] storageKey = key.storageKey();
            LookupResult lookup = database.get(storageKey);
            byte[] value = null;
            if (lookup.isFound()) {
                try { value = decode(lookup.value(), storageKey); }
                catch (IllegalArgumentException corrupt) {
                    corruptEntries.increment();
                    database.delete(storageKey);
                }
            }
            if (value == null) {
                statuses.add(BatchValueResult.ValueStatus.MISS);
                values.add(null);
            } else if (isSegmentValue(lookup.value())) {
                statuses.add(BatchValueResult.ValueStatus.HIT_SEGMENT);
                values.add(null);
            } else {
                statuses.add(BatchValueResult.ValueStatus.HIT_INLINE);
                values.add(value);
                totalBytes = Math.addExact(totalBytes, value.length);
            }
        }
        ByteBuffer payload = ByteBuffer.allocate(totalBytes);
        int[] offsets = new int[values.size()];
        int[] lengths = new int[values.size()];
        for (int index = 0; index < values.size(); index++) {
            byte[] value = values.get(index);
            offsets[index] = payload.position();
            if (value != null) { lengths[index] = value.length; payload.put(value); }
        }
        payload.flip();
        return new BatchValueResult(statuses, offsets, lengths, payload);
    }

    private static boolean isSegmentValue(byte[] encoded) {
        return encoded.length >= 8 && ByteBuffer.wrap(encoded).getInt() == SEGMENT_MAGIC;
    }

    public Set<String> namespaces() {
        synchronized (entries) {
            long started = System.nanoTime();
            Set<String> namespaces = new HashSet<>();
            for (String key : entries.keySet()) namespaces.add(namespaceOf(key));
            return Set.copyOf(namespaces);
        }
    }

    public Map<String, Long> namespaceStats() {
        synchronized (entries) {
            Map<String, Long> stats = new LinkedHashMap<>();
            for (String key : entries.keySet()) stats.merge(namespaceOf(key), 1L, Long::sum);
            return Map.copyOf(stats);
        }
    }

    public void invalidateNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        deleteNamespace(namespace);
    }

    public void deleteNamespace(String namespace) {
        Objects.requireNonNull(namespace, "namespace");
        synchronized (entries) {
            Iterator<Map.Entry<String, Integer>> iterator = entries.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, Integer> entry = iterator.next();
                String key = entry.getKey();
                if (namespace.equals(namespaceOf(key))) {
                    byte[] storageKey = decodeKey(key);
                    database.delete(storageKey);
                    if (segmentStore != null) segmentStore.delete(segmentName(storageKey));
                    diskBytes -= entry.getValue();
                    iterator.remove();
                }
            }
        }
    }

    public void put(CacheKey key, byte[] payload) {
        putMany(java.util.List.of(new CacheEntry(
                Objects.requireNonNull(key, "key"), Objects.requireNonNull(payload, "payload"))));
    }

    public void putMany(Iterable<CacheEntry> values) {
        long started = System.nanoTime();
        synchronized (entries) {
            // Serialize publication, including segment creation, and validate the
            // entire batch before modifying files. A derived-artifact key is immutable.
            java.util.LinkedHashMap<CacheKey, byte[]> unique = new java.util.LinkedHashMap<>();
            for (CacheEntry entry : values) {
                byte[] previous = unique.putIfAbsent(entry.key(), entry.value());
                if (previous != null && !Arrays.equals(previous, entry.value()))
                    throw new IllegalArgumentException("conflicting values for an immutable cache key");
            }
            java.util.ArrayList<CacheEntry> pending = new java.util.ArrayList<>();
            for (var entry : unique.entrySet()) {
                byte[] storageKey = entry.getKey().storageKey();
                LookupResult existing = database.get(storageKey);
                if (existing.isFound()) {
                    if (!Arrays.equals(decode(existing.value(), storageKey), entry.getValue()))
                        throw new IllegalArgumentException("cannot overwrite an immutable cache key");
                } else pending.add(new CacheEntry(entry.getKey(), entry.getValue()));
            }
            if (pending.isEmpty()) return;
            TrainingCacheFaultHooks.reach("before-data-write");
            java.util.ArrayList<Integer> sizes = new java.util.ArrayList<>();
            try (WriteBatch batch = new WriteBatch()) {
                for (CacheEntry entry : pending) {
                    byte[] encoded = encode(entry.value(), entry.key().storageKey());
                    batch.put(entry.key().storageKey(), encoded);
                    sizes.add(encoded.length + (storagePolicy.usesSegment(entry.value().length) ? entry.value().length : 0));
                }
                TrainingCacheFaultHooks.reach("before-index-commit");
                database.write(batch, new WriteOptions(
                        durability == TrainingCacheDurability.DURABLE ? DurabilityMode.SYNC : DurabilityMode.GROUP_SYNC,
                        Duration.ofSeconds(30), false));
                TrainingCacheFaultHooks.reach("after-index-commit-before-ack");
            }
            for (int index = 0; index < pending.size(); index++) {
                CacheEntry entry = pending.get(index);
                int storedBytes = sizes.get(index);
                entries.put(keyString(entry.key().storageKey()), storedBytes);
                diskBytes += storedBytes;
                bytesWritten.add(entry.value().length);
            }
            evictIfNeeded();
            putLatency.record(System.nanoTime() - started);
        }
    }

    public void prefetch(Iterable<CacheKey> keys) {
        for (CacheKey key : keys) prefetchExecutor.submit(() -> get(key));
    }

    public byte[] getOrCompute(CacheKey key, Supplier<byte[]> computation) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(computation, "computation");
        String identity = keyString(key.storageKey());
        CompletableFuture<byte[]> promise = new CompletableFuture<>();
        CompletableFuture<byte[]> existing = inflight.putIfAbsent(identity, promise);
        if (existing != null) return existing.join().clone();
        try {
            byte[] cached = get(key);
            byte[] value = cached != null ? cached : Objects.requireNonNull(computation.get(), "computation returned null");
            if (cached == null) put(key, value);
            promise.complete(value.clone());
            return value;
        } catch (RuntimeException | Error failure) {
            promise.completeExceptionally(failure);
            throw failure;
        } finally { inflight.remove(identity, promise); }
    }

    public TrainingCacheMetrics metrics() {
        synchronized (entries) {
            return new TrainingCacheMetrics(hits.sum(), misses.sum(), corruptEntries.sum(), bytesServed.sum(),
                    bytesWritten.sum(), evictions.sum(), entries.size(), diskBytes,
                    getLatency.percentile(50), getLatency.percentile(95), getLatency.percentile(99),
                    putLatency.percentile(50), putLatency.percentile(95), putLatency.percentile(99));
        }
    }

    public TrainingCacheDurability durability() { return durability; }

    /** Metadata-only presence query; payload integrity is still checked on retrieval. */
    public boolean containsKey(CacheKey key) {
        return database.get(Objects.requireNonNull(key, "key").storageKey()).isFound();
    }

    private void loadIndex() {
        try (AetherCursor cursor = database.scanAll()) {
            while (cursor.next()) {
                byte[] key = cursor.key();
                byte[] value = cursor.value();
                try {
                    decode(value, key);
                    int storedBytes = value.length;
                    if (value.length >= 8 && ByteBuffer.wrap(value).getInt() == SEGMENT_MAGIC)
                        storedBytes = Math.toIntExact(value.length + segmentStore.size(segmentName(key)));
                    entries.put(keyString(key), storedBytes);
                    diskBytes += storedBytes;
                }
                catch (IllegalArgumentException corrupt) {
                    corruptEntries.increment();
                    database.delete(key);
                    if (segmentStore != null) segmentStore.delete(segmentName(key));
                }
            }
        }
        synchronized (entries) { evictIfNeeded(); }
    }

    private void evictIfNeeded() {
        Iterator<Map.Entry<String, Integer>> iterator = entries.entrySet().iterator();
        while (diskBytes > maximumBytes && iterator.hasNext()) {
            Map.Entry<String, Integer> entry = iterator.next();
            byte[] key = decodeKey(entry.getKey());
            database.delete(key);
            if (segmentStore != null) segmentStore.delete(segmentName(key));
            diskBytes -= entry.getValue(); iterator.remove(); evictions.increment();
        }
    }

    private void removeIndex(String key) {
        Integer size = entries.remove(key);
        if (size != null) diskBytes -= size;
    }

    private static String keyString(byte[] key) { return Base64.getUrlEncoder().withoutPadding().encodeToString(key); }

    private static byte[] decodeKey(String key) { return Base64.getUrlDecoder().decode(key); }

    private static String namespaceOf(String key) {
        byte[] bytes = decodeKey(key);
        int length = ByteBuffer.wrap(bytes).getInt();
        if (length < 1 || length > bytes.length - 4) throw new IllegalArgumentException("invalid cache key");
        return new String(bytes, 4, length, StandardCharsets.UTF_8);
    }

    private byte[] encode(byte[] payload, byte[] storageKey) {
        byte[] digest = TransformationFingerprint.sha256(payload);
        if (storagePolicy.usesSegment(payload.length)) {
            if (segmentStore == null) throw new IllegalStateException("large values require a persistent cache");
            String name = segmentName(storageKey);
            segmentStore.publish(name, payload, durability == TrainingCacheDurability.DURABLE);
            ByteBuffer metadata = ByteBuffer.allocate(SEGMENT_METADATA_BYTES).order(ByteOrder.BIG_ENDIAN);
            metadata.putInt(SEGMENT_MAGIC).putInt(1).putInt(payload.length).put(digest);
            metadata.put(name.getBytes(StandardCharsets.US_ASCII));
            CRC32C crc = new CRC32C();
            crc.update(metadata.array(), 0, metadata.position());
            metadata.putInt((int) crc.getValue());
            return metadata.array();
        }
        ByteBuffer output = ByteBuffer.allocate(HEADER_BYTES + payload.length + 4).order(ByteOrder.BIG_ENDIAN);
        output.putInt(MAGIC).putInt(1).putInt(payload.length).put(digest).put(payload);
        CRC32C crc = new CRC32C(); crc.update(output.array(), 0, output.position()); output.putInt((int) crc.getValue());
        return output.array();
    }

    private byte[] decode(byte[] encoded, byte[] storageKey) {
        if (encoded.length < HEADER_BYTES + 4) throw new IllegalArgumentException("truncated cache entry");
        ByteBuffer input = ByteBuffer.wrap(encoded).order(ByteOrder.BIG_ENDIAN);
        int magic = input.getInt();
        int version = input.getInt();
        if (magic == SEGMENT_MAGIC) {
            if (version != 1 || encoded.length != SEGMENT_METADATA_BYTES || segmentStore == null)
                throw new IllegalArgumentException("invalid segment metadata");
            int length = input.getInt();
            byte[] digest = new byte[TransformationFingerprint.BYTES];
            input.get(digest);
            byte[] nameBytes = new byte[68];
            input.get(nameBytes);
            int expectedCrc = input.getInt();
            CRC32C crc = new CRC32C();
            crc.update(encoded, 0, encoded.length - 4);
            String name = new String(nameBytes, StandardCharsets.US_ASCII);
            if ((int) crc.getValue() != expectedCrc || !name.equals(segmentName(storageKey)))
                throw new IllegalArgumentException("invalid segment reference");
            byte[] payload = segmentStore.read(name);
            if (payload.length != length || !Arrays.equals(digest, TransformationFingerprint.sha256(payload)))
                throw new IllegalArgumentException("cache segment checksum mismatch");
            return payload;
        }
        if (magic != MAGIC || version != 1) throw new IllegalArgumentException("unknown cache entry");
        int length = input.getInt();
        if (length < 0 || length != encoded.length - HEADER_BYTES - 4) throw new IllegalArgumentException("invalid payload length");
        byte[] digest = new byte[TransformationFingerprint.BYTES]; input.get(digest);
        byte[] payload = new byte[length]; input.get(payload); int expectedCrc = input.getInt();
        CRC32C crc = new CRC32C(); crc.update(encoded, 0, encoded.length - 4);
        if ((int) crc.getValue() != expectedCrc || !Arrays.equals(digest, TransformationFingerprint.sha256(payload)))
            throw new IllegalArgumentException("cache checksum mismatch");
        return payload;
    }

    private ByteBuffer validateMappedSegment(byte[] metadata, byte[] storageKey) {
        ByteBuffer input = ByteBuffer.wrap(metadata).order(ByteOrder.BIG_ENDIAN);
        if (input.getInt() != SEGMENT_MAGIC || input.getInt() != 1 || metadata.length != SEGMENT_METADATA_BYTES)
            throw new IllegalArgumentException("invalid segment metadata");
        int length = input.getInt();
        byte[] digest = new byte[TransformationFingerprint.BYTES];
        input.get(digest);
        byte[] nameBytes = new byte[68];
        input.get(nameBytes);
        int expectedCrc = input.getInt();
        CRC32C crc = new CRC32C();
        crc.update(metadata, 0, metadata.length - 4);
        String name = new String(nameBytes, StandardCharsets.US_ASCII);
        if ((int) crc.getValue() != expectedCrc || !name.equals(segmentName(storageKey)))
            throw new IllegalArgumentException("invalid segment reference");
            ByteBuffer mapped;
            try { mapped = segmentStore.mapReadOnly(name); }
            catch (IllegalArgumentException windowsFallback) {
                mapped = ByteBuffer.wrap(segmentStore.read(name)).asReadOnlyBuffer();
            }
        if (mapped.remaining() != length || !Arrays.equals(digest, digest(mapped.duplicate())))
            throw new IllegalArgumentException("cache segment checksum mismatch");
        return mapped;
    }

    private static byte[] digest(ByteBuffer input) {
        java.security.MessageDigest digest;
        try { digest = java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
        digest.update(input);
        return digest.digest();
    }

    private static String segmentName(byte[] storageKey) {
        byte[] digest = TransformationFingerprint.sha256(storageKey);
        StringBuilder name = new StringBuilder(68);
        for (byte value : digest) name.append(String.format("%02x", value));
        return name.append(".seg").toString();
    }

    @Override public void close() { prefetchExecutor.shutdownNow(); database.close(); }
}
