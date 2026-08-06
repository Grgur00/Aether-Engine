package io.aetherdb.tools;

import io.aetherdb.io.DatabaseIdentityV1;
import io.aetherdb.io.CheckpointMetadataV1;
import io.aetherdb.io.DatabaseLock;
import io.aetherdb.io.FormatOptionsV1;
import io.aetherdb.io.PathSecurityValidator;
import io.aetherdb.format.checksum.MaskedCrc32c;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
import io.aetherdb.sstable.manifest.ManifestEdit;
import io.aetherdb.sstable.manifest.ManifestInspection;
import io.aetherdb.sstable.manifest.CurrentFileV1;
import io.aetherdb.sstable.manifest.Version;
import io.aetherdb.sstable.manifest.VersionSet;
import io.aetherdb.sstable.SSTableBuilder;
import io.aetherdb.sstable.SSTableCorruptionException;
import io.aetherdb.sstable.SSTableEntry;
import io.aetherdb.sstable.SSTableReader;
import io.aetherdb.sstable.InternalKey;
import io.aetherdb.sstable.TableFileMetadata;
import io.aetherdb.wal.format.WalFormatV1;
import io.aetherdb.wal.format.WalFragmentCodec;
import io.aetherdb.wal.format.WalSegmentHeader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Offline operational CLI for durable metadata inspection and database verification. */
public final class AetherCli {
    private static final String VERSION = "0.2.0-dev";
    private AetherCli() { }

    /**
     * Runs the command-line entry point and exits with the command status.
     *
     * @param arguments command name, database path, and options
     */
    public static void main(String[] arguments) {
        int exit = run(arguments); if (exit != 0) System.exit(exit);
    }

    static int run(String[] arguments) {
        if (arguments.length == 0 || arguments[0].equals("help") || arguments[0].equals("--help")) { usage(); return 0; }
        try {
            return switch (arguments[0]) {
                case "version", "--version" -> { System.out.println("Aether Engine " + VERSION + " (format epoch 1)"); yield 0; }
                case "inspect" -> inspect(Arguments.parse(arguments, VerificationLevel.METADATA), false);
                case "verify" -> inspect(Arguments.parse(arguments, VerificationLevel.FULL), true);
                case "checkpoint" -> checkpoint(arguments);
                case "restore-verify" -> restoreVerify(arguments);
                case "repair-tail" -> repairTail(arguments);
                case "rebuild-current" -> rebuildCurrent(arguments);
                case "salvage" -> salvage(arguments);
                default -> { System.err.println("Unknown command: " + arguments[0]); usage(); yield 64; }
            };
        } catch (LockUnavailableException failure) {
            System.err.println("Database lock unavailable: " + failure.getMessage()); return 4;
        } catch (IOException failure) {
            System.err.println("I/O error: " + failure.getMessage()); return 4;
        } catch (IllegalArgumentException failure) {
            System.err.println("Invalid or corrupt database: " + failure.getMessage()); return 3;
        }
    }

    private static int inspect(Arguments arguments, boolean verification) throws IOException {
        Path root = PathSecurityValidator.validateRoot(arguments.path, true); DatabaseLock lock = null;
        if (arguments.unsafeNoLock) System.err.println("WARNING: --unsafe-no-lock is forensic read-only mode; results may be inconsistent.");
        else try { lock = DatabaseLock.acquire(root); }
        catch (IOException failure) { throw new LockUnavailableException(failure.getMessage(), failure); }
        long started = System.nanoTime();
        try {
            DatabaseReport report = readReport(root, arguments.level, started);
            if (arguments.json) printJson(report, verification); else printText(report, verification);
            return report.warnings.isEmpty() ? 0 : 2;
        } finally { if (lock != null) lock.close(); }
    }

    private static DatabaseReport readReport(Path root, VerificationLevel level, long started) throws IOException {
        DatabaseIdentityV1 identity = DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
        FormatOptionsV1 options = FormatOptionsV1.decode(Files.readAllBytes(root.resolve("FORMAT-OPTIONS")));
        if (!identity.databaseId().equals(options.databaseId())) throw new IllegalArgumentException("identity/options UUID mismatch");
        ManifestInspection manifest = VersionSet.inspect(root, identity.databaseId()); Version version = manifest.version();
        List<String> warnings = new ArrayList<>();
        if (manifest.incompleteTailBytes() > 0) warnings.add("manifest has " + manifest.incompleteTailBytes() + " incomplete trailing bytes");
        List<WalInfo> wals = inspectWals(root, identity.databaseId(), version.minimumWalFileNumber(), level);
        long[] levelBytes = new long[7]; int[] levelFiles = new int[7];
        long oldest = Long.MAX_VALUE, newest = 0, totalTableBytes = 0;
        for (int levelNumber = 0; levelNumber < 7; levelNumber++) for (ManifestFileMetadata file : version.files(levelNumber)) {
            levelFiles[levelNumber]++; levelBytes[levelNumber] = Math.addExact(levelBytes[levelNumber], file.fileSize());
            totalTableBytes = Math.addExact(totalTableBytes, file.fileSize()); oldest = Math.min(oldest, file.fileNumber()); newest = Math.max(newest, file.fileNumber());
        }
        if (level == VerificationLevel.FULL) verifyNoDuplicateInternalIdentities(root, identity.databaseId(), version);
        return new DatabaseReport(identity, options, manifest, wals, levelFiles, levelBytes, totalTableBytes,
                oldest == Long.MAX_VALUE ? 0 : oldest, newest, List.copyOf(warnings), level, System.nanoTime() - started);
    }

    private static List<WalInfo> inspectWals(Path root, UUID databaseId, long minimumWal, VerificationLevel level) throws IOException {
        List<Path> paths;
        try (var entries = Files.list(root)) {
            paths = entries.filter(path -> path.getFileName().toString().matches("WAL-[0-9]{20}\\.aewal"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        List<WalInfo> result = new ArrayList<>();
        for (Path path : paths) {
            long number = Long.parseLong(path.getFileName().toString().substring(4, 24)); byte[] contents = Files.readAllBytes(path);
            if (contents.length < WalFormatV1.HEADER_BLOCK_BYTES) throw new IllegalArgumentException("truncated WAL header: " + path.getFileName());
            WalSegmentHeader header = WalSegmentHeader.decode(Arrays.copyOf(contents, WalFormatV1.HEADER_BLOCK_BYTES), databaseId, number);
            int groups = -1;
            if (level != VerificationLevel.METADATA) groups = WalFragmentCodec.reassemble(
                    Arrays.copyOfRange(contents, WalFormatV1.HEADER_BLOCK_BYTES, contents.length), WalFormatV1.HEADER_BLOCK_BYTES).size();
            result.add(new WalInfo(number, contents.length, header.firstSequence(), groups));
        }
        if (minimumWal > 0 && result.stream().noneMatch(wal -> wal.number == minimumWal)) {
            throw new IllegalArgumentException("minimum required WAL is missing: " + minimumWal);
        }
        return List.copyOf(result);
    }

    private static void verifyNoDuplicateInternalIdentities(Path root, UUID databaseId, Version version) throws IOException {
        java.util.HashSet<String> identities = new java.util.HashSet<>();
        for (ManifestFileMetadata file : version.allFiles()) {
            try (io.aetherdb.sstable.SSTableReader table = io.aetherdb.sstable.SSTableReader.open(
                    root.resolve(VersionSet.sstableName(file.fileNumber())), databaseId, file)) {
                for (io.aetherdb.sstable.SSTableEntry entry : table.entries()) {
                    String identity = HexFormat.of().formatHex(entry.key().encode());
                    if (!identities.add(identity)) throw new IllegalArgumentException("duplicate internal key across live SSTables: " + identity);
                }
            }
        }
    }

    private static void printText(DatabaseReport report, boolean verification) {
        Version version = report.manifest.version();
        System.out.println((verification ? "Verification" : "Inspection") + " level: " + (verification ? report.verificationLevel : "METADATA"));
        System.out.println("Database UUID: " + report.identity.databaseId());
        System.out.println("Format epoch: 1");
        System.out.println("Creator version: " + report.identity.creatorMajor() + "." + report.identity.creatorMinor());
        System.out.println("Created: " + Instant.ofEpochMilli(report.identity.creationEpochMillis()));
        System.out.println("Compatibility fingerprint: " + HexFormat.of().formatHex(report.options.compatibilityFingerprint()));
        System.out.println("Current manifest: " + report.manifest.manifestPath().getFileName());
        System.out.println("Manifest records/bytes: " + report.manifest.recordCount() + "/" + report.manifest.physicalBytes());
        System.out.println("Sequences assigned/persisted: " + version.lastAssignedSequence() + "/" + version.persistedSequenceWatermark());
        System.out.println("Minimum WAL segment: " + version.minimumWalFileNumber());
        System.out.println("WAL files/bytes: " + report.wals.size() + "/" + report.wals.stream().mapToLong(WalInfo::bytes).sum());
        for (int level = 0; level < 7; level++) System.out.println("L" + level + " files/bytes: " + report.levelFiles[level] + "/" + report.levelBytes[level]);
        System.out.println("SSTable oldest/newest file: " + report.oldestTable + "/" + report.newestTable);
        System.out.println("Snapshot data: unavailable offline");
        System.out.println("Elapsed: " + Duration.ofNanos(report.elapsedNanos));
        if (report.warnings.isEmpty()) System.out.println("Health: valid");
        else for (String warning : report.warnings) System.out.println("WARNING: " + warning);
    }

    private static void printJson(DatabaseReport report, boolean verification) {
        Version version = report.manifest.version(); StringBuilder levels = new StringBuilder("[");
        for (int level = 0; level < 7; level++) {
            if (level > 0) levels.append(','); levels.append("{\"level\":").append(level).append(",\"files\":")
                    .append(report.levelFiles[level]).append(",\"bytes\":").append(report.levelBytes[level]).append('}');
        }
        levels.append(']');
        System.out.printf(Locale.ROOT,
                "{\"mode\":\"%s\",\"databaseUuid\":\"%s\",\"formatEpoch\":1,\"fingerprint\":\"%s\","
                        + "\"manifest\":\"%s\",\"manifestRecords\":%d,\"manifestBytes\":%d,\"lastAssignedSequence\":%d,"
                        + "\"persistedSequence\":%d,\"minimumWal\":%d,\"walFiles\":%d,\"sstableBytes\":%d,"
                        + "\"levels\":%s,\"warnings\":%d,\"elapsedNanos\":%d}%n",
                verification ? "verify" : "inspect", report.identity.databaseId(), HexFormat.of().formatHex(report.options.compatibilityFingerprint()),
                report.manifest.manifestPath().getFileName(), report.manifest.recordCount(), report.manifest.physicalBytes(),
                version.lastAssignedSequence(), version.persistedSequenceWatermark(), version.minimumWalFileNumber(), report.wals.size(),
                report.totalTableBytes, levels, report.warnings.size(), report.elapsedNanos);
    }

    private static int checkpoint(String[] arguments) throws IOException {
        if (arguments.length != 3) throw new IllegalArgumentException("checkpoint requires source and destination directories");
        Path source = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path destination = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (source.equals(destination) || destination.startsWith(source)) throw new IllegalArgumentException("checkpoint destination must be outside the source");
        if (Files.exists(destination)) throw new IllegalArgumentException("checkpoint destination already exists");
        try (io.aetherdb.api.AetherDatabase database = io.aetherdb.engine.Aether.open(source)) {
            if (database.isClosed()) throw new IllegalStateException("source unexpectedly closed");
        }
        Path root = PathSecurityValidator.validateRoot(source, true); Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IllegalArgumentException("checkpoint destination parent does not exist");
        Path temporary = parent.resolve(destination.getFileName() + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        try (DatabaseLock lock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(lock);
            Files.createDirectory(temporary);
            try {
                DatabaseIdentityV1 identity = DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
                FormatOptionsV1 options = FormatOptionsV1.decode(Files.readAllBytes(root.resolve("FORMAT-OPTIONS")));
                copyForced(root.resolve("DB-IDENTITY"), temporary.resolve("DB-IDENTITY"));
                copyForced(root.resolve("FORMAT-OPTIONS"), temporary.resolve("FORMAT-OPTIONS"));
                Version sourceVersion; long sourceManifest;
                try (VersionSet versions = VersionSet.recover(root, identity.databaseId())) {
                    sourceVersion = versions.current(); sourceManifest = sourceVersion.manifestGeneration();
                    if (sourceVersion.lastAssignedSequence() != sourceVersion.persistedSequenceWatermark()) {
                        throw new IllegalStateException("source did not flush through the checkpoint sequence");
                    }
                    for (ManifestFileMetadata file : sourceVersion.allFiles()) {
                        copyForced(root.resolve(VersionSet.sstableName(file.fileNumber())),
                                temporary.resolve(VersionSet.sstableName(file.fileNumber())));
                    }
                }
                long checkpointManifest = Math.max(sourceVersion.nextFileNumber(), sourceManifest + 1);
                ManifestEdit snapshot = new ManifestEdit(ManifestEdit.Kind.SNAPSHOT, 1, checkpointManifest + 1,
                        sourceVersion.lastAssignedSequence(), sourceVersion.persistedSequenceWatermark(), 0,
                        sourceVersion.allFiles(), List.of());
                try (VersionSet ignored = VersionSet.create(temporary, identity.databaseId(), checkpointManifest,
                        snapshot, System.currentTimeMillis())) { ignored.current(); }
                long tableBytes = sourceVersion.allFiles().stream().mapToLong(ManifestFileMetadata::fileSize).sum();
                CheckpointMetadataV1 metadata = new CheckpointMetadataV1(identity.databaseId(),
                        sourceVersion.persistedSequenceWatermark(), System.currentTimeMillis(), sourceManifest,
                        sourceManifest, checkpointManifest, sourceVersion.allFiles().size(), tableBytes,
                        options.compatibilityFingerprint());
                writeForced(temporary.resolve("CHECKPOINT-METADATA"), metadata.encode()); syncDirectory(temporary);
                verifyCheckpointDirectory(temporary); Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                syncDirectory(parent);
            } catch (Throwable failure) {
                cleanupTemporaryDirectory(temporary, failure);
                if (failure instanceof IOException exception) throw exception;
                if (failure instanceof RuntimeException exception) throw exception;
                throw new IOException("checkpoint creation failed", failure);
            }
        }
        System.out.println("Checkpoint created and fully verified: " + destination); return 0;
    }

    private static int restoreVerify(String[] arguments) throws IOException {
        if (arguments.length != 2) throw new IllegalArgumentException("restore-verify requires a checkpoint directory");
        Path checkpoint = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        try (DatabaseLock lock = DatabaseLock.acquire(checkpoint)) {
            java.util.Objects.requireNonNull(lock); verifyCheckpointDirectory(checkpoint);
        }
        System.out.println("Checkpoint is complete and independently restorable: " + checkpoint); return 0;
    }

    private static int repairTail(String[] arguments) throws IOException {
        if (arguments.length < 2) throw new IllegalArgumentException("repair-tail requires a database directory");
        List<String> values = Arrays.asList(arguments); boolean confirmed = values.contains("--yes");
        boolean backup = !values.contains("--no-backup"); Path root = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        try (DatabaseLock lock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(lock);
            DatabaseIdentityV1 identity = DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
            ManifestInspection manifest = VersionSet.inspect(root, identity.databaseId()); List<TailRepair> repairs = new ArrayList<>();
            if (manifest.incompleteTailBytes() > 0) repairs.add(new TailRepair(manifest.manifestPath(),
                    manifest.physicalBytes() - manifest.incompleteTailBytes(), manifest.incompleteTailBytes()));
            long minimumWal = manifest.version().minimumWalFileNumber();
            if (minimumWal > 0) {
                Path wal = root.resolve(WalFormatV1.fileName(minimumWal)); long validEnd = validWalEnd(wal, identity.databaseId(), minimumWal);
                long trailing = Files.size(wal) - validEnd; if (trailing > 0) repairs.add(new TailRepair(wal, validEnd, trailing));
            }
            if (repairs.isEmpty()) { System.out.println("No eligible incomplete manifest or WAL tail found."); return 0; }
            for (TailRepair repair : repairs) System.out.println("Eligible repair: truncate " + repair.path.getFileName()
                    + " from " + Files.size(repair.path) + " to " + repair.validBytes + " bytes (remove " + repair.trailingBytes + ")");
            if (!confirmed) { System.out.println("No files changed. Re-run with --yes to apply this exact plan."); return 2; }
            for (TailRepair repair : repairs) {
                if (backup) {
                    String suffix = ".pre-repair-" + System.currentTimeMillis() + '-' + UUID.randomUUID().toString().replace("-", "");
                    copyForced(repair.path, repair.path.resolveSibling(repair.path.getFileName() + suffix));
                }
                try (FileChannel channel = FileChannel.open(repair.path, StandardOpenOption.WRITE)) {
                    channel.truncate(repair.validBytes); channel.force(true);
                }
            }
            syncDirectory(root); ManifestInspection verified = VersionSet.inspect(root, identity.databaseId());
            if (verified.incompleteTailBytes() != 0) throw new IOException("post-repair manifest verification still reports a tail");
            if (verified.version().minimumWalFileNumber() > 0) validWalEnd(
                    root.resolve(WalFormatV1.fileName(verified.version().minimumWalFileNumber())), identity.databaseId(),
                    verified.version().minimumWalFileNumber());
        }
        System.out.println("Tail repair applied and post-repair verification succeeded."); return 0;
    }

    private static long validWalEnd(Path path, UUID databaseId, long segmentNumber) throws IOException {
        byte[] contents = Files.readAllBytes(path);
        if (contents.length < WalFormatV1.HEADER_BLOCK_BYTES) throw new IllegalArgumentException("WAL header is incomplete");
        WalSegmentHeader.decode(Arrays.copyOf(contents, WalFormatV1.HEADER_BLOCK_BYTES), databaseId, segmentNumber);
        List<byte[]> groups = WalFragmentCodec.reassemble(Arrays.copyOfRange(contents, WalFormatV1.HEADER_BLOCK_BYTES,
                contents.length), WalFormatV1.HEADER_BLOCK_BYTES);
        long validEnd = WalFormatV1.HEADER_BLOCK_BYTES;
        for (byte[] group : groups) validEnd = WalFormatV1.estimateEndOffset(validEnd, group.length);
        return validEnd;
    }

    private static int rebuildCurrent(String[] arguments) throws IOException {
        if (arguments.length < 2) throw new IllegalArgumentException("rebuild-current requires a database directory");
        boolean confirmed = Arrays.asList(arguments).contains("--yes");
        Path root = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        try (DatabaseLock lock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(lock);
            DatabaseIdentityV1 identity = DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
            List<ManifestInspection> candidates = new ArrayList<>();
            try (var entries = Files.list(root)) {
                for (Path path : entries.filter(candidate -> candidate.getFileName().toString()
                        .matches("MANIFEST-[0-9]{20}\\.aeman")).toList()) {
                    try {
                        ManifestInspection inspection = VersionSet.inspectManifest(root, identity.databaseId(), path);
                        if (inspection.incompleteTailBytes() == 0) candidates.add(inspection);
                    } catch (IllegalArgumentException | IOException ignored) {
                        // A corrupt or incomplete candidate cannot establish authority.
                    }
                }
            }
            if (candidates.isEmpty()) throw new IllegalArgumentException("no complete valid manifest candidate exists");
            candidates.sort(Comparator.comparingLong(candidate -> candidate.header().manifestFileNumber()));
            ManifestInspection selected = candidates.get(candidates.size() - 1);
            if (candidates.size() > 1) {
                ManifestInspection previous = candidates.get(candidates.size() - 2);
                if (!equivalentTerminalVersion(previous.version(), selected.version())) {
                    throw new IllegalArgumentException("multiple incompatible valid manifests make CURRENT authority ambiguous");
                }
            }
            System.out.println("Authoritative candidate: " + selected.manifestPath().getFileName()
                    + " (records=" + selected.recordCount() + ", sequence=" + selected.version().lastAssignedSequence() + ")");
            if (!confirmed) { System.out.println("No files changed. Re-run with --yes to publish CURRENT."); return 2; }
            Path temporary = root.resolve("CURRENT.tmp-" + UUID.randomUUID().toString().replace("-", ""));
            try {
                writeForced(temporary, CurrentFileV1.encode(identity.databaseId(), selected.header().manifestFileNumber()));
                Files.move(temporary, root.resolve("CURRENT"), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                syncDirectory(root);
            } finally { Files.deleteIfExists(temporary); }
            VersionSet.inspect(root, identity.databaseId());
        }
        System.out.println("CURRENT rebuilt and verified without modifying the manifest."); return 0;
    }

    private static boolean equivalentTerminalVersion(Version left, Version right) {
        if (left.nextFileNumber() != right.nextFileNumber() || left.lastAssignedSequence() != right.lastAssignedSequence()
                || left.persistedSequenceWatermark() != right.persistedSequenceWatermark()
                || left.minimumWalFileNumber() != right.minimumWalFileNumber()
                || left.allFiles().size() != right.allFiles().size()) return false;
        for (int index = 0; index < left.allFiles().size(); index++) {
            if (!left.allFiles().get(index).contentEquals(right.allFiles().get(index))) return false;
        }
        return true;
    }

    private static int salvage(String[] arguments) throws IOException {
        if (arguments.length < 3) throw new IllegalArgumentException("salvage requires source and destination directories");
        List<String> values = Arrays.asList(arguments); SalvageMode mode = SalvageMode.LATEST_STATE;
        int modeIndex = values.indexOf("--mode");
        if (modeIndex >= 0) {
            if (modeIndex + 1 >= values.size()) throw new IllegalArgumentException("--mode requires a value");
            try { mode = SalvageMode.valueOf(values.get(modeIndex + 1).toUpperCase(Locale.ROOT)); }
            catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unknown salvage mode: " + values.get(modeIndex + 1)); }
        }
        boolean includeUnreferenced = values.contains("--include-unreferenced");
        Path source = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        Path destination = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (Files.exists(destination) || destination.startsWith(source)) throw new IllegalArgumentException("salvage destination must be absent and outside source");
        Path parent = destination.getParent(); if (parent == null || !Files.isDirectory(parent)) throw new IllegalArgumentException("salvage destination parent does not exist");
        Path temporary = parent.resolve(destination.getFileName() + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        List<String> skipped = new ArrayList<>(); int candidateFiles = 0; List<SSTableEntry> recovered = new ArrayList<>();
        try (DatabaseLock lock = DatabaseLock.acquire(source)) {
            java.util.Objects.requireNonNull(lock);
            DatabaseIdentityV1 sourceIdentity = DatabaseIdentityV1.decode(Files.readAllBytes(source.resolve("DB-IDENTITY")));
            ManifestInspection manifest = VersionSet.inspectMetadata(source, sourceIdentity.databaseId());
            java.util.LinkedHashMap<Path, ManifestFileMetadata> candidates = new java.util.LinkedHashMap<>();
            for (ManifestFileMetadata file : manifest.version().allFiles()) {
                candidates.put(source.resolve(VersionSet.sstableName(file.fileNumber())), file);
            }
            if (includeUnreferenced) try (var entries = Files.list(source)) {
                for (Path path : entries.filter(candidate -> candidate.getFileName().toString().matches("SST-[0-9]{20}\\.aess")).toList()) {
                    candidates.putIfAbsent(path, null);
                }
            }
            candidateFiles = candidates.size();
            java.util.HashMap<String, SSTableEntry> exact = new java.util.HashMap<>();
            for (var candidate : candidates.entrySet()) {
                try (SSTableReader reader = candidate.getValue() == null
                        ? SSTableReader.open(candidate.getKey(), sourceIdentity.databaseId())
                        : SSTableReader.open(candidate.getKey(), sourceIdentity.databaseId(), candidate.getValue())) {
                    for (SSTableEntry entry : reader.entries()) {
                        String identity = HexFormat.of().formatHex(entry.key().encode()); SSTableEntry previous = exact.putIfAbsent(identity, entry);
                        if (previous != null && !Arrays.equals(previous.value(), entry.value())) {
                            throw new SalvageConflictException("conflicting duplicate internal key in salvage source: " + identity);
                        }
                    }
                } catch (SalvageConflictException conflict) {
                    throw conflict;
                } catch (SSTableCorruptionException | IllegalArgumentException | IOException failure) {
                    skipped.add(candidate.getKey().getFileName() + ": " + failure.getMessage());
                }
            }
            List<Path> walCandidates;
            try (var entries = Files.list(source)) {
                walCandidates = entries.filter(path -> path.getFileName().toString()
                                .matches("WAL-[0-9]{20}\\.aewal"))
                        .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
            }
            candidateFiles += walCandidates.size();
            for (Path walPath : walCandidates) {
                recoverWalForSalvage(walPath, sourceIdentity.databaseId(), exact, skipped);
            }
            recovered.addAll(exact.values()); recovered.sort(Comparator.comparing(SSTableEntry::key));
            if (mode == SalvageMode.LATEST_STATE) recovered = latestState(recovered);
            publishSalvage(temporary, destination, recovered, mode, candidateFiles, skipped);
        } catch (Throwable failure) {
            cleanupTemporaryDirectory(temporary, failure);
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("salvage failed", failure);
        }
        System.out.println("Salvage published to " + destination + ": recovered " + recovered.size()
                + " internal records; skipped " + skipped.size() + " files.");
        if (!skipped.isEmpty()) System.out.println("WARNING: salvage may contain data loss; see SALVAGE-REPORT.json");
        return skipped.isEmpty() ? 0 : 2;
    }

    private static List<SSTableEntry> latestState(List<SSTableEntry> sorted) {
        List<SSTableEntry> result = new ArrayList<>(); byte[] previous = null;
        for (SSTableEntry entry : sorted) {
            byte[] key = entry.key().userKey();
            if (previous == null || !Arrays.equals(previous, key)) { result.add(entry); previous = key; }
        }
        return List.copyOf(result);
    }

    private static void recoverWalForSalvage(Path path, UUID databaseId,
                                             java.util.Map<String, SSTableEntry> exact,
                                             List<String> skipped) throws IOException {
        byte[] contents = Files.readAllBytes(path); String file = path.getFileName().toString();
        long segment = Long.parseLong(file.substring(4, 24));
        if (contents.length < WalFormatV1.HEADER_BLOCK_BYTES) {
            skipped.add(file + " bytes [0," + contents.length + "): incomplete segment header"); return;
        }
        try {
            WalSegmentHeader.decode(Arrays.copyOf(contents, WalFormatV1.HEADER_BLOCK_BYTES), databaseId, segment);
        } catch (RuntimeException failure) {
            skipped.add(file + " bytes [0," + contents.length + "): " + failure.getMessage()); return;
        }
        WalFragmentCodec.PrefixRecovery prefix = WalFragmentCodec.recoverPrefix(
                Arrays.copyOfRange(contents, WalFormatV1.HEADER_BLOCK_BYTES, contents.length),
                WalFormatV1.HEADER_BLOCK_BYTES);
        long groupOffset = WalFormatV1.HEADER_BLOCK_BYTES;
        for (byte[] logical : prefix.records()) {
            long groupEnd = WalFormatV1.estimateEndOffset(groupOffset, logical.length);
            try {
                for (SSTableEntry entry : decodeWalGroup(logical)) mergeSalvageEntry(exact, entry);
            } catch (SalvageConflictException conflict) {
                throw conflict;
            } catch (IllegalArgumentException failure) {
                skipped.add(file + " bytes [" + groupOffset + ',' + contents.length
                        + "): " + failure.getMessage()); return;
            }
            groupOffset = groupEnd;
        }
        if (prefix.hasIssue()) {
            skipped.add(file + " bytes [" + prefix.validEndOffset() + ',' + contents.length + "): " + prefix.issue());
        }
    }

    private static List<SSTableEntry> decodeWalGroup(byte[] encoded) {
        if (encoded.length < WalFormatV1.GROUP_HEADER_BYTES) throw new IllegalArgumentException("short WAL logical group");
        ByteBuffer bytes = ByteBuffer.wrap(encoded).order(ByteOrder.LITTLE_ENDIAN); byte[] magic = new byte[8]; bytes.get(magic);
        if (!Arrays.equals(magic, "AETHGRP1".getBytes(java.nio.charset.StandardCharsets.US_ASCII))
                || bytes.getShort() != 1 || Short.toUnsignedInt(bytes.getShort()) != WalFormatV1.GROUP_HEADER_BYTES
                || bytes.getInt() != encoded.length) throw new IllegalArgumentException("invalid WAL logical-group header");
        long first = bytes.getLong(), last = bytes.getLong(); int count = bytes.getInt();
        if (bytes.getInt() != 0 || bytes.getInt() != 0
                || bytes.getInt() != MaskedCrc32c.masked(encoded, 0, 44)
                || first < 1 || last < first || last - first + 1 != count || count > 10_000) {
            throw new IllegalArgumentException("invalid WAL logical-group metadata");
        }
        List<SSTableEntry> result = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            if (bytes.remaining() < WalFormatV1.OPERATION_HEADER_BYTES) throw new IllegalArgumentException("truncated WAL operation");
            int type = Byte.toUnsignedInt(bytes.get());
            if (bytes.get() != 0 || bytes.get() != 0 || bytes.get() != 0) throw new IllegalArgumentException("invalid WAL operation flags");
            int keyLength = bytes.getInt(), valueLength = bytes.getInt();
            if (keyLength < 0 || keyLength > 65_536 || valueLength < 0 || valueLength > 16 * 1024 * 1024
                    || bytes.remaining() < (long) keyLength + valueLength || type != 1 && type != 2
                    || type == 2 && valueLength != 0) throw new IllegalArgumentException("invalid WAL operation");
            byte[] key = new byte[keyLength], value = new byte[valueLength]; bytes.get(key).get(value);
            result.add(new SSTableEntry(new InternalKey(key, first + index, (byte) type), value));
        }
        if (bytes.hasRemaining()) throw new IllegalArgumentException("WAL logical-group trailing bytes");
        return List.copyOf(result);
    }

    private static void mergeSalvageEntry(java.util.Map<String, SSTableEntry> exact, SSTableEntry entry) {
        String identity = HexFormat.of().formatHex(entry.key().encode()); SSTableEntry previous = exact.putIfAbsent(identity, entry);
        if (previous != null && !Arrays.equals(previous.value(), entry.value())) {
            throw new SalvageConflictException("conflicting duplicate internal key in salvage source: " + identity);
        }
    }

    private static void publishSalvage(Path temporary, Path destination, List<SSTableEntry> entries,
                                       SalvageMode mode, int candidateFiles, List<String> skipped) throws IOException {
        Files.createDirectory(temporary); UUID databaseId = UUID.randomUUID(); long now = System.currentTimeMillis();
        writeForced(temporary.resolve("DB-IDENTITY"), new DatabaseIdentityV1(databaseId, now, 0, 2).encode());
        writeForced(temporary.resolve("FORMAT-OPTIONS"), new FormatOptionsV1(databaseId, now).encode());
        writeWalHeader(temporary.resolve(WalFormatV1.fileName(1)), databaseId,
                Math.addExact(entries.stream().mapToLong(entry -> entry.key().sequence()).max().orElse(0), 1), now);
        List<ManifestFileMetadata> additions = new ArrayList<>(); long lastSequence = 0;
        if (!entries.isEmpty()) {
            Path tablePath = temporary.resolve(VersionSet.sstableName(2));
            SSTableBuilder builder = new SSTableBuilder(tablePath, 2, databaseId, now);
            for (SSTableEntry entry : entries) { builder.add(entry.key(), entry.value()); lastSequence = Math.max(lastSequence, entry.key().sequence()); }
            TableFileMetadata table = builder.finish(); additions.add(new ManifestFileMetadata(2, 0, table.fileSize(), table.entryCount(),
                    table.smallestSequence(), table.largestSequence(), table.smallestInternalKey(), table.largestInternalKey()));
        }
        ManifestEdit snapshot = new ManifestEdit(ManifestEdit.Kind.SNAPSHOT, 1, entries.isEmpty() ? 2 : 3,
                lastSequence, lastSequence, 1, additions, List.of());
        try (VersionSet versions = VersionSet.create(temporary, databaseId, 1, snapshot, now)) { versions.current(); }
        String report = "{\"mode\":\"" + mode + "\",\"candidateFiles\":" + candidateFiles
                + ",\"recoveredInternalRecords\":" + entries.size() + ",\"skippedFiles\":" + skipped.size()
                + ",\"skipped\":[" + skipped.stream().map(AetherCli::jsonString).collect(java.util.stream.Collectors.joining(",")) + "]"
                + ",\"possibleDataLoss\":" + !skipped.isEmpty() + "}\n";
        writeForced(temporary.resolve("SALVAGE-REPORT.json"), report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        syncDirectory(temporary); VersionSet.inspect(temporary, databaseId);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE); syncDirectory(destination.getParent());
    }

    private static void writeWalHeader(Path path, UUID databaseId, long firstSequence, long creationEpochMillis) throws IOException {
        io.aetherdb.wal.format.WalSegmentHeader header = new io.aetherdb.wal.format.WalSegmentHeader(
                databaseId, 1, 0, firstSequence, creationEpochMillis);
        writeForced(path, header.encodeBlock());
    }

    private static String jsonString(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\f' -> encoded.append("\\f");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (character < 0x20) encoded.append(String.format("\\u%04x", (int) character));
                    else encoded.append(character);
                }
            }
        }
        return encoded.append('"').toString();
    }

    private static void verifyCheckpointDirectory(Path checkpoint) throws IOException {
        CheckpointMetadataV1 metadata = CheckpointMetadataV1.decode(Files.readAllBytes(checkpoint.resolve("CHECKPOINT-METADATA")));
        DatabaseIdentityV1 identity = DatabaseIdentityV1.decode(Files.readAllBytes(checkpoint.resolve("DB-IDENTITY")));
        FormatOptionsV1 options = FormatOptionsV1.decode(Files.readAllBytes(checkpoint.resolve("FORMAT-OPTIONS")));
        if (!identity.databaseId().equals(metadata.databaseId()) || !identity.databaseId().equals(options.databaseId())
                || !Arrays.equals(metadata.compatibilityFingerprint(), options.compatibilityFingerprint())) {
            throw new IllegalArgumentException("checkpoint identity or fingerprint mismatch");
        }
        ManifestInspection inspection = VersionSet.inspect(checkpoint, identity.databaseId()); Version version = inspection.version();
        if (inspection.incompleteTailBytes() != 0 || version.minimumWalFileNumber() != 0
                || version.lastAssignedSequence() != metadata.checkpointSequence()
                || version.persistedSequenceWatermark() != metadata.checkpointSequence()
                || version.allFiles().size() != metadata.sstableFileCount()
                || version.allFiles().stream().mapToLong(ManifestFileMetadata::fileSize).sum() != metadata.totalSstableBytes()
                || version.allFiles().stream().anyMatch(file -> file.largestSequence() > metadata.checkpointSequence())) {
            throw new IllegalArgumentException("checkpoint inventory or sequence boundary mismatch");
        }
        try (var entries = Files.list(checkpoint)) {
            if (entries.anyMatch(path -> path.getFileName().toString().matches("WAL-[0-9]{20}\\.aewal"))) {
                throw new IllegalArgumentException("published checkpoint contains a WAL");
            }
        }
    }

    private static void copyForced(Path source, Path destination) throws IOException {
        Files.copy(source, destination); try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) { channel.force(true); }
        if (Files.size(source) != Files.size(destination)) throw new IOException("checkpoint copy size mismatch: " + source.getFileName());
    }
    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer input = ByteBuffer.wrap(bytes); while (input.hasRemaining()) channel.write(input); channel.force(true);
        }
    }
    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }
    private static void cleanupTemporaryDirectory(Path temporary, Throwable failure) {
        if (!Files.exists(temporary)) return;
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  aether version");
        System.out.println("  aether inspect <database-directory> [--json] [--unsafe-no-lock]");
        System.out.println("  aether verify <database-directory> [--level METADATA|CHECKSUMS|FULL] [--json] [--unsafe-no-lock]");
        System.out.println("  aether checkpoint <source-database> <destination-directory>");
        System.out.println("  aether restore-verify <checkpoint-directory>");
        System.out.println("  aether repair-tail <database-directory> [--yes] [--no-backup]");
        System.out.println("  aether rebuild-current <database-directory> [--yes]");
        System.out.println("  aether salvage <source-database> <destination-directory> [--mode LATEST_STATE|PRESERVE_VALID_HISTORY] [--include-unreferenced]");
    }

    private enum VerificationLevel { METADATA, CHECKSUMS, FULL }
    private enum SalvageMode { LATEST_STATE, PRESERVE_VALID_HISTORY }

    private record Arguments(Path path, boolean json, boolean unsafeNoLock, VerificationLevel level) {
        static Arguments parse(String[] arguments, VerificationLevel defaultLevel) {
            if (arguments.length < 2) throw new IllegalArgumentException("database directory is required");
            List<String> values = Arrays.asList(arguments); VerificationLevel level = defaultLevel;
            int levelIndex = values.indexOf("--level");
            if (levelIndex >= 0) {
                if (levelIndex + 1 >= values.size()) throw new IllegalArgumentException("--level requires a value");
                try { level = VerificationLevel.valueOf(values.get(levelIndex + 1).toUpperCase(Locale.ROOT)); }
                catch (IllegalArgumentException failure) { throw new IllegalArgumentException("unknown verification level: " + values.get(levelIndex + 1)); }
            }
            return new Arguments(Path.of(arguments[1]), values.contains("--json"), values.contains("--unsafe-no-lock"), level);
        }
    }

    private record WalInfo(long number, long bytes, long firstSequence, int groups) { }
    private record TailRepair(Path path, long validBytes, long trailingBytes) { }
    private record DatabaseReport(DatabaseIdentityV1 identity, FormatOptionsV1 options, ManifestInspection manifest,
                                  List<WalInfo> wals, int[] levelFiles, long[] levelBytes, long totalTableBytes,
                                  long oldestTable, long newestTable, List<String> warnings,
                                  VerificationLevel verificationLevel, long elapsedNanos) { }
    private static final class LockUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;
        private LockUnavailableException(String message, Throwable cause) { super(message, cause); }
    }
    private static final class SalvageConflictException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;
        private SalvageConflictException(String message) { super(message); }
    }
}
