package io.aetherdb.engine;

import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.io.DatabaseIdentityV1;
import io.aetherdb.io.DatabaseLock;
import io.aetherdb.io.PathSecurityValidator;
import io.aetherdb.reliability.CrashPointRegistry;
import io.aetherdb.sstable.InternalKey;
import io.aetherdb.sstable.SSTableBuilder;
import io.aetherdb.sstable.SSTableFinishTrace;
import io.aetherdb.sstable.SSTableReader;
import io.aetherdb.sstable.jfr.BulkPhaseEvent;
import io.aetherdb.sstable.manifest.ManifestEdit;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
import io.aetherdb.sstable.manifest.VersionSet;
import io.aetherdb.wal.format.WalFormatV1;
import io.aetherdb.wal.format.WalSegmentHeader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Experimental offline, empty-store-only loader. Staging is not a durable acknowledgement. */
@SuppressWarnings("try")
public final class EmptyStoreBulkLoader implements AutoCloseable {
    public static final long DEFAULT_MAX_BUFFER_BYTES = 512L * 1024 * 1024;
    public static final long DEFAULT_TABLE_BYTES = 32L * 1024 * 1024;
    private final Path root;
    private final DatabaseLock lock;
    private final VersionSet versions;
    private final UUID databaseId;
    private final long maxBytes;
    private final long tableBytes;
    private final TreeMap<byte[], byte[]> entries = new TreeMap<>(Arrays::compareUnsigned);
    private long bufferedBytes;
    private boolean finished;
    private boolean failed;
    private boolean closed;

    public EmptyStoreBulkLoader(Path directory, AetherConfiguration configuration) throws IOException {
        this(directory, configuration, DEFAULT_MAX_BUFFER_BYTES, DEFAULT_TABLE_BYTES);
    }

    public EmptyStoreBulkLoader(Path directory, AetherConfiguration configuration, long maxBytes, long tableBytes)
            throws IOException {
        if (maxBytes < 1 || tableBytes < 1 || tableBytes > maxBytes)
            throw new IllegalArgumentException("invalid bulk memory/table limits");
        this.maxBytes = maxBytes;
        this.tableBytes = tableBytes;
        // Reuse canonical identity/bootstrap/recovery, then recheck under the exclusive offline lock.
        if (!Files.exists(directory.resolve("DB-IDENTITY"))) {
            Aether.open(directory, configuration).close();
        }
        root = PathSecurityValidator.validateRoot(directory.toAbsolutePath().normalize(), true);
        DatabaseLock acquired = DatabaseLock.acquire(root);
        VersionSet opened = null;
        try {
            databaseId = DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY"))).databaseId();
            opened = VersionSet.recover(root, databaseId);
            var version = opened.current();
            Path wal = root.resolve(WalFormatV1.fileName(version.minimumWalFileNumber()));
            if (!version.allFiles().isEmpty() || version.lastAssignedSequence() != 0
                    || version.persistedSequenceWatermark() != 0 || Files.size(wal) != WalFormatV1.HEADER_BLOCK_BYTES)
                throw new IllegalStateException("bulk load requires a never-populated empty database");
            versions = opened;
            lock = acquired;
        } catch (Throwable error) {
            if (opened != null) try { opened.close(); } catch (Throwable close) { error.addSuppressed(close); }
            try { acquired.close(); } catch (Throwable close) { error.addSuppressed(close); }
            throw error;
        }
    }

    public synchronized void add(byte[] key, byte[] value) {
        requireStaging();
        try {
            if (key == null || value == null || key.length > io.aetherdb.api.WriteBatch.MAX_KEY_BYTES)
                throw new IllegalArgumentException("invalid bulk entry");
            long next = Math.addExact(bufferedBytes, Math.addExact((long) key.length, value.length));
            if (next > maxBytes || entries.size() >= 100_000)
                throw new IllegalArgumentException("prototype bulk memory/entry limit exceeded");
            if (entries.containsKey(key)) throw new IllegalArgumentException("duplicate bulk key");
            entries.put(key.clone(), value.clone());
            bufferedBytes = next;
        } catch (RuntimeException error) {
            failed = true;
            throw error;
        }
    }

    /** One manifest edit makes every staged artifact visible together; failures require reopen/recovery. */
    public synchronized Map<String, Object> finish() throws IOException {
        requireStaging();
        if (entries.isEmpty()) throw new IllegalStateException("empty bulk transaction");
        finished = true;
        var timings = new LinkedHashMap<String, Long>();
        var tableTimings = new ArrayList<Map<String, Object>>();
        var additions = new ArrayList<ManifestFileMetadata>();
        var manifestTimings = new LinkedHashMap<String, Long>();
        long started = System.nanoTime();
        long nextFile = versions.current().nextFileNumber();
        long sequence = 0;
        var iterator = entries.entrySet().iterator();
        try {
            while (iterator.hasNext()) {
                long file = nextFile++;
                String name = VersionSet.sstableName(file);
                Path temporary = root.resolve(name + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
                Path target = root.resolve(name);
                SSTableBuilder builder = new SSTableBuilder(temporary, file, databaseId, System.currentTimeMillis());
                long bytes = 0;
                try (var phase = BulkPhaseEvent.start("SORT_OR_PARTITION", 0, 0, additions.size())) {
                    do {
                        var entry = iterator.next();
                        builder.add(new InternalKey(entry.getKey(), ++sequence, (byte) 1), entry.getValue());
                        bytes += entry.getKey().length + entry.getValue().length;
                    } while (iterator.hasNext() && bytes < tableBytes);
                    if (phase != null) phase.bytes = bytes;
                }
                var trace = new SSTableFinishTrace(true);
                io.aetherdb.sstable.TableFileMetadata built;
                try (var phase = BulkPhaseEvent.start("SSTABLE_BUILD", 0, bytes, additions.size())) {
                    built = builder.finish(trace);
                }
                CrashPointRegistry.hit("bulk.after_table_force");
                try (var phase = BulkPhaseEvent.start("SSTABLE_RENAME", built.entryCount(), built.fileSize(), additions.size())) {
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                }
                CrashPointRegistry.hit("bulk.after_table_rename");
                var metadata = new ManifestFileMetadata(file, 1, built.fileSize(), built.entryCount(),
                        built.smallestSequence(), built.largestSequence(), built.smallestInternalKey(), built.largestInternalKey());
                long verificationStarted = System.nanoTime();
                CrashPointRegistry.hit("bulk.before_verification");
                try (var phase = BulkPhaseEvent.start("SSTABLE_VERIFY", built.entryCount(), built.fileSize(), additions.size())) {
                    SSTableReader.open(target, databaseId, metadata).close();
                }
                timings.merge("verificationNs", System.nanoTime() - verificationStarted, Long::sum);
                CrashPointRegistry.hit("bulk.after_verification");
                additions.add(metadata);
                tableTimings.add(Map.of("file", file, "bytes", built.fileSize(), "entries", built.entryCount(),
                        "totalNs", trace.totalNs(), "stagesNs", trace.stagesNs()));
                CrashPointRegistry.hit("bulk.after_table");
            }
            timings.put("sstableBuildFinishRenameVerify", System.nanoTime() - started);
            started = System.nanoTime();
            long oldWal = versions.current().minimumWalFileNumber();
            long newWal = Math.addExact(oldWal, 1);
            Path newWalPath = root.resolve(WalFormatV1.fileName(newWal));
            try (FileChannel channel = FileChannel.open(newWalPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                var header = new WalSegmentHeader(databaseId, newWal, oldWal, sequence + 1, System.currentTimeMillis());
                ByteBuffer bytes = ByteBuffer.wrap(header.encodeBlock());
                while (bytes.hasRemaining()) channel.write(bytes);
                channel.force(true);
            }
            syncDirectory(root);
            timings.put("emptyWalHeaderAndDirectoryForce", System.nanoTime() - started);
            CrashPointRegistry.hit("bulk.before_manifest");
            started = System.nanoTime();
            versions.logAndApplyMeasured(new ManifestEdit(ManifestEdit.Kind.DELTA,
                    versions.current().manifestEditNumber() + 1, nextFile, sequence, sequence, newWal,
                    additions, List.of()), manifestTimings);
            timings.put("atomicManifestPublication", System.nanoTime() - started);
            CrashPointRegistry.hit("bulk.after_manifest");
            started = System.nanoTime();
            try (var phase = BulkPhaseEvent.start("QUIESCE", sequence, bufferedBytes, -1)) {
                Files.delete(root.resolve(WalFormatV1.fileName(oldWal)));
                syncDirectory(root);
            }
            timings.put("obsoleteWalCleanup", System.nanoTime() - started);
            var result = new LinkedHashMap<String, Object>();
            result.putAll(Map.of("entries", sequence, "tables", additions.size(), "bufferedBytes", bufferedBytes,
                    "timingsNs", timings, "sstableFinishes", tableTimings,
                    "walPayloadBytes", 0, "memtableInsertions", 0, "level", 1));
            result.put("targetSstableBytes", tableBytes);
            result.put("peakBufferedBytes", bufferedBytes);
            result.put("manifest", manifestTimings);
            result.put("manifestProtocol", "existing append-only forced record; temp write/rename/directory force not applicable to this edit; directory barriers recorded separately");
            return result;
        } catch (Throwable error) {
            failed = true;
            // Never remove possible manifest dependencies after an indeterminate publication.
            throw new IOException("bulk commit failed; reopen and validate outcome before any retry", error);
        } finally {
            entries.clear();
        }
    }

    private void requireStaging() {
        if (closed || failed || finished) throw new IllegalStateException("bulk writer is closed, failed, or finalized");
    }

    private static void syncDirectory(Path root) throws IOException {
        try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
            directory.force(true);
        } catch (java.nio.file.AccessDeniedException error) {
            if (!System.getProperty("os.name", "").startsWith("Windows")) throw error;
        }
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        entries.clear();
        try { versions.close(); } finally { lock.close(); }
    }
}
