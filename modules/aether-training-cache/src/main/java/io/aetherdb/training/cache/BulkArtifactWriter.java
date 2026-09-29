package io.aetherdb.training.cache;

import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.engine.EmptyStoreBulkLoader;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** Opt-in offline prototype for inline immutable artifacts. Only finish() acknowledges durability. */
public final class BulkArtifactWriter implements AutoCloseable {
    private final EmptyStoreBulkLoader loader;
    private final Map<String, Long> timings = new LinkedHashMap<>();
    private long artifacts;
    private long payloadBytes;
    private boolean failed;

    public BulkArtifactWriter(Path directory) throws IOException {
        loader = new EmptyStoreBulkLoader(directory, new AetherConfiguration(Map.of(
                "aether.security.profile", "development", "aether.storage.disk_pressure.enabled", "false")));
    }

    public synchronized void addAll(Iterable<CacheEntry> entries) {
        if (failed) throw new IllegalStateException("failed bulk writer");
        try {
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
        return Map.of("status", "committed", "artifacts", artifacts, "payloadBytes", payloadBytes,
                "sha256Calls", artifacts, "admissionTimingsNs", Map.copyOf(timings), "storage", loader.finish());
    }

    @Override public void close() throws IOException { loader.close(); }

    /** Local pipe protocol only, not an online daemon opcode. EOF aborts; zero-length frame commits. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("usage: BulkArtifactWriter EMPTY_STORE");
        try (var writer = new BulkArtifactWriter(Path.of(args[0]));
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
                var entries = TrainingCacheProtocol.decodeBulkEntries(ByteBuffer.wrap(frame));
                writer.addAll(entries);
                System.out.println("STAGED " + entries.size());
            }
        }
    }
}
