package io.aetherdb.engine;

import io.aetherdb.admission.AdmissionDecision;
import io.aetherdb.admission.AdmissionOutcome;
import io.aetherdb.api.AetherCursor;
import io.aetherdb.api.AetherDatabase;
import io.aetherdb.api.DurabilityMode;
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
import io.aetherdb.config.AetherConfigRegistry;
import io.aetherdb.config.AetherConfigValidator;
import io.aetherdb.config.AetherConfiguration;
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
import io.aetherdb.lsm.pressure.WritePressurePolicy;
import io.aetherdb.lsm.pressure.WritePressureSnapshot;
import io.aetherdb.lsm.pressure.WritePressureState;
import io.aetherdb.memory.NativeMemoryBudget;
import io.aetherdb.memory.RegionConfig;
import io.aetherdb.memtable.reference.VersionedKeyValueStore;
import io.aetherdb.memtable.skiplist.MemTableLookupResult;
import io.aetherdb.memtable.skiplist.NativeSkipListMemTable;
import io.aetherdb.reliability.CrashContext;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;
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
import io.aetherdb.wal.format.WalCorruptionException;
import io.aetherdb.wal.format.WalLogicalGroupCodec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private static final Comparator<byte[]> BYTE_ORDER = Arrays::compareUnsigned;
    private static final AetherConfigRegistry CONFIG_REGISTRY = AetherConfigRegistry.defaults();

    private final Object snapshotIdentity = new Object();
    private final Set<SnapshotHandle> snapshots =
            Collections.newSetFromMap(new IdentityHashMap<>());
    private final Path root;
    private final DatabaseLock lock;
    private final UUID databaseId;
    private FileChannel wal;
    private final VersionSet versions;
    private final NativeMemoryBudget nativeBudget;
    private final RuntimeConfiguration configuration;
    private final WritePressureController pressureController;
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
        return open(requested, new AetherConfiguration(Map.of("aether.security.profile", "development")));
    }

    /** Opens or creates the process-exclusive local database with validated configuration. */
    static PersistentAetherDatabase open(Path requested, AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        RuntimeConfiguration runtimeConfiguration = RuntimeConfiguration.from(configuration);
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
            NativeMemoryBudget nativeBudget = new NativeMemoryBudget(runtimeConfiguration.memtableBytes());
            active = createMemTable(nativeBudget, runtimeConfiguration.memtableBytes(), identity.databaseId(), 1);
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
                    runtimeConfiguration,
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
            RuntimeConfiguration configuration,
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
        this.configuration = configuration;
        pressureController = new WritePressureController(configuration.writePressurePolicy());
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
        if (snapshots.size() >= configuration.maximumSnapshots())
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
        write(batch, configuration.defaultWriteOptions());
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
            CrashPointRegistry.hit(
                    CrashPointIds.FLUSH_AFTER_SSTABLE_FORCE_BEFORE_MANIFEST,
                    tableCrashContext(fileNumber, 0, built));
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
        active = createMemTable(nativeBudget, configuration.memtableBytes(), databaseId, ++memTableNumber);
        compactIfNeeded();
    }

    private void compactIfNeeded() throws IOException {
        if (!configuration.compactionEnabled()) return;
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
                if (levelBytes > configuration.compactionConfig().targetBytes(level)) {
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
                        retained, configuration.compactionConfig().targetOutputFileBytes(outputLevel));
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
            CrashPointRegistry.hit(
                    CrashPointIds.COMPACTION_AFTER_OUTPUT_FORCE_BEFORE_MANIFEST,
                    compactionCrashContext(outputLevel, additions.size(), inputs.size()));
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
            CrashPointRegistry.hit(
                    CrashPointIds.COMPACTION_AFTER_MANIFEST_BEFORE_DELETE,
                    compactionCrashContext(outputLevel, additions.size(), deletions.size()));
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
                CrashPointRegistry.hit(
                        CrashPointIds.WRITE_BEFORE_SEAL,
                        CrashContext.of(
                                "operation_count",
                                Integer.toString(request.batch.operationCount())));
                request.batch.sealForSubmission();
                if (request.batch.operationCount() == 0) {
                    request.batch.markSucceeded();
                    request.result =
                            new WriteResult(0, 0, 0, request.options.durabilityMode(), false);
                    continue;
                }
                long required = requiredNativeBytes(request.batch);
                if (required > configuration.memtableBytes() - 256)
                    throw new IllegalArgumentException("batch cannot fit in one MemTable");
                admitWrite(required, request.options);
                long first = Math.addExact(lastVisibleSequence, 1);
                long last = Math.addExact(first, request.batch.operationCount() - 1L);
                CrashPointRegistry.hit(
                        CrashPointIds.WRITE_AFTER_SEQUENCE_ALLOCATED,
                        writeSequenceCrashContext(first, last, request.batch.operationCount()));
                byte[] logical = WalLogicalGroupCodec.encode(request.batch, first, last);
                if (WalFormatV1.estimateEndOffset(wal.position(), logical.length)
                        > configuration.walSegmentBytes()) flushActive();
                int recordNumber = Math.incrementExact(walRecordNumber);
                long walStartOffset = wal.position();
                byte[] physical = WalFragmentCodec.fragment(logical, walStartOffset, recordNumber);
                request.batch.markSubmitted();
                CrashContext walContext =
                        walCrashContext(
                                recordNumber, walStartOffset, logical.length, physical.length);
                CrashPointRegistry.hit(CrashPointIds.WAL_BEFORE_FRAGMENT_WRITE, walContext);
                writeFully(wal, ByteBuffer.wrap(physical));
                CrashPointRegistry.hit(
                        CrashPointIds.WAL_AFTER_FRAGMENT_WRITE_BEFORE_FORCE,
                        walCrashContext(
                                recordNumber,
                                walStartOffset + physical.length,
                                logical.length,
                                physical.length));
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
                CrashPointRegistry.hit(
                        CrashPointIds.WAL_AFTER_FORCE_BEFORE_VISIBILITY,
                        forceCrashContext(prepared));
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

    private static CrashContext walCrashContext(
            int recordNumber, long walOffset, int logicalBytes, int physicalBytes) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("record_number", Integer.toUnsignedString(recordNumber));
        attributes.put("wal_offset", Long.toUnsignedString(walOffset));
        attributes.put("logical_bytes", Integer.toString(logicalBytes));
        attributes.put("physical_bytes", Integer.toString(physicalBytes));
        return new CrashContext(attributes);
    }

    private static CrashContext tableCrashContext(
            long fileNumber, int level, TableFileMetadata metadata) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("file_number", Long.toUnsignedString(fileNumber));
        attributes.put("level", Integer.toString(level));
        attributes.put("file_size", Long.toUnsignedString(metadata.fileSize()));
        attributes.put("entry_count", Long.toUnsignedString(metadata.entryCount()));
        attributes.put("smallest_sequence", Long.toUnsignedString(metadata.smallestSequence()));
        attributes.put("largest_sequence", Long.toUnsignedString(metadata.largestSequence()));
        return new CrashContext(attributes);
    }

    private static CrashContext compactionCrashContext(
            int outputLevel, int additionCount, int deletionCount) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("output_level", Integer.toString(outputLevel));
        attributes.put("addition_count", Integer.toString(additionCount));
        attributes.put("deletion_count", Integer.toString(deletionCount));
        return new CrashContext(attributes);
    }

    private static CrashContext writeSequenceCrashContext(
            long firstSequence, long lastSequence, int operationCount) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("first_sequence", Long.toUnsignedString(firstSequence));
        attributes.put("last_sequence", Long.toUnsignedString(lastSequence));
        attributes.put("operation_count", Integer.toString(operationCount));
        return new CrashContext(attributes);
    }

    private static CrashContext forceCrashContext(List<PreparedCommit> prepared) {
        LinkedHashMap<String, String> attributes = new LinkedHashMap<>();
        attributes.put("prepared_count", Integer.toString(prepared.size()));
        if (!prepared.isEmpty()) {
            attributes.put(
                    "first_sequence",
                    Long.toUnsignedString(prepared.get(0).firstSequence()));
            attributes.put(
                    "last_sequence",
                    Long.toUnsignedString(prepared.get(prepared.size() - 1).lastSequence()));
        }
        return new CrashContext(attributes);
    }

    synchronized long walForceCountForTesting() {
        return walForceCount;
    }

    static int configuredMaximumSnapshotsForTesting(AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        return RuntimeConfiguration.from(configuration).maximumSnapshots();
    }

    static long configuredWalSegmentBytesForTesting(AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        return RuntimeConfiguration.from(configuration).walSegmentBytes();
    }

    static int configuredImmutableMemtableStopForTesting(AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        return RuntimeConfiguration.from(configuration).writePressurePolicy().immutableMemtableStop();
    }

    static DurabilityMode configuredDefaultDurabilityForTesting(AetherConfiguration configuration) {
        AetherConfigValidator.defaults().validate(Objects.requireNonNull(configuration, "configuration"));
        return RuntimeConfiguration.from(configuration).defaultWriteOptions().durabilityMode();
    }

    private void admitWrite(long requiredNativeBytes, WriteOptions options) throws IOException {
        if (requiredNativeBytes > active.nativeRemainingBytes() && active.entryCount() > 0)
            flushActive();
        compactIfNeeded();
        WritePressureSnapshot pressure = pressure(requiredNativeBytes);
        AdmissionDecision decision = writeAdmissionDecision(pressure);
        if (decision.accepted()) return;
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
                "write admission rejected by "
                        + decision.outcome()
                        + ": "
                        + String.join(", ", decision.reasons()));
    }

    static AdmissionDecision writeAdmissionDecision(WritePressureSnapshot pressure) {
        return switch (pressure.state()) {
            case NORMAL ->
                    new AdmissionDecision(AdmissionOutcome.ACCEPTED, List.of(), java.time.Duration.ZERO);
            case SLOWDOWN ->
                    new AdmissionDecision(
                            AdmissionOutcome.REJECTED_BEFORE_ACK,
                            writeAdmissionReasons(pressure),
                            java.time.Duration.ofNanos(Math.multiplyExact(pressure.delayMicros(), 1_000L)));
            case STOPPED_RETRYABLE ->
                    new AdmissionDecision(
                            AdmissionOutcome.RESOURCE_EXHAUSTED,
                            writeAdmissionReasons(pressure),
                            java.time.Duration.ZERO);
            case FAILED ->
                    new AdmissionDecision(
                            AdmissionOutcome.REJECTED_BEFORE_ACK,
                            writeAdmissionReasons(pressure),
                            java.time.Duration.ZERO);
        };
    }

    private static List<String> writeAdmissionReasons(WritePressureSnapshot pressure) {
        if (pressure.reasons().isEmpty()) return List.of("write pressure: " + pressure.state());
        return pressure.reasons().stream()
                .map(reason -> "write pressure: " + reason)
                .sorted()
                .toList();
    }

    private WritePressureSnapshot pressure(long requiredNativeBytes) throws IOException {
        long debt = 0;
        for (int level = 1; level <= 5; level++) {
            long bytes = 0;
            for (ManifestFileMetadata file : versions.current().files(level))
                bytes = Math.addExact(bytes, file.fileSize());
            debt =
                    Math.addExact(
                            debt,
                            Math.max(0, bytes - configuration.compactionConfig().targetBytes(level)));
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
        return pressureController.evaluate(input);
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
            WalLogicalGroupCodec.DecodedGroup group = decodeGroup(logical);
            if (group.lastSequence() <= persistedWatermark) continue;
            if (group.firstSequence() <= persistedWatermark || group.firstSequence() != expected)
                throw new IOException("WAL sequence discontinuity");
            applyDecoded(target, group);
            expected = group.lastSequence() + 1;
            recoveredLast = group.lastSequence();
        }
        return new WalRecovery(valid, record, recoveredLast);
    }

    private static void applyDecoded(
            NativeSkipListMemTable target, WalLogicalGroupCodec.DecodedGroup group)
            throws IOException {
        long sequence = group.firstSequence();
        for (WalLogicalGroupCodec.Mutation mutation : group.mutations()) {
            NativeSkipListMemTable.InsertResult result =
                    mutation.delete()
                            ? target.delete(mutation.key(), sequence)
                            : target.put(mutation.key(), mutation.value(), sequence);
            if (result != NativeSkipListMemTable.InsertResult.INSERTED)
                throw new IOException("WAL recovery MemTable exhausted: " + result);
            sequence++;
        }
    }

    private static WalLogicalGroupCodec.DecodedGroup decodeGroup(byte[] encoded) throws IOException {
        try {
            return WalLogicalGroupCodec.decode(encoded);
        } catch (WalCorruptionException failure) {
            throw new IOException(failure.getMessage(), failure);
        }
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
            NativeMemoryBudget budget, long capacityBytes, UUID databaseId, long number) {
        return new NativeSkipListMemTable(
                budget,
                capacityBytes,
                "mem-" + databaseId + '-' + number,
                databaseId.getLeastSignificantBits() ^ number);
    }

    private record RuntimeConfiguration(
            long memtableBytes,
            long walSegmentBytes,
            int maximumSnapshots,
            boolean compactionEnabled,
            LevelCompactionConfig compactionConfig,
            WritePressurePolicy writePressurePolicy,
            WriteOptions defaultWriteOptions) {
        private RuntimeConfiguration {
            RegionConfig.validateCapacity(memtableBytes);
            if (walSegmentBytes < WalFormatV1.HEADER_BLOCK_BYTES
                    || walSegmentBytes > WalFormatV1.SEGMENT_CAPACITY) {
                throw new IllegalArgumentException("invalid WAL segment byte threshold");
            }
            if (maximumSnapshots <= 0) throw new IllegalArgumentException("maximumSnapshots must be positive");
            Objects.requireNonNull(compactionConfig, "compactionConfig");
            Objects.requireNonNull(writePressurePolicy, "writePressurePolicy");
            Objects.requireNonNull(defaultWriteOptions, "defaultWriteOptions");
        }

        static RuntimeConfiguration from(AetherConfiguration configuration) {
            return new RuntimeConfiguration(
                    longValue(configuration, "aether.memtable.native_bytes"),
                    longValue(configuration, "aether.wal.segment_bytes"),
                    intValue(configuration, "aether.snapshots.max_open"),
                    booleanValue(configuration, "aether.compaction.enabled"),
                    LevelCompactionConfig.defaults(),
                    WritePressurePolicy.forImmutableLimit(
                            intValue(configuration, "aether.memtable.immutable_limit")),
                    new WriteOptions(
                            DurabilityMode.valueOf(
                                    configuration
                                            .getOrDefault(
                                                    CONFIG_REGISTRY.require(
                                                            "aether.wal.durability_mode"))
                                            .toUpperCase(java.util.Locale.ROOT)),
                            WriteOptions.defaults().admissionTimeout(),
                            WriteOptions.defaults().failFastOnBackpressure()));
        }

        private static long longValue(AetherConfiguration configuration, String name) {
            return Long.parseLong(configuration.getOrDefault(CONFIG_REGISTRY.require(name)));
        }

        private static int intValue(AetherConfiguration configuration, String name) {
            return Integer.parseInt(configuration.getOrDefault(CONFIG_REGISTRY.require(name)));
        }

        private static boolean booleanValue(AetherConfiguration configuration, String name) {
            return Boolean.parseBoolean(configuration.getOrDefault(CONFIG_REGISTRY.require(name)));
        }
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

}
