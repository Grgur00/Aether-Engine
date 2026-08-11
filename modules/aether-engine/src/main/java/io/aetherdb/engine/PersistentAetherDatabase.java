package io.aetherdb.engine;

import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.Snapshot;
import io.aetherdb.api.WriteBatch;
import io.aetherdb.api.WriteOptions;
import io.aetherdb.api.WriteResult;
import io.aetherdb.api.exceptions.AetherClosedException;
import io.aetherdb.api.exceptions.AetherException;
import io.aetherdb.api.exceptions.DatabaseOpenException;
import io.aetherdb.api.exceptions.SnapshotException;
import io.aetherdb.api.exceptions.SnapshotLimitExceededException;
import io.aetherdb.api.result.LookupResult;
import io.aetherdb.io.DatabaseIdentityV1;
import io.aetherdb.io.DatabaseLock;
import io.aetherdb.io.FormatOptionsV1;
import io.aetherdb.io.PathSecurityValidator;
import io.aetherdb.lsm.compaction.CompactionDroppingIterator;
import io.aetherdb.lsm.compaction.LevelCompactionConfig;
import io.aetherdb.lsm.iterator.InternalEntry;
import io.aetherdb.lsm.iterator.ListInternalIterator;
import io.aetherdb.lsm.pressure.WritePressureController;
import io.aetherdb.lsm.pressure.WritePressureInput;
import io.aetherdb.lsm.pressure.WritePressureSnapshot;
import io.aetherdb.lsm.pressure.WritePressureState;
import io.aetherdb.memory.NativeMemoryBudget;
import io.aetherdb.memory.RegionConfig;
import io.aetherdb.memtable.reference.VersionedKeyValueStore;
import io.aetherdb.memtable.skiplist.MemTableLookupResult;
import io.aetherdb.memtable.skiplist.NativeSkipListMemTable;
import io.aetherdb.sstable.InternalKey;
import io.aetherdb.sstable.SSTableBuilder;
import io.aetherdb.sstable.SSTableEntry;
import io.aetherdb.sstable.SSTableLookup;
import io.aetherdb.sstable.SSTableReader;
import io.aetherdb.sstable.TableFileMetadata;
import io.aetherdb.sstable.manifest.ManifestEdit;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
import io.aetherdb.sstable.manifest.VersionSet;
import io.aetherdb.wal.format.WalFormatV1;
import io.aetherdb.wal.format.WalFragmentCodec;
import io.aetherdb.wal.format.WalSegmentHeader;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;

/** WAL-first local LSM coordinator using native MemTables, immutable SSTables, and a VersionSet. */
@SuppressWarnings("preview")
final class PersistentAetherDatabase implements AetherDatabase {
    private static final String IDENTITY = "DB-IDENTITY";
    private static final String OPTIONS = "FORMAT-OPTIONS";
    private static final int GROUP_HEADER_BYTES = 48;
    private static final int MAXIMUM_SNAPSHOTS = 1_024;
    private static final long MEMTABLE_BYTES = RegionConfig.DEFAULT_CAPACITY_BYTES;
    private static final LevelCompactionConfig COMPACTION_CONFIG = LevelCompactionConfig.defaults();
    private static final WritePressureController PRESSURE = new WritePressureController();
    private static final Comparator<byte[]> BYTE_ORDER = Arrays::compareUnsigned;

    private final Object snapshotIdentity = new Object();
    private final Set<SnapshotHandle> snapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Path root;
    private final DatabaseLock lock;
    private final UUID databaseId;
    private FileChannel wal;
    private final VersionSet versions;
    private final NativeMemoryBudget nativeBudget;
    private final List<SSTableReader> tables = new ArrayList<>();
    private final CommitCoordinator commits = new CommitCoordinator();
    private NativeSkipListMemTable active;
    private long lastVisibleSequence;
    private long nextSnapshotId = 1;
    private int walRecordNumber;
    private long walSegmentNumber;
    private long memTableNumber;
    private long walForceCount;
    private Throwable backgroundFailure;
    private boolean closed;

    /** Opens or creates the process-exclusive local database. */
    static PersistentAetherDatabase open(Path requested) {
        DatabaseLock lock = null;
        FileChannel wal = null;
        VersionSet versions = null;
        List<SSTableReader> openedTables = new ArrayList<>();
        NativeSkipListMemTable active = null;
        try {
            Path absolute = requested.toAbsolutePath().normalize();
            Files.createDirectories(absolute);
            Path root = PathSecurityValidator.validateRoot(absolute, true);
            lock = DatabaseLock.acquire(root);
            boolean newDatabase = !Files.exists(root.resolve(IDENTITY));
            if (newDatabase) initializeIdentityAndWal(root);
            else validateIdentityPair(root);
            DatabaseIdentityV1 identity =
                    DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve(IDENTITY)));
            if (newDatabase) {
                ManifestEdit snapshot =
                        new ManifestEdit(
                                ManifestEdit.Kind.SNAPSHOT, 1, 2, 0, 0, 1, List.of(), List.of());
                versions =
                        VersionSet.create(
                                root,
                                identity.databaseId(),
                                1,
                                snapshot,
                                System.currentTimeMillis());
            } else versions = VersionSet.recover(root, identity.databaseId());
            if (versions.current().minimumWalFileNumber() == 0)
                initializeCheckpointWal(root, identity.databaseId(), versions);
            cleanupObsoleteWalFiles(root, versions.current().minimumWalFileNumber());
            for (ManifestFileMetadata file : versions.current().allFiles()) {
                openedTables.add(
                        SSTableReader.open(
                                root.resolve(VersionSet.sstableName(file.fileNumber())),
                                identity.databaseId(),
                                file));
            }
            long walSegment = versions.current().minimumWalFileNumber();
            Path walPath = root.resolve(WalFormatV1.fileName(walSegment));
            wal = FileChannel.open(walPath, StandardOpenOption.READ, StandardOpenOption.WRITE);
            NativeMemoryBudget nativeBudget = new NativeMemoryBudget(MEMTABLE_BYTES);
            active = createMemTable(nativeBudget, identity.databaseId(), 1);
            WalRecovery recovery =
                    recoverWal(
                            wal,
                            identity.databaseId(),
                            walSegment,
                            versions.current().persistedSequenceWatermark(),
                            versions.current().lastAssignedSequence(),
                            active);
            wal.truncate(recovery.validEnd());
            wal.position(recovery.validEnd());
            return new PersistentAetherDatabase(
                    root,
                    lock,
                    identity.databaseId(),
                    wal,
                    versions,
                    openedTables,
                    active,
                    nativeBudget,
                    walSegment,
                    recovery.lastSequence(),
                    recovery.records());
        } catch (Throwable failure) {
            closeSuppressed(active, failure);
            for (SSTableReader table : openedTables) closeSuppressed(table, failure);
            closeSuppressed(versions, failure);
            closeSuppressed(wal, failure);
            closeSuppressed(lock, failure);
            if (failure instanceof DatabaseOpenException exception) throw exception;
            throw new DatabaseOpenException(
                    "cannot open persistent database: " + requested, failure);
        }
    }

    private PersistentAetherDatabase(
            Path root,
            DatabaseLock lock,
            UUID databaseId,
            FileChannel wal,
            VersionSet versions,
            List<SSTableReader> tables,
            NativeSkipListMemTable active,
            NativeMemoryBudget nativeBudget,
            long walSegmentNumber,
            long lastSequence,
            int records) {
        this.root = root;
        this.lock = lock;
        this.databaseId = databaseId;
        this.wal = wal;
        this.versions = versions;
        this.tables.addAll(tables);
        this.active = active;
        this.nativeBudget = nativeBudget;
        this.lastVisibleSequence = lastSequence;
        this.walSegmentNumber = walSegmentNumber;
        this.walRecordNumber = records;
        this.memTableNumber = 1;
    }

    @Override
    public void put(byte[] key, byte[] value) {
        try (WriteBatch batch = new WriteBatch()) {
            batch.put(key, value);
            write(batch);
        }
    }

    @Override
    public void delete(byte[] key) {
        try (WriteBatch batch = new WriteBatch()) {
            batch.delete(key);
            write(batch);
        }
    }

    @Override
    public synchronized LookupResult get(byte[] key) {
        return lookup(key, lastVisibleSequence);
    }

    @Override
    public synchronized LookupResult get(byte[] key, Snapshot snapshot) {
        return lookup(key, validateSnapshot(snapshot).sequence());
    }

    @Override
    public synchronized Snapshot newSnapshot() {
        ensureOpen();
        if (snapshots.size() >= MAXIMUM_SNAPSHOTS)
            throw new SnapshotLimitExceededException("active snapshot limit exceeded");
        if (nextSnapshotId <= 0) throw new SnapshotException("snapshot ID exhausted");
        SnapshotHandle[] holder = new SnapshotHandle[1];
        SnapshotHandle handle =
                new SnapshotHandle(
                        snapshotIdentity,
                        nextSnapshotId++,
                        lastVisibleSequence,
                        () -> snapshots.remove(holder[0]));
        holder[0] = handle;
        snapshots.add(handle);
        return handle;
    }

    @Override
    public synchronized AetherCursor scan(byte[] startInclusive, byte[] endExclusive) {
        return scanAt(startInclusive, endExclusive, lastVisibleSequence);
    }

    @Override
    public synchronized AetherCursor scan(
            byte[] startInclusive, byte[] endExclusive, Snapshot snapshot) {
        return scanAt(startInclusive, endExclusive, validateSnapshot(snapshot).sequence());
    }

    @Override
    public synchronized AetherCursor scanAll() {
        return scanAt(null, null, lastVisibleSequence);
    }

    @Override
    public synchronized AetherCursor scanAll(Snapshot snapshot) {
        return scanAt(null, null, validateSnapshot(snapshot).sequence());
    }

    @Override
    public void write(WriteBatch batch) {
        write(batch, WriteOptions.defaults());
    }

    @Override
    public WriteResult write(WriteBatch batch, WriteOptions options) {
        return commits.submit(
                Objects.requireNonNull(batch, "batch"), Objects.requireNonNull(options, "options"));
    }

    @Override
    public synchronized boolean isClosed() {
        return closed;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        Throwable failure = null;
        try {
            forceWal();
            flushActive();
        } catch (Throwable exception) {
            failure = exception;
        }
        closed = true;
        for (SnapshotHandle snapshot : List.copyOf(snapshots)) snapshot.invalidate();
        snapshots.clear();
        try {
            active.close();
        } catch (Throwable exception) {
            failure = merge(failure, exception);
        }
        for (SSTableReader table : tables)
            try {
                table.close();
            } catch (Throwable exception) {
                failure = merge(failure, exception);
            }
        try {
            versions.close();
        } catch (Throwable exception) {
            failure = merge(failure, exception);
        }
        try {
            wal.close();
        } catch (Throwable exception) {
            failure = merge(failure, exception);
        }
        try {
            lock.close();
        } catch (Throwable exception) {
            failure = merge(failure, exception);
        }
        if (failure != null) throw new AetherException("persistent database close failed", failure);
    }

    private LookupResult lookup(byte[] key, long visibleSequence) {
        ensureOpen();
        validateKey(key);
        MemTableLookupResult memory = active.get(key, visibleSequence);
        if (memory.kind() == MemTableLookupResult.Kind.VALUE)
            return LookupResult.found(memory.value());
        if (memory.kind() == MemTableLookupResult.Kind.TOMBSTONE) return LookupResult.notFound();
        SSTableLookup best = new SSTableLookup.Absent();
        long bestSequence = -1;
        for (SSTableReader table : tables) {
            SSTableLookup candidate = table.lookup(key, visibleSequence);
            long sequence =
                    candidate instanceof SSTableLookup.Found found
                            ? found.sequence()
                            : candidate instanceof SSTableLookup.Tombstone tombstone
                                    ? tombstone.sequence()
                                    : -1;
            if (sequence > bestSequence) {
                best = candidate;
                bestSequence = sequence;
            }
        }
        return best instanceof SSTableLookup.Found found
                ? LookupResult.found(found.value())
                : LookupResult.notFound();
    }

    private AetherCursor scanAt(byte[] startInclusive, byte[] endExclusive, long visibleSequence) {
        ensureOpen();
        if (startInclusive != null) validateKey(startInclusive);
        if (endExclusive != null) validateKey(endExclusive);
        if (startInclusive != null
                && endExclusive != null
                && BYTE_ORDER.compare(startInclusive, endExclusive) > 0) {
            throw new IllegalArgumentException("scan start must not be greater than end");
        }
        TreeSet<byte[]> keys = new TreeSet<>(BYTE_ORDER);
        keys.addAll(active.userKeys());
        for (SSTableReader table : tables)
            for (SSTableEntry entry : table.entries()) keys.add(entry.key().userKey());
        List<VersionedKeyValueStore.VisibleEntry> rows = new ArrayList<>();
        for (byte[] key : keys) {
            if (startInclusive != null && BYTE_ORDER.compare(key, startInclusive) < 0) continue;
            if (endExclusive != null && BYTE_ORDER.compare(key, endExclusive) >= 0) break;
            LookupResult value = lookup(key, visibleSequence);
            if (value.isFound())
                rows.add(new VersionedKeyValueStore.VisibleEntry(key, value.value()));
        }
        return new PersistentListCursor(this, rows);
    }

    private void flushActive() throws IOException {
        if (active.entryCount() == 0) return;
        active.freeze();
        long fileNumber = versions.current().nextFileNumber();
        String finalName = VersionSet.sstableName(fileNumber);
        Path temporary =
                root.resolve(finalName + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        Path target = root.resolve(finalName);
        TableFileMetadata built;
        try {
            SSTableBuilder builder =
                    new SSTableBuilder(
                            temporary, fileNumber, databaseId, System.currentTimeMillis());
            for (NativeSkipListMemTable.InternalEntry entry : active.internalEntries()) {
                builder.add(
                        new InternalKey(
                                entry.key(), entry.sequence(), (byte) (entry.tombstone() ? 2 : 1)),
                        entry.value());
            }
            built = builder.finish();
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
        ManifestFileMetadata added =
                new ManifestFileMetadata(
                        fileNumber,
                        0,
                        built.fileSize(),
                        built.entryCount(),
                        built.smallestSequence(),
                        built.largestSequence(),
                        built.smallestInternalKey(),
                        built.largestInternalKey());
        long replacementWalNumber = Math.addExact(walSegmentNumber, 1);
        Path replacementWalPath = root.resolve(WalFormatV1.fileName(replacementWalNumber));
        FileChannel replacementWal =
                createWalSegment(
                        replacementWalPath,
                        databaseId,
                        replacementWalNumber,
                        walSegmentNumber,
                        Math.addExact(lastVisibleSequence, 1));
        ManifestEdit delta =
                new ManifestEdit(
                        ManifestEdit.Kind.DELTA,
                        versions.current().manifestEditNumber() + 1,
                        fileNumber + 1,
                        lastVisibleSequence,
                        lastVisibleSequence,
                        replacementWalNumber,
                        List.of(added),
                        List.of());
        try {
            versions.logAndApply(delta);
        } catch (Throwable failure) {
            closeSuppressed(replacementWal, failure);
            try {
                Files.deleteIfExists(replacementWalPath);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("WAL rotation publication failed", failure);
        }
        FileChannel obsoleteWal = wal;
        Path obsoleteWalPath = root.resolve(WalFormatV1.fileName(walSegmentNumber));
        wal = replacementWal;
        walSegmentNumber = replacementWalNumber;
        walRecordNumber = 0;
        obsoleteWal.close();
        Files.delete(obsoleteWalPath);
        syncDirectory(root);
        tables.add(SSTableReader.open(target, databaseId, added));
        NativeSkipListMemTable previous = active;
        previous.retire();
        active = createMemTable(nativeBudget, databaseId, ++memTableNumber);
        compactIfNeeded();
    }

    private void compactIfNeeded() throws IOException {
        boolean changed;
        do {
            changed = false;
            List<ManifestFileMetadata> levelZero = versions.current().files(0);
            if (levelZero.size() >= 4) {
                compactSelection(levelZero, 1);
                changed = true;
                continue;
            }
            for (int level = 1; level <= 5; level++) {
                long levelBytes = 0;
                for (ManifestFileMetadata file : versions.current().files(level))
                    levelBytes = Math.addExact(levelBytes, file.fileSize());
                if (levelBytes > COMPACTION_CONFIG.targetBytes(level)) {
                    compactSelection(List.of(versions.current().files(level).get(0)), level + 1);
                    changed = true;
                    break;
                }
            }
        } while (changed);
    }

    private void compactSelection(List<ManifestFileMetadata> primaryInputs, int outputLevel)
            throws IOException {
        int inputLevel = outputLevel - 1;
        List<ManifestFileMetadata> selectedPrimary = new ArrayList<>(primaryInputs);
        List<ManifestFileMetadata> outputInputs = new ArrayList<>();
        byte[] smallest;
        byte[] largest;
        boolean expanded;
        do {
            List<ManifestFileMetadata> selected = new ArrayList<>(selectedPrimary);
            selected.addAll(outputInputs);
            smallest =
                    selected.stream()
                            .map(ManifestFileMetadata::smallestUserKey)
                            .min(BYTE_ORDER)
                            .orElseThrow();
            largest =
                    selected.stream()
                            .map(ManifestFileMetadata::largestUserKey)
                            .max(BYTE_ORDER)
                            .orElseThrow();
            int before = selectedPrimary.size() + outputInputs.size();
            for (ManifestFileMetadata file : versions.current().files(inputLevel)) {
                if (!selectedPrimary.contains(file) && overlaps(file, smallest, largest))
                    selectedPrimary.add(file);
            }
            for (ManifestFileMetadata file : versions.current().files(outputLevel)) {
                if (!outputInputs.contains(file) && overlaps(file, smallest, largest))
                    outputInputs.add(file);
            }
            expanded = before != selectedPrimary.size() + outputInputs.size();
        } while (expanded);
        List<ManifestFileMetadata> inputs = new ArrayList<>(selectedPrimary);
        inputs.addAll(outputInputs);
        Set<Long> inputNumbers = new java.util.HashSet<>();
        for (ManifestFileMetadata input : inputs) inputNumbers.add(input.fileNumber());

        List<InternalEntry> merged = new ArrayList<>();
        for (SSTableReader table : tables)
            if (inputNumbers.contains(table.metadata().fileNumber())) {
                for (SSTableEntry entry : table.entries()) {
                    InternalKey key = entry.key();
                    merged.add(
                            key.type() == 2
                                    ? InternalEntry.tombstone(key.userKey(), key.sequence())
                                    : InternalEntry.value(
                                            key.userKey(), key.sequence(), entry.value()));
                }
            }
        merged.sort(InternalEntry::compareTo);
        merged = deduplicateInternalEntries(merged);
        long oldestSnapshot =
                snapshots.stream()
                        .mapToLong(SnapshotHandle::sequence)
                        .min()
                        .orElse(lastVisibleSequence);
        List<InternalEntry> retained = new ArrayList<>();
        try (CompactionDroppingIterator dropping =
                new CompactionDroppingIterator(
                        new ListInternalIterator(merged),
                        oldestSnapshot,
                        key -> isBaseLevelForKey(key, outputLevel))) {
            while (dropping.next()) retained.add(dropping.current());
        }

        List<List<InternalEntry>> partitions =
                partitionCompactionOutput(
                        retained, COMPACTION_CONFIG.targetOutputFileBytes(outputLevel));
        List<ManifestFileMetadata> additions = new ArrayList<>();
        List<Path> created = new ArrayList<>();
        long nextFile = versions.current().nextFileNumber();
        try {
            for (List<InternalEntry> partition : partitions) {
                long fileNumber = nextFile++;
                String name = VersionSet.sstableName(fileNumber);
                Path temporary =
                        root.resolve(
                                name + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
                Path target = root.resolve(name);
                try {
                    SSTableBuilder builder =
                            new SSTableBuilder(
                                    temporary, fileNumber, databaseId, System.currentTimeMillis());
                    for (InternalEntry entry : partition)
                        builder.add(
                                new InternalKey(
                                        entry.userKey(),
                                        entry.sequence(),
                                        (byte)
                                                (entry.type() == InternalEntry.Type.TOMBSTONE
                                                        ? 2
                                                        : 1)),
                                entry.type() == InternalEntry.Type.TOMBSTONE
                                        ? new byte[0]
                                        : entry.value());
                    TableFileMetadata built = builder.finish();
                    Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                    created.add(target);
                    additions.add(
                            new ManifestFileMetadata(
                                    fileNumber,
                                    outputLevel,
                                    built.fileSize(),
                                    built.entryCount(),
                                    built.smallestSequence(),
                                    built.largestSequence(),
                                    built.smallestInternalKey(),
                                    built.largestInternalKey()));
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            if (!created.isEmpty()) syncDirectory(root);
            List<io.aetherdb.sstable.manifest.ManifestDeletion> deletions =
                    inputs.stream()
                            .map(
                                    file ->
                                            new io.aetherdb.sstable.manifest.ManifestDeletion(
                                                    file.fileNumber(), file.level()))
                            .toList();
            ManifestEdit edit =
                    new ManifestEdit(
                            ManifestEdit.Kind.DELTA,
                            versions.current().manifestEditNumber() + 1,
                            nextFile,
                            lastVisibleSequence,
                            versions.current().persistedSequenceWatermark(),
                            walSegmentNumber,
                            additions,
                            deletions);
            versions.logAndApply(edit);
        } catch (Throwable failure) {
            for (Path path : created)
                try {
                    Files.deleteIfExists(path);
                } catch (IOException cleanup) {
                    failure.addSuppressed(cleanup);
                }
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("compaction output failed", failure);
        }

        List<SSTableReader> replacements = new ArrayList<>();
        for (ManifestFileMetadata addition : additions)
            replacements.add(
                    SSTableReader.open(
                            root.resolve(VersionSet.sstableName(addition.fileNumber())),
                            databaseId,
                            addition));
        List<SSTableReader> obsolete =
                tables.stream()
                        .filter(table -> inputNumbers.contains(table.metadata().fileNumber()))
                        .toList();
        tables.removeAll(obsolete);
        tables.addAll(replacements);
        for (SSTableReader table : obsolete) table.close();
        for (ManifestFileMetadata input : inputs)
            Files.delete(root.resolve(VersionSet.sstableName(input.fileNumber())));
        syncDirectory(root);
    }

    private boolean isBaseLevelForKey(byte[] key, int outputLevel) {
        for (int level = outputLevel + 1; level <= 6; level++)
            for (ManifestFileMetadata file : versions.current().files(level)) {
                if (BYTE_ORDER.compare(file.smallestUserKey(), key) <= 0
                        && BYTE_ORDER.compare(key, file.largestUserKey()) <= 0) return false;
            }
        return true;
    }

    private static boolean overlaps(ManifestFileMetadata file, byte[] smallest, byte[] largest) {
        return BYTE_ORDER.compare(file.smallestUserKey(), largest) <= 0
                && BYTE_ORDER.compare(smallest, file.largestUserKey()) <= 0;
    }

    private static List<InternalEntry> deduplicateInternalEntries(List<InternalEntry> sorted)
            throws IOException {
        List<InternalEntry> result = new ArrayList<>();
        for (InternalEntry entry : sorted) {
            if (!result.isEmpty() && result.get(result.size() - 1).sameIdentity(entry)) {
                InternalEntry previous = result.get(result.size() - 1);
                if (entry.type() == InternalEntry.Type.VALUE
                        && !Arrays.equals(previous.value(), entry.value())) {
                    throw new IOException("duplicate internal identity has different values");
                }
            } else result.add(entry);
        }
        return result;
    }

    private static List<List<InternalEntry>> partitionCompactionOutput(
            List<InternalEntry> entries, long targetBytes) {
        if (entries.isEmpty()) return List.of();
        List<List<InternalEntry>> result = new ArrayList<>();
        List<InternalEntry> current = new ArrayList<>();
        long bytes = 0;
        byte[] previousKey = null;
        for (InternalEntry entry : entries) {
            long entryBytes =
                    entry.userKey().length
                            + 32L
                            + (entry.type() == InternalEntry.Type.VALUE ? entry.value().length : 0);
            if (!current.isEmpty()
                    && bytes + entryBytes > targetBytes
                    && !Arrays.equals(previousKey, entry.userKey())) {
                result.add(List.copyOf(current));
                current.clear();
                bytes = 0;
            }
            current.add(entry);
            bytes += entryBytes;
            previousKey = entry.userKey();
        }
        if (!current.isEmpty()) result.add(List.copyOf(current));
        return List.copyOf(result);
    }

    private static long requiredNativeBytes(WriteBatch batch) {
        long bytes = 0;
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            int valueBytes = mutation instanceof WriteBatch.Put put ? put.value().length : 0;
            bytes =
                    Math.addExact(
                            bytes,
                            NativeSkipListMemTable.maximumInsertionBytes(
                                    mutation.key().length, valueBytes));
        }
        return bytes;
    }

    private synchronized void processCommitGroup(List<CommitRequest> requests) {
        List<PreparedCommit> prepared = new ArrayList<>();
        boolean forceRequested = false;
        for (CommitRequest request : requests) {
            if (backgroundFailure != null) {
                failBeforeSubmission(
                        request,
                        new AetherException(
                                "write subsystem previously failed", backgroundFailure));
                continue;
            }
            try {
                ensureOpen();
                request.batch.sealForSubmission();
                if (request.batch.operationCount() == 0) {
                    request.batch.markSucceeded();
                    request.result =
                            new WriteResult(0, 0, 0, request.options.durabilityMode(), false);
                    continue;
                }
                long required = requiredNativeBytes(request.batch);
                if (required > MEMTABLE_BYTES - 256)
                    throw new IllegalArgumentException("batch cannot fit in one MemTable");
                admitWrite(required, request.options);
                long first = Math.addExact(lastVisibleSequence, 1);
                long last = Math.addExact(first, request.batch.operationCount() - 1L);
                byte[] logical = encodeGroup(request.batch, first, last);
                if (WalFormatV1.estimateEndOffset(wal.position(), logical.length)
                        > WalFormatV1.SEGMENT_CAPACITY) flushActive();
                int recordNumber = Math.incrementExact(walRecordNumber);
                byte[] physical = WalFragmentCodec.fragment(logical, wal.position(), recordNumber);
                request.batch.markSubmitted();
                writeFully(wal, ByteBuffer.wrap(physical));
                walRecordNumber = recordNumber;
                applyBatch(active, request.batch, first);
                lastVisibleSequence = last;
                prepared.add(new PreparedCommit(request, first, last));
                forceRequested |=
                        request.options.durabilityMode()
                                != io.aetherdb.api.DurabilityMode.ASYNC_WAL;
            } catch (Throwable failure) {
                transitionFailure(request, failure);
                if (request.batch.state() == WriteBatch.State.INDETERMINATE) {
                    backgroundFailure = failure;
                    break;
                }
            }
        }
        boolean forced = false;
        if (backgroundFailure == null && forceRequested) {
            try {
                forceWal();
                forced = true;
            } catch (Throwable failure) {
                backgroundFailure = failure;
            }
        }
        if (backgroundFailure != null) {
            for (PreparedCommit commit : prepared) {
                if (commit.request.batch.state() == WriteBatch.State.SUBMITTED)
                    commit.request.batch.markIndeterminate();
                commit.request.failure =
                        new AetherException(
                                "group commit force failed; outcome is indeterminate",
                                backgroundFailure);
            }
            for (CommitRequest request : requests)
                if (request.result == null
                        && request.failure == null
                        && request.batch.state() != WriteBatch.State.INDETERMINATE) {
                    failBeforeSubmission(
                            request,
                            new AetherException("write subsystem failed", backgroundFailure));
                }
            return;
        }
        for (PreparedCommit commit : prepared) {
            commit.request.batch.markSucceeded();
            commit.request.result =
                    new WriteResult(
                            commit.request.batch.operationCount(),
                            commit.firstSequence,
                            commit.lastSequence,
                            commit.request.options.durabilityMode(),
                            forced);
        }
    }

    private static void transitionFailure(CommitRequest request, Throwable failure) {
        if (request.batch.state() == WriteBatch.State.SEALED) request.batch.markFailed();
        else if (request.batch.state() == WriteBatch.State.SUBMITTED)
            request.batch.markIndeterminate();
        request.failure =
                failure instanceof RuntimeException runtime
                        ? runtime
                        : new AetherException(
                                "persistent write failed; outcome may be indeterminate", failure);
    }

    private static void failBeforeSubmission(CommitRequest request, RuntimeException failure) {
        try {
            if (request.batch.state() == WriteBatch.State.OPEN) request.batch.sealForSubmission();
            if (request.batch.state() == WriteBatch.State.SEALED) request.batch.markFailed();
        } catch (RuntimeException stateFailure) {
            failure.addSuppressed(stateFailure);
        }
        request.failure = failure;
    }

    private void forceWal() throws IOException {
        wal.force(false);
        walForceCount++;
    }

    synchronized long walForceCountForTesting() {
        return walForceCount;
    }

    private void admitWrite(long requiredNativeBytes, WriteOptions options) throws IOException {
        if (requiredNativeBytes > active.nativeRemainingBytes() && active.entryCount() > 0)
            flushActive();
        compactIfNeeded();
        WritePressureSnapshot pressure = pressure(requiredNativeBytes);
        if (pressure.state() == WritePressureState.NORMAL) return;
        if (pressure.state() == WritePressureState.SLOWDOWN && !options.failFastOnBackpressure()) {
            long allowedNanos;
            try {
                allowedNanos = options.admissionTimeout().toNanos();
            } catch (ArithmeticException overflow) {
                allowedNanos = Long.MAX_VALUE;
            }
            long requestedNanos = Math.multiplyExact(pressure.delayMicros(), 1_000L);
            if (requestedNanos <= allowedNanos) {
                LockSupport.parkNanos(requestedNanos);
                return;
            }
        }
        throw new AetherException(
                "write admission rejected by " + pressure.state() + ": " + pressure.reasons());
    }

    private WritePressureSnapshot pressure(long requiredNativeBytes) throws IOException {
        long debt = 0;
        for (int level = 1; level <= 5; level++) {
            long bytes = 0;
            for (ManifestFileMetadata file : versions.current().files(level))
                bytes = Math.addExact(bytes, file.fileSize());
            debt = Math.addExact(debt, Math.max(0, bytes - COMPACTION_CONFIG.targetBytes(level)));
        }
        java.nio.file.FileStore store = Files.getFileStore(root);
        WritePressureInput input =
                new WritePressureInput(
                        0,
                        requiredNativeBytes <= active.nativeRemainingBytes(),
                        wal.size(),
                        versions.current().files(0).size(),
                        debt,
                        store.getUsableSpace(),
                        store.getTotalSpace(),
                        true,
                        false,
                        false);
        return PRESSURE.evaluate(input);
    }

    private static void applyBatch(
            NativeSkipListMemTable table, WriteBatch batch, long firstSequence) throws IOException {
        long sequence = firstSequence;
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            NativeSkipListMemTable.InsertResult result =
                    mutation instanceof WriteBatch.Put put
                            ? table.put(put.key(), put.value(), sequence)
                            : table.delete(mutation.key(), sequence);
            if (result != NativeSkipListMemTable.InsertResult.INSERTED)
                throw new IOException("MemTable insertion failed after WAL submission: " + result);
            sequence++;
        }
    }

    private static void initializeIdentityAndWal(Path root) throws IOException {
        try (var entries = Files.list(root)) {
            if (entries.anyMatch(path -> !path.getFileName().toString().equals("LOCK"))) {
                throw new IOException("database directory is nonempty but has no DB-IDENTITY");
            }
        }
        UUID id = UUID.randomUUID();
        long now = System.currentTimeMillis();
        atomicWrite(root, IDENTITY, new DatabaseIdentityV1(id, now, 0, 1).encode());
        atomicWrite(root, OPTIONS, new FormatOptionsV1(id, now).encode());
        Path wal = root.resolve(WalFormatV1.fileName(1));
        FileChannel initialWal = createWalSegment(wal, id, 1, 0, 1);
        initialWal.close();
        syncDirectory(root);
    }

    private static FileChannel createWalSegment(
            Path path,
            UUID databaseId,
            long segmentNumber,
            long previousSegmentNumber,
            long firstSequence)
            throws IOException {
        FileChannel channel =
                FileChannel.open(
                        path,
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.READ,
                        StandardOpenOption.WRITE);
        try {
            WalSegmentHeader header =
                    new WalSegmentHeader(
                            databaseId,
                            segmentNumber,
                            previousSegmentNumber,
                            firstSequence,
                            System.currentTimeMillis());
            writeFully(channel, ByteBuffer.wrap(header.encodeBlock()));
            channel.force(true);
            channel.position(WalFormatV1.HEADER_BLOCK_BYTES);
            return channel;
        } catch (Throwable failure) {
            closeSuppressed(channel, failure);
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("WAL segment creation failed", failure);
        }
    }

    private static void validateIdentityPair(Path root) throws IOException {
        if (!Files.exists(root.resolve(OPTIONS)) || !Files.exists(root.resolve("CURRENT")))
            throw new IOException("existing database metadata is incomplete");
        DatabaseIdentityV1 identity =
                DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve(IDENTITY)));
        FormatOptionsV1 options = FormatOptionsV1.decode(Files.readAllBytes(root.resolve(OPTIONS)));
        if (!identity.databaseId().equals(options.databaseId()))
            throw new IOException("database identity/options mismatch");
    }

    private static void cleanupObsoleteWalFiles(Path root, long requiredSegment)
            throws IOException {
        Path required = root.resolve(WalFormatV1.fileName(requiredSegment));
        if (!Files.isRegularFile(required))
            throw new IOException("required WAL segment is missing: " + required.getFileName());
        boolean deleted = false;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.matches("WAL-[0-9]{20}\\.aewal") && !entry.equals(required)) {
                    if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry))
                        throw new IOException("unsafe obsolete WAL path: " + name);
                    Files.delete(entry);
                    deleted = true;
                }
            }
        }
        if (deleted) syncDirectory(root);
    }

    private static void initializeCheckpointWal(Path root, UUID databaseId, VersionSet versions)
            throws IOException {
        Path path = root.resolve(WalFormatV1.fileName(1));
        FileChannel channel =
                createWalSegment(
                        path,
                        databaseId,
                        1,
                        0,
                        Math.addExact(versions.current().lastAssignedSequence(), 1));
        try {
            ManifestEdit edit =
                    new ManifestEdit(
                            ManifestEdit.Kind.DELTA,
                            versions.current().manifestEditNumber() + 1,
                            versions.current().nextFileNumber(),
                            versions.current().lastAssignedSequence(),
                            versions.current().persistedSequenceWatermark(),
                            1,
                            List.of(),
                            List.of());
            versions.logAndApply(edit);
        } catch (Throwable failure) {
            closeSuppressed(channel, failure);
            try {
                Files.deleteIfExists(path);
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("checkpoint WAL initialization failed", failure);
        }
        channel.close();
        syncDirectory(root);
    }

    private static WalRecovery recoverWal(
            FileChannel wal,
            UUID databaseId,
            long segmentNumber,
            long persistedWatermark,
            long lastAssignedSequence,
            NativeSkipListMemTable target)
            throws IOException {
        if (wal.size() < WalFormatV1.HEADER_BLOCK_BYTES)
            throw new IOException("WAL header missing");
        WalSegmentHeader.decode(
                readRange(wal, 0, WalFormatV1.HEADER_BLOCK_BYTES), databaseId, segmentNumber);
        byte[] physical =
                readRange(
                        wal,
                        WalFormatV1.HEADER_BLOCK_BYTES,
                        Math.toIntExact(wal.size() - WalFormatV1.HEADER_BLOCK_BYTES));
        List<byte[]> groups = WalFragmentCodec.reassemble(physical, WalFormatV1.HEADER_BLOCK_BYTES);
        long valid = WalFormatV1.HEADER_BLOCK_BYTES, expected = persistedWatermark + 1;
        long recoveredLast = Math.max(lastAssignedSequence, persistedWatermark);
        int record = 0;
        for (byte[] logical : groups) {
            record++;
            valid = WalFormatV1.estimateEndOffset(valid, logical.length);
            DecodedGroup group = decodeGroup(logical);
            if (group.last() <= persistedWatermark) continue;
            if (group.first() <= persistedWatermark || group.first() != expected)
                throw new IOException("WAL sequence discontinuity");
            applyDecoded(target, group);
            expected = group.last() + 1;
            recoveredLast = group.last();
        }
        return new WalRecovery(valid, record, recoveredLast);
    }

    private static void applyDecoded(NativeSkipListMemTable target, DecodedGroup group)
            throws IOException {
        long sequence = group.first();
        for (Mutation mutation : group.mutations()) {
            NativeSkipListMemTable.InsertResult result =
                    mutation.delete()
                            ? target.delete(mutation.key(), sequence)
                            : target.put(mutation.key(), mutation.value(), sequence);
            if (result != NativeSkipListMemTable.InsertResult.INSERTED)
                throw new IOException("WAL recovery MemTable exhausted: " + result);
            sequence++;
        }
    }

    private static byte[] encodeGroup(WriteBatch batch, long first, long last) {
        int size = GROUP_HEADER_BYTES;
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            size =
                    Math.addExact(
                            size,
                            12
                                    + mutation.key().length
                                    + (mutation instanceof WriteBatch.Put put
                                            ? put.value().length
                                            : 0));
        }
        byte[] result = new byte[size];
        ByteBuffer bytes = little(result);
        bytes.put("AETHGRP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                .putShort((short) 1)
                .putShort((short) GROUP_HEADER_BYTES)
                .putInt(size)
                .putLong(first)
                .putLong(last)
                .putInt(batch.operationCount())
                .putInt(0)
                .putInt(0)
                .putInt(0);
        for (WriteBatch.Mutation mutation : batch.mutations()) {
            byte[] key = mutation.key(),
                    value = mutation instanceof WriteBatch.Put put ? put.value() : new byte[0];
            bytes.put((byte) (mutation instanceof WriteBatch.Delete ? 2 : 1))
                    .put(new byte[3])
                    .putInt(key.length)
                    .putInt(value.length)
                    .put(key)
                    .put(value);
        }
        bytes.putInt(44, io.aetherdb.format.checksum.MaskedCrc32c.masked(result, 0, 44));
        return result;
    }

    private static DecodedGroup decodeGroup(byte[] encoded) throws IOException {
        if (encoded.length < GROUP_HEADER_BYTES) throw new IOException("short WAL group");
        ByteBuffer bytes = little(encoded);
        byte[] magic = new byte[8];
        bytes.get(magic);
        if (!Arrays.equals(magic, "AETHGRP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || bytes.getShort() != 1
                || bytes.getShort() != GROUP_HEADER_BYTES
                || bytes.getInt() != encoded.length) {
            throw new IOException("invalid WAL group header");
        }
        long first = bytes.getLong(), last = bytes.getLong();
        int count = bytes.getInt();
        if (bytes.getInt() != 0
                || bytes.getInt() != 0
                || bytes.getInt() != io.aetherdb.format.checksum.MaskedCrc32c.masked(encoded, 0, 44)
                || first < 1
                || last < first
                || last - first + 1 != count) throw new IOException("invalid WAL group metadata");
        List<Mutation> mutations = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            if (bytes.remaining() < 12) throw new IOException("truncated WAL operation");
            int type = Byte.toUnsignedInt(bytes.get());
            if (bytes.get() != 0 || bytes.get() != 0 || bytes.get() != 0)
                throw new IOException("invalid WAL operation flags");
            int keyLength = bytes.getInt(), valueLength = bytes.getInt();
            if (keyLength < 0
                    || keyLength > WriteBatch.MAX_KEY_BYTES
                    || valueLength < 0
                    || valueLength > WriteBatch.MAX_VALUE_BYTES
                    || bytes.remaining() < keyLength + valueLength
                    || type != 1 && type != 2
                    || type == 2 && valueLength != 0)
                throw new IOException("invalid WAL operation");
            byte[] key = new byte[keyLength], value = new byte[valueLength];
            bytes.get(key).get(value);
            mutations.add(new Mutation(key, value, type == 2));
        }
        if (bytes.hasRemaining()) throw new IOException("WAL group trailing bytes");
        return new DecodedGroup(first, last, mutations);
    }

    private SnapshotHandle validateSnapshot(Snapshot snapshot) {
        if (!(snapshot instanceof SnapshotHandle handle) || handle.identity() != snapshotIdentity)
            throw new SnapshotException("snapshot belongs to another database");
        handle.ensureOpen();
        return handle;
    }

    private void ensureOpen() {
        if (closed) throw new AetherClosedException("database is closed");
    }

    private static void validateKey(byte[] key) {
        if (key == null || key.length > WriteBatch.MAX_KEY_BYTES)
            throw new IllegalArgumentException("invalid key");
    }

    private static NativeSkipListMemTable createMemTable(
            NativeMemoryBudget budget, UUID databaseId, long number) {
        return new NativeSkipListMemTable(
                budget,
                MEMTABLE_BYTES,
                "mem-" + databaseId + '-' + number,
                databaseId.getLeastSignificantBits() ^ number);
    }

    private static void atomicWrite(Path root, String name, byte[] data) throws IOException {
        Path target = PathSecurityValidator.managed(root, name),
                temporary = root.resolve(name + ".tmp-" + UUID.randomUUID());
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(data));
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void syncDirectory(Path root) throws IOException {
        try (FileChannel channel = FileChannel.open(root, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private static byte[] readRange(FileChannel channel, long offset, int length)
            throws IOException {
        byte[] result = new byte[length];
        ByteBuffer bytes = ByteBuffer.wrap(result);
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset + bytes.position());
            if (read < 0) throw new EOFException();
        }
        return result;
    }

    private static ByteBuffer little(byte[] value) {
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN);
    }

    private static Throwable merge(Throwable first, Throwable next) {
        if (first == null) return next;
        first.addSuppressed(next);
        return first;
    }

    private static void closeSuppressed(AutoCloseable closeable, Throwable failure) {
        if (closeable != null)
            try {
                closeable.close();
            } catch (Throwable exception) {
                failure.addSuppressed(exception);
            }
    }

    private final class CommitCoordinator {
        private static final int MAXIMUM_GROUP_REQUESTS = 64;
        private static final long GATHER_NANOS = 200_000;
        private final Object monitor = new Object();
        private final ArrayDeque<CommitRequest> queue = new ArrayDeque<>();
        private boolean leaderActive;

        WriteResult submit(WriteBatch batch, WriteOptions options) {
            CommitRequest request = new CommitRequest(batch, options);
            boolean leader = false;
            boolean interrupted = false;
            synchronized (monitor) {
                queue.addLast(request);
                while (!request.done) {
                    if (!leaderActive) {
                        leaderActive = true;
                        leader = true;
                        break;
                    }
                    try {
                        monitor.wait();
                    } catch (InterruptedException interruption) {
                        interrupted = true;
                    }
                }
            }
            if (leader) {
                LockSupport.parkNanos(GATHER_NANOS);
                List<CommitRequest> group = new ArrayList<>(MAXIMUM_GROUP_REQUESTS);
                synchronized (monitor) {
                    while (group.size() < MAXIMUM_GROUP_REQUESTS && !queue.isEmpty())
                        group.add(queue.removeFirst());
                }
                processCommitGroup(group);
                synchronized (monitor) {
                    for (CommitRequest member : group) member.done = true;
                    leaderActive = false;
                    monitor.notifyAll();
                }
            } else {
                synchronized (monitor) {
                    while (!request.done)
                        try {
                            monitor.wait();
                        } catch (InterruptedException interruption) {
                            interrupted = true;
                        }
                }
            }
            if (interrupted) Thread.currentThread().interrupt();
            if (request.failure != null) throw request.failure;
            return request.result;
        }
    }

    private static final class CommitRequest {
        private final WriteBatch batch;
        private final WriteOptions options;
        private WriteResult result;
        private RuntimeException failure;
        private boolean done;

        private CommitRequest(WriteBatch batch, WriteOptions options) {
            this.batch = batch;
            this.options = options;
        }
    }

    private record WalRecovery(long validEnd, int records, long lastSequence) {}

    private record PreparedCommit(CommitRequest request, long firstSequence, long lastSequence) {}

    private record Mutation(byte[] key, byte[] value, boolean delete) {}

    private record DecodedGroup(long first, long last, List<Mutation> mutations) {}
}
