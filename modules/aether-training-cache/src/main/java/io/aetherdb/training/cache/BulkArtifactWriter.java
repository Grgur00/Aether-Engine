package io.aetherdb.training.cache;

import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.engine.EmptyStoreBulkLoader;
import io.aetherdb.sstable.jfr.BulkPhaseEvent;
import io.aetherdb.training.cache.jfr.BulkPopulationEvent;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in offline prototype for inline immutable artifacts. Only finish() acknowledges durability. */
@SuppressWarnings("try")
public final class BulkArtifactWriter implements AutoCloseable {
    private final EmptyStoreBulkLoader loader;
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private long artifacts;
    private long payloadBytes;
    private boolean failed;
    private BulkPopulationEvent populationEvent;

    public BulkArtifactWriter(Path directory) throws IOException {
        this(directory, EmptyStoreBulkLoader.DEFAULT_TABLE_BYTES);
    }

    public BulkArtifactWriter(Path directory, long targetSstableBytes) throws IOException {
        loader = new EmptyStoreBulkLoader(directory, new AetherConfiguration(Map.of(
                "aether.security.profile", "development", "aether.storage.disk_pressure.enabled", "false")),
                EmptyStoreBulkLoader.DEFAULT_MAX_BUFFER_BYTES, targetSstableBytes);
        if (BulkPhaseEvent.ENABLED) {
            populationEvent = new BulkPopulationEvent();
            populationEvent.targetSstableBytes = targetSstableBytes;
            populationEvent.begin();
        }
    }

    public synchronized void addAll(Iterable<CacheEntry> entries) {
        if (failed) throw new IllegalStateException("failed bulk writer");
        try (var phase = BulkPhaseEvent.start("INTEGRITY", 0, 0, -1)) {
            long initialArtifacts = artifacts, initialBytes = payloadBytes;
            for (CacheEntry entry : entries) {
                long started = System.nanoTime();
                byte[] payload = entry.value();
                addTiming("ownedPayloadCopy", started);
                if (payload.length > TrainingCache.INLINE_VALUE_THRESHOLD_BYTES)
                    throw new IllegalArgumentException("bulk prototype supports inline artifacts only");
                started = System.nanoTime();
                byte[] sha = TransformationFingerprint.sha256(payload);
                addTiming("payloadSha256", started);
                started = System.nanoTime();
                byte[] encoded = TrainingCache.inlineEnvelope(payload, sha);
                addTiming("artifactEnvelopeAndCrc32c", started);
                started = System.nanoTime();
                loader.add(entry.key().storageKey(), encoded);
                addTiming("sortedBufferAdmission", started);
                artifacts++;
                payloadBytes += payload.length;
            }
            if (phase != null) {
                phase.records = artifacts - initialArtifacts;
                phase.bytes = payloadBytes - initialBytes;
            }
        } catch (RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    private void addTiming(String stage, long started) {
        timings.merge(stage, System.nanoTime() - started, Long::sum);
    }

    public synchronized Map<String, Object> finish() throws IOException {
        if (failed) throw new IllegalStateException("failed bulk writer");
        var storage = loader.finish();
        if (populationEvent != null) {
            populationEvent.success = true;
            populationEvent.sstableCount = ((Number) storage.get("tables")).intValue();
            finishEvent();
        }
        return Map.of("status", "committed", "artifacts", artifacts, "payloadBytes", payloadBytes,
                "sha256Calls", artifacts, "admissionTimingsNs", Map.copyOf(timings), "storage", storage);
    }

    private void finishEvent() {
        if (populationEvent == null) return;
        populationEvent.artifactCount = artifacts;
        populationEvent.payloadBytes = payloadBytes;
        populationEvent.end();
        populationEvent.commit();
        populationEvent = null;
    }

    @Override public void close() throws IOException {
        try { loader.close(); } finally { finishEvent(); }
    }

    /** Local pipe protocol only, not an online daemon opcode. EOF aborts; zero-length frame commits. */
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("usage: BulkArtifactWriter EMPTY_STORE [TARGET_SSTABLE_BYTES]");
        long target = args.length == 2 ? Long.parseLong(args[1]) : EmptyStoreBulkLoader.DEFAULT_TABLE_BYTES;
        try (var writer = new BulkArtifactWriter(Path.of(args[0]), target);
                var input = new DataInputStream(System.in)) {
            System.out.println("READY");
            while (true) {
                int length = input.readInt();
                if (length == 0) {
                    System.out.println(DiagnosticJson.encode(writer.finish()));
                    return;
                }
                if (length < 6 || length > 64 * 1024 * 1024) throw new IOException("invalid bulk frame size");
                byte[] frame = new byte[length];
                input.readFully(frame);
                java.util.List<CacheEntry> entries;
                try (var phase = BulkPhaseEvent.start("REQUEST_DECODE", 0, length, -1)) {
                    entries = TrainingCacheProtocol.decodeBulkEntries(ByteBuffer.wrap(frame));
                    if (phase != null) phase.records = entries.size();
                }
                writer.addAll(entries);
                System.out.println("STAGED " + entries.size());
            }
        }
    }
}
