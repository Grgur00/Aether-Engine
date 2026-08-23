package io.aetherdb.tools;

import io.aetherdb.admission.AdmissionController;
import io.aetherdb.admission.AdmissionDecision;
import io.aetherdb.admission.AdmissionPolicy;
import io.aetherdb.admission.AdmissionRequest;
import io.aetherdb.admission.AdmissionResource;
import io.aetherdb.admission.AdmissionSnapshot;
import io.aetherdb.admission.ResourceLimit;
import io.aetherdb.admission.ResourceMeasurement;
import io.aetherdb.config.AetherConfigLoader;
import io.aetherdb.config.AetherConfiguration;
import io.aetherdb.config.ConfigValidationException;
import io.aetherdb.crypto.BackupObjectAead;
import io.aetherdb.crypto.EncryptedBackupObject;
import io.aetherdb.io.BackupArchiveContents;
import io.aetherdb.io.BackupArchiveV1;
import io.aetherdb.io.BackupManifestObject;
import io.aetherdb.io.BackupManifestV1;
import io.aetherdb.io.BackupObjectKind;
import io.aetherdb.io.BackupRestoreMode;
import io.aetherdb.io.BackupRestorePreflight;
import io.aetherdb.io.BackupRestorePreflightOptions;
import io.aetherdb.io.BackupRestorePreflightReport;
import io.aetherdb.io.BackupRestoreResult;
import io.aetherdb.io.BackupRestoreWriter;
import io.aetherdb.io.CheckpointMetadataV1;
import io.aetherdb.io.DatabaseIdentityV1;
import io.aetherdb.io.DatabaseLock;
import io.aetherdb.io.FormatOptionsV1;
import io.aetherdb.io.PathSecurityValidator;
import io.aetherdb.reliability.CrashContext;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;
import io.aetherdb.release.ReleaseCertificationEvaluator;
import io.aetherdb.release.ReleaseCertificationManifest;
import io.aetherdb.release.ReleaseCertificationManifestCodec;
import io.aetherdb.release.ReleaseCertificationReport;
import io.aetherdb.security.core.SecretRedactor;
import io.aetherdb.sstable.InternalKey;
import io.aetherdb.sstable.SSTableBuilder;
import io.aetherdb.sstable.SSTableCorruptionException;
import io.aetherdb.sstable.SSTableEntry;
import io.aetherdb.sstable.SSTableReader;
import io.aetherdb.sstable.TableFileMetadata;
import io.aetherdb.sstable.manifest.CurrentFileV1;
import io.aetherdb.sstable.manifest.ManifestEdit;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
import io.aetherdb.sstable.manifest.ManifestInspection;
import io.aetherdb.sstable.manifest.Version;
import io.aetherdb.sstable.manifest.VersionSet;
import io.aetherdb.wal.format.WalFormatV1;
import io.aetherdb.wal.format.WalFragmentCodec;
import io.aetherdb.wal.format.WalCorruptionException;
import io.aetherdb.wal.format.WalLogicalGroupCodec;
import io.aetherdb.wal.format.WalSegmentHeader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Offline operational CLI for durable metadata inspection and database verification. */
public final class AetherCli {
    private static final String VERSION = "0.2.0-dev";
    private static final AdmissionController ADMISSION = new AdmissionController();

    private AetherCli() {}

    /**
     * Runs the command-line entry point and exits with the command status.
     *
     * @param arguments command name, database path, and options
     */
    public static void main(String[] arguments) {
        int exit = run(arguments);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] arguments) {
        if (arguments.length == 0 || arguments[0].equals("help") || arguments[0].equals("--help")) {
            usage();
            return 0;
        }
        try {
            return switch (arguments[0]) {
                case "version", "--version" -> {
                    System.out.println("Aether Engine " + VERSION + " (format epoch 1)");
                    yield 0;
                }
                case "inspect" ->
                        inspect(Arguments.parse(arguments, VerificationLevel.METADATA), false);
                case "verify" -> inspect(Arguments.parse(arguments, VerificationLevel.FULL), true);
                case "checkpoint" -> checkpoint(arguments);
                case "restore-verify" -> restoreVerify(arguments);
                case "backup-create" -> backupCreate(arguments);
                case "backup-restore-preflight" -> backupRestorePreflight(arguments);
                case "backup-restore" -> backupRestore(arguments);
                case "backup-restore-drill" -> backupRestoreDrill(arguments);
                case "release-certify" -> releaseCertify(arguments);
                case "diagnostics" -> diagnostics(arguments);
                case "config-validate" -> configValidate(arguments);
                case "command-schema" -> commandSchema(arguments);
                case "repair-tail" -> repairTail(arguments);
                case "rebuild-current" -> rebuildCurrent(arguments);
                case "salvage" -> salvage(arguments);
                default -> {
                    System.err.println("Unknown command: " + arguments[0]);
                    usage();
                    yield 64;
                }
            };
        } catch (LockUnavailableException failure) {
            System.err.println("Database lock unavailable: " + failure.getMessage());
            return 4;
        } catch (IOException failure) {
            System.err.println("I/O error: " + failure.getMessage());
            return 4;
        } catch (IllegalArgumentException failure) {
            System.err.println("Invalid or corrupt database: " + failure.getMessage());
            return 3;
        }
    }

    private static int inspect(Arguments arguments, boolean verification) throws IOException {
        Path root = PathSecurityValidator.validateRoot(arguments.path, true);
        DatabaseLock lock = null;
        if (arguments.unsafeNoLock)
            System.err.println(
                    "WARNING: --unsafe-no-lock is forensic read-only mode; results may be"
                            + " inconsistent.");
        else
            try {
                lock = DatabaseLock.acquire(root);
            } catch (IOException failure) {
                throw new LockUnavailableException(failure.getMessage(), failure);
            }
        long started = System.nanoTime();
        try {
            DatabaseReport report = readReport(root, arguments.level, started);
            if (arguments.json) printJson(report, verification);
            else printText(report, verification);
            return report.warnings.isEmpty() ? 0 : 2;
        } finally {
            if (lock != null) lock.close();
        }
    }

    private static DatabaseReport readReport(Path root, VerificationLevel level, long started)
            throws IOException {
        DatabaseIdentityV1 identity =
                DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
        FormatOptionsV1 options =
                FormatOptionsV1.decode(Files.readAllBytes(root.resolve("FORMAT-OPTIONS")));
        if (!identity.databaseId().equals(options.databaseId()))
            throw new IllegalArgumentException("identity/options UUID mismatch");
        ManifestInspection manifest = VersionSet.inspect(root, identity.databaseId());
        Version version = manifest.version();
        List<String> warnings = new ArrayList<>();
        if (manifest.incompleteTailBytes() > 0)
            warnings.add(
                    "manifest has "
                            + manifest.incompleteTailBytes()
                            + " incomplete trailing bytes");
        List<WalInfo> wals =
                inspectWals(root, identity.databaseId(), version.minimumWalFileNumber(), level);
        long[] levelBytes = new long[7];
        int[] levelFiles = new int[7];
        long oldest = Long.MAX_VALUE, newest = 0, totalTableBytes = 0;
        for (int levelNumber = 0; levelNumber < 7; levelNumber++)
            for (ManifestFileMetadata file : version.files(levelNumber)) {
                levelFiles[levelNumber]++;
                levelBytes[levelNumber] = Math.addExact(levelBytes[levelNumber], file.fileSize());
                totalTableBytes = Math.addExact(totalTableBytes, file.fileSize());
                oldest = Math.min(oldest, file.fileNumber());
                newest = Math.max(newest, file.fileNumber());
            }
        if (level == VerificationLevel.FULL)
            verifyNoDuplicateInternalIdentities(root, identity.databaseId(), version);
        return new DatabaseReport(
                identity,
                options,
                manifest,
                wals,
                levelFiles,
                levelBytes,
                totalTableBytes,
                oldest == Long.MAX_VALUE ? 0 : oldest,
                newest,
                List.copyOf(warnings),
                level,
                System.nanoTime() - started);
    }

    private static List<WalInfo> inspectWals(
            Path root, UUID databaseId, long minimumWal, VerificationLevel level)
            throws IOException {
        List<Path> paths;
        try (var entries = Files.list(root)) {
            paths =
                    entries.filter(
                                    path ->
                                            path.getFileName()
                                                    .toString()
                                                    .matches("WAL-[0-9]{20}\\.aewal"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .toList();
        }
        List<WalInfo> result = new ArrayList<>();
        for (Path path : paths) {
            long number = Long.parseLong(path.getFileName().toString().substring(4, 24));
            byte[] contents = Files.readAllBytes(path);
            if (contents.length < WalFormatV1.HEADER_BLOCK_BYTES)
                throw new IllegalArgumentException("truncated WAL header: " + path.getFileName());
            WalSegmentHeader header =
                    WalSegmentHeader.decode(
                            Arrays.copyOf(contents, WalFormatV1.HEADER_BLOCK_BYTES),
                            databaseId,
                            number);
            int groups = -1;
            if (level != VerificationLevel.METADATA)
                groups =
                        WalFragmentCodec.reassemble(
                                        Arrays.copyOfRange(
                                                contents,
                                                WalFormatV1.HEADER_BLOCK_BYTES,
                                                contents.length),
                                        WalFormatV1.HEADER_BLOCK_BYTES)
                                .size();
            result.add(new WalInfo(number, contents.length, header.firstSequence(), groups));
        }
        if (minimumWal > 0 && result.stream().noneMatch(wal -> wal.number == minimumWal)) {
            throw new IllegalArgumentException("minimum required WAL is missing: " + minimumWal);
        }
        return List.copyOf(result);
    }

    private static void verifyNoDuplicateInternalIdentities(
            Path root, UUID databaseId, Version version) throws IOException {
        java.util.HashSet<String> identities = new java.util.HashSet<>();
        for (ManifestFileMetadata file : version.allFiles()) {
            try (io.aetherdb.sstable.SSTableReader table =
                    io.aetherdb.sstable.SSTableReader.open(
                            root.resolve(VersionSet.sstableName(file.fileNumber())),
                            databaseId,
                            file)) {
                for (io.aetherdb.sstable.SSTableEntry entry : table.entries()) {
                    String identity = HexFormat.of().formatHex(entry.key().encode());
                    if (!identities.add(identity))
                        throw new IllegalArgumentException(
                                "duplicate internal key across live SSTables: " + identity);
                }
            }
        }
    }

    private static void printText(DatabaseReport report, boolean verification) {
        Version version = report.manifest.version();
        System.out.println(
                (verification ? "Verification" : "Inspection")
                        + " level: "
                        + (verification ? report.verificationLevel : "METADATA"));
        System.out.println("Database UUID: " + report.identity.databaseId());
        System.out.println("Format epoch: 1");
        System.out.println(
                "Creator version: "
                        + report.identity.creatorMajor()
                        + "."
                        + report.identity.creatorMinor());
        System.out.println(
                "Created: " + Instant.ofEpochMilli(report.identity.creationEpochMillis()));
        System.out.println(
                "Compatibility fingerprint: "
                        + HexFormat.of().formatHex(report.options.compatibilityFingerprint()));
        System.out.println("Current manifest: " + report.manifest.manifestPath().getFileName());
        System.out.println(
                "Manifest records/bytes: "
                        + report.manifest.recordCount()
                        + "/"
                        + report.manifest.physicalBytes());
        System.out.println(
                "Sequences assigned/persisted: "
                        + version.lastAssignedSequence()
                        + "/"
                        + version.persistedSequenceWatermark());
        System.out.println("Minimum WAL segment: " + version.minimumWalFileNumber());
        System.out.println(
                "WAL files/bytes: "
                        + report.wals.size()
                        + "/"
                        + report.wals.stream().mapToLong(WalInfo::bytes).sum());
        for (int level = 0; level < 7; level++)
            System.out.println(
                    "L"
                            + level
                            + " files/bytes: "
                            + report.levelFiles[level]
                            + "/"
                            + report.levelBytes[level]);
        System.out.println(
                "SSTable oldest/newest file: " + report.oldestTable + "/" + report.newestTable);
        System.out.println("Snapshot data: unavailable offline");
        System.out.println("Elapsed: " + Duration.ofNanos(report.elapsedNanos));
        if (report.warnings.isEmpty()) System.out.println("Health: valid");
        else for (String warning : report.warnings) System.out.println("WARNING: " + warning);
    }

    private static void printJson(DatabaseReport report, boolean verification) {
        Version version = report.manifest.version();
        StringBuilder levels = new StringBuilder("[");
        for (int level = 0; level < 7; level++) {
            if (level > 0) levels.append(',');
            levels.append("{\"level\":")
                    .append(level)
                    .append(",\"files\":")
                    .append(report.levelFiles[level])
                    .append(",\"bytes\":")
                    .append(report.levelBytes[level])
                    .append('}');
        }
        levels.append(']');
        System.out.printf(
                Locale.ROOT,
                "{\"mode\":\"%s\",\"databaseUuid\":\"%s\",\"formatEpoch\":1,\"fingerprint\":\"%s\","
                        + "\"manifest\":\"%s\",\"manifestRecords\":%d,"
                        + "\"manifestBytes\":%d,\"lastAssignedSequence\":%d,"
                        + "\"persistedSequence\":%d,\"minimumWal\":%d,"
                        + "\"walFiles\":%d,\"sstableBytes\":%d,"
                        + "\"levels\":%s,\"warnings\":%d,\"elapsedNanos\":%d}%n",
                verification ? "verify" : "inspect",
                report.identity.databaseId(),
                HexFormat.of().formatHex(report.options.compatibilityFingerprint()),
                report.manifest.manifestPath().getFileName(),
                report.manifest.recordCount(),
                report.manifest.physicalBytes(),
                version.lastAssignedSequence(),
                version.persistedSequenceWatermark(),
                version.minimumWalFileNumber(),
                report.wals.size(),
                report.totalTableBytes,
                levels,
                report.warnings.size(),
                report.elapsedNanos);
    }

    private static int checkpoint(String[] arguments) throws IOException {
        if (arguments.length != 3)
            throw new IllegalArgumentException(
                    "checkpoint requires source and destination directories");
        Path source = Path.of(arguments[1]).toAbsolutePath().normalize();
        Path destination = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (source.equals(destination) || destination.startsWith(source))
            throw new IllegalArgumentException("checkpoint destination must be outside the source");
        if (Files.exists(destination))
            throw new IllegalArgumentException("checkpoint destination already exists");
        try (io.aetherdb.api.AetherDatabase database = io.aetherdb.engine.Aether.open(source)) {
            if (database.isClosed()) throw new IllegalStateException("source unexpectedly closed");
        }
        Path root = PathSecurityValidator.validateRoot(source, true);
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IllegalArgumentException("checkpoint destination parent does not exist");
        Path temporary =
                parent.resolve(
                        destination.getFileName()
                                + ".tmp-"
                                + UUID.randomUUID().toString().replace("-", ""));
        try (DatabaseLock lock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(lock);
            Files.createDirectory(temporary);
            try {
                DatabaseIdentityV1 identity =
                        DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
                FormatOptionsV1 options =
                        FormatOptionsV1.decode(Files.readAllBytes(root.resolve("FORMAT-OPTIONS")));
                copyForced(root.resolve("DB-IDENTITY"), temporary.resolve("DB-IDENTITY"));
                copyForced(root.resolve("FORMAT-OPTIONS"), temporary.resolve("FORMAT-OPTIONS"));
                Version sourceVersion;
                long sourceManifest;
                try (VersionSet versions = VersionSet.recover(root, identity.databaseId())) {
                    sourceVersion = versions.current();
                    sourceManifest = sourceVersion.manifestGeneration();
                    if (sourceVersion.lastAssignedSequence()
                            != sourceVersion.persistedSequenceWatermark()) {
                        throw new IllegalStateException(
                                "source did not flush through the checkpoint sequence");
                    }
                    for (ManifestFileMetadata file : sourceVersion.allFiles()) {
                        copyForced(
                                root.resolve(VersionSet.sstableName(file.fileNumber())),
                                temporary.resolve(VersionSet.sstableName(file.fileNumber())));
                    }
                }
                long checkpointManifest =
                        Math.max(sourceVersion.nextFileNumber(), sourceManifest + 1);
                ManifestEdit snapshot =
                        new ManifestEdit(
                                ManifestEdit.Kind.SNAPSHOT,
                                1,
                                checkpointManifest + 1,
                                sourceVersion.lastAssignedSequence(),
                                sourceVersion.persistedSequenceWatermark(),
                                0,
                                sourceVersion.allFiles(),
                                List.of());
                try (VersionSet ignored =
                        VersionSet.create(
                                temporary,
                                identity.databaseId(),
                                checkpointManifest,
                                snapshot,
                                System.currentTimeMillis())) {
                    ignored.current();
                }
                long tableBytes =
                        sourceVersion.allFiles().stream()
                                .mapToLong(ManifestFileMetadata::fileSize)
                                .sum();
                hitCheckpointAfterCopyBeforeMetadata(
                        destination,
                        temporary,
                        sourceVersion,
                        sourceManifest,
                        checkpointManifest,
                        tableBytes);
                CheckpointMetadataV1 metadata =
                        new CheckpointMetadataV1(
                                identity.databaseId(),
                                sourceVersion.persistedSequenceWatermark(),
                                System.currentTimeMillis(),
                                sourceManifest,
                                sourceManifest,
                                checkpointManifest,
                                sourceVersion.allFiles().size(),
                                tableBytes,
                                options.compatibilityFingerprint());
                writeForced(temporary.resolve("CHECKPOINT-METADATA"), metadata.encode());
                syncDirectory(temporary);
                verifyCheckpointDirectory(temporary);
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
                syncDirectory(parent);
            } catch (Throwable failure) {
                cleanupTemporaryDirectory(temporary, failure);
                if (failure instanceof IOException exception) throw exception;
                if (failure instanceof RuntimeException exception) throw exception;
                throw new IOException("checkpoint creation failed", failure);
            }
        }
        System.out.println("Checkpoint created and fully verified: " + destination);
        return 0;
    }

    static void hitCheckpointAfterCopyBeforeMetadata(
            Path destination,
            Path temporary,
            Version sourceVersion,
            long sourceManifest,
            long checkpointManifest,
            long tableBytes) {
        CrashPointRegistry.hit(
                CrashPointIds.CHECKPOINT_AFTER_COPY_BEFORE_METADATA,
                checkpointCrashContext(
                        destination,
                        temporary,
                        sourceVersion,
                        sourceManifest,
                        checkpointManifest,
                        tableBytes));
    }

    private static CrashContext checkpointCrashContext(
            Path destination,
            Path temporary,
            Version sourceVersion,
            long sourceManifest,
            long checkpointManifest,
            long tableBytes) {
        return new CrashContext(
                Map.of(
                        "destination",
                        destination.getFileName().toString(),
                        "temporary",
                        temporary.getFileName().toString(),
                        "source_manifest",
                        Long.toUnsignedString(sourceManifest),
                        "checkpoint_manifest",
                        Long.toUnsignedString(checkpointManifest),
                        "sequence",
                        Long.toUnsignedString(sourceVersion.persistedSequenceWatermark()),
                        "table_count",
                        Integer.toString(sourceVersion.allFiles().size()),
                        "table_bytes",
                        Long.toUnsignedString(tableBytes)));
    }

    private static int restoreVerify(String[] arguments) throws IOException {
        if (arguments.length != 2)
            throw new IllegalArgumentException("restore-verify requires a checkpoint directory");
        Path checkpoint = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        try (DatabaseLock lock = DatabaseLock.acquire(checkpoint)) {
            java.util.Objects.requireNonNull(lock);
            verifyCheckpointDirectory(checkpoint);
        }
        System.out.println("Checkpoint is complete and independently restorable: " + checkpoint);
        return 0;
    }

    private static int backupCreate(String[] arguments) throws IOException {
        if (arguments.length < 3)
            throw new IllegalArgumentException(
                    "backup-create requires checkpoint directory and archive path");
        List<String> values = Arrays.asList(arguments);
        Path checkpoint = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        Path archive = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (Files.exists(archive)) throw new IllegalArgumentException("backup archive already exists");
        boolean json = values.contains("--json");
        BackupAdmissionOptions admissionOptions = BackupAdmissionOptions.parse(values);
        BackupEncryptionKey encryptionKey = BackupEncryptionKey.create(values);
        BackupArchiveContents contents;
        try (DatabaseLock lock = DatabaseLock.acquire(checkpoint)) {
            java.util.Objects.requireNonNull(lock);
            contents = checkpointBackupContents(checkpoint);
        }
        if (encryptionKey != null) contents = encryptBackupContents(contents, encryptionKey);
        AdmissionDecision admission = backupAdmission(contents, admissionOptions, false);
        if (!admission.accepted()) {
            if (json) printBackupAdmissionJson("backup-create", contents, admission);
            else printBackupAdmissionText("backup-create", contents, admission);
            return 2;
        }
        Path parent = archive.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IllegalArgumentException("backup archive parent does not exist");
        try (var output = Files.newOutputStream(archive, StandardOpenOption.CREATE_NEW)) {
            BackupArchiveV1.write(output, contents.manifest(), contents.objects());
        }
        try (var input = Files.newInputStream(archive)) {
            BackupArchiveV1.read(input);
        }
        if (json) {
            System.out.printf(
                    Locale.ROOT,
                    "{\"mode\":\"backup-create\",\"backupId\":\"%s\",\"archive\":%s,"
                            + "\"objectCount\":%d,\"totalBytes\":%d}%n",
                    contents.manifest().backupId(),
                    jsonString(archive.toString()),
                    contents.manifest().objectCount(),
                    contents.manifest().totalBytes());
        } else {
            System.out.println("Backup archive created and verified: " + archive);
            System.out.println("Backup ID: " + contents.manifest().backupId());
            System.out.println(
                    "Objects/bytes: "
                            + contents.manifest().objectCount()
                            + "/"
                            + contents.manifest().totalBytes());
        }
        return 0;
    }

    private static BackupArchiveContents checkpointBackupContents(Path checkpoint)
            throws IOException {
        verifyCheckpointDirectory(checkpoint);
        DatabaseIdentityV1 identity =
                DatabaseIdentityV1.decode(Files.readAllBytes(checkpoint.resolve("DB-IDENTITY")));
        FormatOptionsV1 options =
                FormatOptionsV1.decode(Files.readAllBytes(checkpoint.resolve("FORMAT-OPTIONS")));
        CheckpointMetadataV1 metadata =
                CheckpointMetadataV1.decode(
                        Files.readAllBytes(checkpoint.resolve("CHECKPOINT-METADATA")));
        ManifestInspection inspection = VersionSet.inspect(checkpoint, identity.databaseId());
        Version version = inspection.version();
        Map<String, byte[]> objects = new java.util.LinkedHashMap<>();
        putBackupObject(objects, checkpoint, "DB-IDENTITY", "objects/DB-IDENTITY");
        putBackupObject(objects, checkpoint, "FORMAT-OPTIONS", "objects/FORMAT-OPTIONS");
        putBackupObject(
                objects,
                checkpoint,
                "CHECKPOINT-METADATA",
                "objects/CHECKPOINT-METADATA");
        putBackupObject(objects, checkpoint, "CURRENT", "objects/CURRENT");
        String manifestName = inspection.manifestPath().getFileName().toString();
        putBackupObject(objects, checkpoint, manifestName, "objects/" + manifestName);
        for (ManifestFileMetadata file : version.allFiles()) {
            String table = VersionSet.sstableName(file.fileNumber());
            putBackupObject(objects, checkpoint, table, "objects/" + table);
        }
        List<BackupManifestObject> manifestObjects = new ArrayList<>();
        for (Map.Entry<String, byte[]> entry : objects.entrySet()) {
            manifestObjects.add(
                    new BackupManifestObject(
                            entry.getKey(),
                            backupKind(entry.getKey()),
                            entry.getValue().length,
                            sha256(entry.getValue()),
                            "",
                            1));
        }
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        UUID.randomUUID(),
                        System.currentTimeMillis(),
                        VERSION,
                        1,
                        identity.databaseId(),
                        null,
                        -1,
                        -1,
                        metadata.checkpointSequence(),
                        BackupManifestV1.HASH_SHA256,
                        manifestObjects,
                        new long[0],
                        options.compatibilityFingerprint());
        return new BackupArchiveContents(manifest, objects);
    }

    private static BackupArchiveContents encryptBackupContents(
            BackupArchiveContents contents, BackupEncryptionKey key) {
        Map<String, byte[]> encryptedObjects = new java.util.LinkedHashMap<>();
        List<BackupManifestObject> encryptedManifestObjects = new ArrayList<>();
        for (BackupManifestObject object : contents.manifest().objects()) {
            EncryptedBackupObject encrypted =
                    BackupObjectAead.encrypt(
                            contents.manifest().backupId(),
                            object.path(),
                            key.key(),
                            key.epoch(),
                            contents.objectBytes(object.path()));
            encryptedObjects.put(object.path(), encrypted.encodedEnvelope());
            encryptedManifestObjects.add(
                    new BackupManifestObject(
                            object.path(),
                            object.kind(),
                            encrypted.encodedEnvelope().length,
                            sha256(encrypted.encodedEnvelope()),
                            encrypted.encryptionMetadataReference(),
                            object.requiredFormatVersion()));
        }
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        contents.manifest().backupId(),
                        contents.manifest().createdUnixMillis(),
                        contents.manifest().aetherVersion(),
                        contents.manifest().formatEpoch(),
                        contents.manifest().databaseId(),
                        contents.manifest().clusterId(),
                        contents.manifest().raftLastIncludedIndex(),
                        contents.manifest().raftLastIncludedTerm(),
                        contents.manifest().sequenceWatermark(),
                        contents.manifest().hashAlgorithm(),
                        encryptedManifestObjects,
                        new long[] {key.epoch()},
                        contents.manifest().compatibilityFingerprint());
        return new BackupArchiveContents(manifest, encryptedObjects);
    }

    private static BackupArchiveContents decryptBackupContents(
            BackupArchiveContents contents, Map<Long, byte[]> keys) {
        boolean encrypted = contents.manifest().objects().stream()
                .anyMatch(object -> !object.encryptionMetadataReference().isEmpty());
        if (!encrypted) return contents;
        Map<String, byte[]> plaintextObjects = new java.util.LinkedHashMap<>();
        List<BackupManifestObject> plaintextManifestObjects = new ArrayList<>();
        for (BackupManifestObject object : contents.manifest().objects()) {
            byte[] plaintext;
            if (object.encryptionMetadataReference().isEmpty()) {
                plaintext = contents.objectBytes(object.path());
            } else {
                long epoch = keyEpoch(object.encryptionMetadataReference());
                byte[] key = keys.get(epoch);
                if (key == null) throw new IllegalArgumentException("missing backup decryption key epoch: " + epoch);
                plaintext =
                        BackupObjectAead.decrypt(
                                contents.manifest().backupId(),
                                object.path(),
                                key,
                                contents.objectBytes(object.path()));
            }
            plaintextObjects.put(object.path(), plaintext);
            plaintextManifestObjects.add(
                    new BackupManifestObject(
                            object.path(),
                            object.kind(),
                            plaintext.length,
                            sha256(plaintext),
                            "",
                            object.requiredFormatVersion()));
        }
        BackupManifestV1 manifest =
                new BackupManifestV1(
                        contents.manifest().backupId(),
                        contents.manifest().createdUnixMillis(),
                        contents.manifest().aetherVersion(),
                        contents.manifest().formatEpoch(),
                        contents.manifest().databaseId(),
                        contents.manifest().clusterId(),
                        contents.manifest().raftLastIncludedIndex(),
                        contents.manifest().raftLastIncludedTerm(),
                        contents.manifest().sequenceWatermark(),
                        contents.manifest().hashAlgorithm(),
                        plaintextManifestObjects,
                        new long[0],
                        contents.manifest().compatibilityFingerprint());
        return new BackupArchiveContents(manifest, plaintextObjects);
    }

    private static long keyEpoch(String metadataReference) {
        String marker = ":key_epoch=";
        int index = metadataReference.lastIndexOf(marker);
        if (index < 0) throw new IllegalArgumentException("backup encryption metadata missing key epoch");
        return Long.parseLong(metadataReference.substring(index + marker.length()));
    }

    private static void putBackupObject(
            Map<String, byte[]> objects, Path root, String sourceName, String objectPath)
            throws IOException {
        objects.put(objectPath, Files.readAllBytes(root.resolve(sourceName)));
    }

    private static BackupObjectKind backupKind(String objectPath) {
        String name = objectPath.substring(objectPath.lastIndexOf('/') + 1);
        if (name.equals("DB-IDENTITY")) return BackupObjectKind.DATABASE_IDENTITY;
        if (name.equals("FORMAT-OPTIONS")) return BackupObjectKind.FORMAT_OPTIONS;
        if (name.equals("CHECKPOINT-METADATA")) return BackupObjectKind.CHECKPOINT_METADATA;
        if (name.equals("CURRENT")) return BackupObjectKind.CURRENT;
        if (name.startsWith("MANIFEST-")) return BackupObjectKind.MANIFEST;
        if (name.startsWith("SST-")) return BackupObjectKind.SSTABLE;
        throw new IllegalArgumentException("unsupported checkpoint backup object: " + objectPath);
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance(BackupManifestV1.HASH_SHA256).digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static int backupRestorePreflight(String[] arguments) throws IOException {
        BackupPreflightArguments parsed = BackupPreflightArguments.parse(arguments);
        BackupArchiveContents contents;
        try (var input = Files.newInputStream(parsed.archive)) {
            contents = BackupArchiveV1.read(input);
        }
        BackupRestorePreflightReport report =
                BackupRestorePreflight.check(contents, parsed.target, parsed.options());
        if (parsed.json) printBackupPreflightJson(contents, report, parsed);
        else printBackupPreflightText(contents, report, parsed);
        return report.passed() ? 0 : 2;
    }

    private static int backupRestore(String[] arguments) throws IOException {
        BackupPreflightArguments parsed = BackupPreflightArguments.parse(arguments);
        BackupArchiveContents contents;
        try (var input = Files.newInputStream(parsed.archive)) {
            contents = BackupArchiveV1.read(input);
        }
        contents = decryptBackupContents(contents, parsed.keys);
        AdmissionDecision admission = backupAdmission(contents, parsed.admissionOptions, false);
        if (!admission.accepted()) {
            if (parsed.json) printBackupAdmissionJson("backup-restore", contents, admission);
            else printBackupAdmissionText("backup-restore", contents, admission);
            return 2;
        }
        BackupRestoreResult result =
                BackupRestoreWriter.restoreCheckpointLayout(contents, parsed.target, parsed.options());
        if (parsed.json) printBackupRestoreJson(contents, result, parsed);
        else printBackupRestoreText(contents, result, parsed);
        return 0;
    }

    private static int backupRestoreDrill(String[] arguments) throws IOException {
        BackupPreflightArguments parsed = BackupPreflightArguments.parse(arguments);
        long started = System.nanoTime();
        BackupArchiveContents contents;
        try (var input = Files.newInputStream(parsed.archive)) {
            contents = BackupArchiveV1.read(input);
        }
        BackupRestorePreflightReport preflight =
                BackupRestorePreflight.check(contents, parsed.target, parsed.options());
        if (!preflight.passed()) {
            BackupRestoreDrillReport report =
                    BackupRestoreDrillReport.failed(
                            contents, parsed, preflight, "preflight failed", started);
            if (parsed.json) printBackupRestoreDrillJson(report);
            else printBackupRestoreDrillText(report);
            return 2;
        }

        contents = decryptBackupContents(contents, parsed.keys);
        AdmissionDecision admission = backupAdmission(contents, parsed.admissionOptions, false);
        if (!admission.accepted()) {
            BackupRestoreDrillReport report =
                    BackupRestoreDrillReport.failed(
                            contents,
                            parsed,
                            preflight,
                            "admission failed: " + String.join("; ", admission.reasons()),
                            started);
            if (parsed.json) printBackupRestoreDrillJson(report);
            else printBackupRestoreDrillText(report);
            return 2;
        }
        BackupRestoreResult result =
                BackupRestoreWriter.restoreCheckpointLayout(contents, parsed.target, parsed.options());
        List<String> failures = new ArrayList<>();
        try (DatabaseLock lock = DatabaseLock.acquire(parsed.target)) {
            java.util.Objects.requireNonNull(lock);
            verifyCheckpointDirectory(parsed.target);
        } catch (IllegalArgumentException | IOException failure) {
            failures.add("restored checkpoint verification failed: " + failure.getMessage());
        }
        BackupRestoreDrillReport report =
                BackupRestoreDrillReport.completed(contents, parsed, result, failures, started);
        if (parsed.json) printBackupRestoreDrillJson(report);
        else printBackupRestoreDrillText(report);
        return report.passed() ? 0 : 2;
    }

    private static void printBackupPreflightText(
            BackupArchiveContents contents,
            BackupRestorePreflightReport report,
            BackupPreflightArguments arguments) {
        System.out.println("Backup restore preflight: " + (report.passed() ? "PASS" : "FAIL"));
        System.out.println("Backup ID: " + contents.manifest().backupId());
        System.out.println("Database ID: " + contents.manifest().databaseId());
        System.out.println("Cluster ID: " + contents.manifest().clusterId());
        System.out.println("Restore mode: " + arguments.mode);
        System.out.println("Objects/bytes: " + report.objectCount() + "/" + report.totalBytes());
        if (!report.passed())
            for (String failure : report.failures()) System.out.println("FAILURE: " + failure);
    }

    private static void printBackupPreflightJson(
            BackupArchiveContents contents,
            BackupRestorePreflightReport report,
            BackupPreflightArguments arguments) {
        System.out.printf(
                Locale.ROOT,
                "{\"mode\":\"backup-restore-preflight\",\"passed\":%s,"
                        + "\"restoreMode\":\"%s\",\"backupId\":\"%s\",\"databaseId\":\"%s\","
                        + "\"clusterId\":%s,\"objectCount\":%d,\"totalBytes\":%d,"
                        + "\"failures\":[%s]}%n",
                report.passed(),
                arguments.mode,
                contents.manifest().backupId(),
                contents.manifest().databaseId(),
                contents.manifest().clusterId() == null
                        ? "null"
                        : jsonString(contents.manifest().clusterId().toString()),
                report.objectCount(),
                report.totalBytes(),
                report.failures().stream()
                        .map(AetherCli::jsonString)
                        .collect(java.util.stream.Collectors.joining(",")));
    }

    private static void printBackupRestoreText(
            BackupArchiveContents contents,
            BackupRestoreResult result,
            BackupPreflightArguments arguments) {
        System.out.println("Backup restore: COMPLETE");
        System.out.println("Backup ID: " + contents.manifest().backupId());
        System.out.println("Restore mode: " + arguments.mode);
        System.out.println("Target: " + result.targetDirectory());
        System.out.println(
                "Objects/bytes: "
                        + result.preflightReport().objectCount()
                        + "/"
                        + result.preflightReport().totalBytes());
        System.out.println("Restored objects: " + result.restoredObjects().size());
    }

    private static void printBackupRestoreJson(
            BackupArchiveContents contents,
            BackupRestoreResult result,
            BackupPreflightArguments arguments) {
        System.out.printf(
                Locale.ROOT,
                "{\"mode\":\"backup-restore\",\"completed\":true,"
                        + "\"restoreMode\":\"%s\",\"backupId\":\"%s\",\"target\":%s,"
                        + "\"objectCount\":%d,\"totalBytes\":%d,\"restoredObjects\":%d}%n",
                arguments.mode,
                contents.manifest().backupId(),
                jsonString(result.targetDirectory().toString()),
                result.preflightReport().objectCount(),
                result.preflightReport().totalBytes(),
                result.restoredObjects().size());
    }

    private static void printBackupRestoreDrillText(BackupRestoreDrillReport report) {
        System.out.println("Backup restore drill: " + (report.passed() ? "PASS" : "FAIL"));
        System.out.println("Backup ID: " + report.backupId());
        System.out.println("Restore mode: " + report.restoreMode());
        System.out.println("Target: " + report.target());
        System.out.println("Objects/bytes: " + report.objectCount() + "/" + report.totalBytes());
        System.out.println("Restored objects: " + report.restoredObjects());
        System.out.println("Checkpoint verified: " + report.verified());
        System.out.println("Elapsed: " + Duration.ofNanos(report.elapsedNanos()));
        for (String failure : report.failures()) System.out.println("FAILURE: " + failure);
    }

    private static void printBackupRestoreDrillJson(BackupRestoreDrillReport report) {
        System.out.printf(
                Locale.ROOT,
                "{\"mode\":\"backup-restore-drill\",\"passed\":%s,"
                        + "\"restoreMode\":\"%s\",\"backupId\":\"%s\",\"databaseId\":\"%s\","
                        + "\"target\":%s,\"objectCount\":%d,\"totalBytes\":%d,"
                        + "\"restoredObjects\":%d,\"checkpointVerified\":%s,"
                        + "\"elapsedNanos\":%d,\"failures\":[%s]}%n",
                report.passed(),
                report.restoreMode(),
                report.backupId(),
                report.databaseId(),
                jsonString(report.target().toString()),
                report.objectCount(),
                report.totalBytes(),
                report.restoredObjects(),
                report.verified(),
                report.elapsedNanos(),
                report.failures().stream()
                        .map(AetherCli::jsonString)
                        .collect(java.util.stream.Collectors.joining(",")));
    }

    static AdmissionDecision backupAdmission(
            BackupArchiveContents contents, BackupAdmissionOptions options, boolean draining) {
        return ADMISSION.evaluate(
                options.policy(),
                new AdmissionSnapshot(
                        Map.of(
                                AdmissionResource.BACKUP_OPERATION_BYTES,
                                new ResourceMeasurement(
                                        AdmissionResource.BACKUP_OPERATION_BYTES,
                                        0),
                                AdmissionResource.BACKUP_OBJECT_COUNT,
                                new ResourceMeasurement(
                                        AdmissionResource.BACKUP_OBJECT_COUNT,
                                        0))),
                new AdmissionRequest(
                        Map.of(
                                AdmissionResource.BACKUP_OPERATION_BYTES,
                                contents.manifest().totalBytes(),
                                AdmissionResource.BACKUP_OBJECT_COUNT,
                                contents.manifest().objectCount()),
                        draining));
    }

    private static void printBackupAdmissionText(
            String mode, BackupArchiveContents contents, AdmissionDecision admission) {
        System.out.println("Backup admission: FAIL");
        System.out.println("Mode: " + mode);
        System.out.println("Backup ID: " + contents.manifest().backupId());
        System.out.println(
                "Objects/bytes: "
                        + contents.manifest().objectCount()
                        + "/"
                        + contents.manifest().totalBytes());
        System.out.println("Outcome: " + admission.outcome());
        for (String reason : admission.reasons()) System.out.println("FAILURE: " + reason);
    }

    private static void printBackupAdmissionJson(
            String mode, BackupArchiveContents contents, AdmissionDecision admission) {
        System.out.printf(
                Locale.ROOT,
                "{\"mode\":\"%s\",\"admitted\":false,\"outcome\":\"%s\","
                        + "\"backupId\":\"%s\",\"objectCount\":%d,\"totalBytes\":%d,"
                        + "\"failures\":[%s]}%n",
                mode,
                admission.outcome(),
                contents.manifest().backupId(),
                contents.manifest().objectCount(),
                contents.manifest().totalBytes(),
                admission.reasons().stream()
                        .map(AetherCli::jsonString)
                        .collect(java.util.stream.Collectors.joining(",")));
    }

    private static int releaseCertify(String[] arguments) throws IOException {
        if (arguments.length < 2)
            throw new IllegalArgumentException("release-certify requires a manifest path");
        List<String> values = Arrays.asList(arguments);
        Path manifestPath = Path.of(arguments[1]).toAbsolutePath().normalize();
        ReleaseCertificationManifest manifest =
                ReleaseCertificationManifestCodec.decode(Files.readString(manifestPath));
        ReleaseCertificationReport report = ReleaseCertificationEvaluator.evaluate(manifest);
        if (values.contains("--json")) {
            System.out.println(ReleaseCertificationEvaluator.toJson(manifest, report));
        } else {
            printReleaseCertificationText(manifest, report);
        }
        return report.productionReady() ? 0 : 2;
    }

    private static int diagnostics(String[] arguments) throws IOException {
        if (arguments.length < 2)
            throw new IllegalArgumentException("diagnostics requires a bundle path");
        List<String> values = Arrays.asList(arguments);
        Path bundle = Path.of(arguments[1]).toAbsolutePath().normalize();
        if (Files.exists(bundle)) throw new IllegalArgumentException("diagnostics bundle already exists");
        Path config = null;
        int configIndex = values.indexOf("--config");
        if (configIndex >= 0) config = Path.of(requiredValue(values, configIndex, "--config"));
        DiagnosticsBundleReport report =
                writeDiagnosticsBundle(bundle, config, System.getenv());
        if (values.contains("--json")) {
            System.out.printf(
                    Locale.ROOT,
                    "{\"mode\":\"diagnostics\",\"bundle\":%s,\"entries\":%d}%n",
                    jsonString(report.bundle().toString()),
                    report.entries());
        } else {
            System.out.println("Diagnostics bundle created: " + report.bundle());
            System.out.println("Entries: " + report.entries());
        }
        return 0;
    }

    private static int configValidate(String[] arguments) throws IOException {
        if (arguments.length < 2)
            throw new IllegalArgumentException("config-validate requires a properties file");
        List<String> values = Arrays.asList(arguments);
        Path config = Path.of(arguments[1]).toAbsolutePath().normalize();
        boolean json = values.contains("--json");
        Map<String, String> overrides = configOverrides(values);
        AetherConfigLoader loader = AetherConfigLoader.defaults();
        try {
            AetherConfiguration configuration = loader.load(config, System.getenv(), overrides);
            Map<String, String> diagnostics = loader.redactedDiagnosticView(configuration);
            if (json) {
                System.out.printf(
                        Locale.ROOT,
                        "{\"mode\":\"config-validate\",\"valid\":true,\"config\":%s,\"settings\":{%s}}%n",
                        jsonString(config.toString()),
                        settingsJson(diagnostics));
            } else {
                System.out.println("Configuration: VALID");
                System.out.println("Config: " + config);
                diagnostics.forEach((name, value) -> System.out.println(name + "=" + value));
            }
            return 0;
        } catch (ConfigValidationException failure) {
            if (json) {
                System.out.printf(
                        Locale.ROOT,
                        "{\"mode\":\"config-validate\",\"valid\":false,\"config\":%s,\"error\":%s}%n",
                        jsonString(config.toString()),
                        jsonString(failure.getMessage()));
            } else {
                System.err.println("Configuration: INVALID");
                System.err.println(failure.getMessage());
            }
            return 2;
        }
    }

    private static int commandSchema(String[] arguments) {
        if (arguments.length > 2)
            throw new IllegalArgumentException("command-schema accepts at most one command name");
        if (arguments.length == 2) {
            CliCommandSchema schema = cliCommandSchemas().stream()
                    .filter(candidate -> candidate.command().equals(arguments[1]))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown schema command: " + arguments[1]));
            System.out.println(schema.toJson());
            return 0;
        }
        System.out.printf(
                Locale.ROOT,
                "{\"mode\":\"command-schema\",\"commands\":[%s]}%n",
                cliCommandSchemas().stream()
                        .map(CliCommandSchema::toJson)
                        .collect(java.util.stream.Collectors.joining(",")));
        return 0;
    }

    static List<CliCommandSchema> cliCommandSchemas() {
        return List.of(
                new CliCommandSchema(
                        "inspect",
                        "inspect",
                        List.of(0, 2, 3, 4),
                        List.of(
                                "databaseUuid",
                                "formatEpoch",
                                "fingerprint",
                                "manifest",
                                "warnings",
                                "elapsedNanos")),
                new CliCommandSchema(
                        "verify",
                        "verify",
                        List.of(0, 2, 3, 4),
                        List.of(
                                "databaseUuid",
                                "formatEpoch",
                                "manifestRecords",
                                "walFiles",
                                "sstableBytes",
                                "warnings")),
                new CliCommandSchema(
                        "backup-create",
                        "backup-create",
                        List.of(0, 2, 3, 4),
                        List.of("backupId", "archive", "objectCount", "totalBytes")),
                new CliCommandSchema(
                        "backup-restore-preflight",
                        "backup-restore-preflight",
                        List.of(0, 2, 3, 4),
                        List.of(
                                "passed",
                                "restoreMode",
                                "backupId",
                                "databaseId",
                                "objectCount",
                                "totalBytes",
                                "failures")),
                new CliCommandSchema(
                        "backup-restore",
                        "backup-restore",
                        List.of(0, 2, 3, 4),
                        List.of(
                                "completed",
                                "restoreMode",
                                "backupId",
                                "target",
                                "objectCount",
                                "totalBytes",
                                "restoredObjects")),
                new CliCommandSchema(
                        "backup-restore-drill",
                        "backup-restore-drill",
                        List.of(0, 2, 3, 4),
                        List.of(
                                "passed",
                                "restoreMode",
                                "backupId",
                                "databaseId",
                                "target",
                                "checkpointVerified",
                                "failures")),
                new CliCommandSchema(
                        "release-certify",
                        "release-certification",
                        List.of(0, 2, 3, 4),
                        List.of(
                                "productionReady",
                                "releaseVersion",
                                "gitCommit",
                                "greenRows",
                                "redRows",
                                "missingRows",
                                "failures")),
                new CliCommandSchema(
                        "diagnostics",
                        "diagnostics",
                        List.of(0, 3, 4),
                        List.of("bundle", "entries")),
                new CliCommandSchema(
                        "config-validate",
                        "config-validate",
                        List.of(0, 2, 3, 4),
                        List.of("valid", "config", "settings", "error")));
    }

    static DiagnosticsBundleReport writeDiagnosticsBundle(
            Path bundle, Path config, Map<String, String> environment) throws IOException {
        Path parent = bundle.toAbsolutePath().normalize().getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IllegalArgumentException("diagnostics bundle parent does not exist");
        List<String> entries = new ArrayList<>();
        try (ZipOutputStream zip =
                new ZipOutputStream(
                        Files.newOutputStream(bundle, StandardOpenOption.CREATE_NEW))) {
            putZipEntry(zip, "manifest.json", diagnosticsManifestJson(config));
            entries.add("manifest.json");
            putZipEntry(zip, "environment.properties", redactedEnvironment(environment));
            entries.add("environment.properties");
            if (config != null) {
                putZipEntry(zip, "config.properties", redactedProperties(config));
                entries.add("config.properties");
            }
        }
        return new DiagnosticsBundleReport(bundle.toAbsolutePath().normalize(), entries.size());
    }

    private static String diagnosticsManifestJson(Path config) {
        return String.format(
                Locale.ROOT,
                "{\"mode\":\"diagnostics\",\"aetherVersion\":\"%s\","
                        + "\"formatEpoch\":1,\"javaVersion\":%s,\"osName\":%s,"
                        + "\"configIncluded\":%s}%n",
                VERSION,
                jsonString(System.getProperty("java.version", "")),
                jsonString(System.getProperty("os.name", "")),
                config != null);
    }

    private static String redactedEnvironment(Map<String, String> environment) {
        StringBuilder builder = new StringBuilder();
        new java.util.TreeMap<>(environment).forEach(
                (name, value) -> {
                    if (name.startsWith("AETHER_")) {
                        builder.append(name)
                                .append('=')
                                .append(redactIfSensitive(name, value))
                                .append('\n');
                    }
                });
        return builder.toString();
    }

    private static String redactedProperties(Path config) throws IOException {
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(config)) {
            properties.load(reader);
        }
        StringBuilder builder = new StringBuilder();
        properties.stringPropertyNames().stream()
                .sorted()
                .forEach(
                        name ->
                                builder.append(name)
                                        .append('=')
                                        .append(redactIfSensitive(name, properties.getProperty(name)))
                                        .append('\n'));
        return builder.toString();
    }

    private static String redactIfSensitive(String name, String value) {
        if (value == null) return "";
        String normalized = name.toLowerCase(Locale.ROOT);
        if (normalized.contains("secret")
                || normalized.contains("password")
                || normalized.contains("token")
                || normalized.contains("credential")
                || normalized.contains("private")
                || normalized.contains("kek")
                || normalized.contains("key")) {
            return SecretRedactor.redact("diagnostic", value);
        }
        return value;
    }

    private static void putZipEntry(ZipOutputStream zip, String name, String value)
            throws IOException {
        ZipEntry entry = new ZipEntry(name);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void printReleaseCertificationText(
            ReleaseCertificationManifest manifest, ReleaseCertificationReport report) {
        System.out.println(
                "Release certification: " + (report.productionReady() ? "PASS" : "FAIL"));
        System.out.println("Release version: " + manifest.releaseVersion());
        System.out.println("Git commit: " + manifest.gitCommit());
        System.out.println("Created at: " + manifest.createdAt());
        System.out.println(
                "Evidence green/not-applicable/red/missing: "
                        + report.greenRows()
                        + "/"
                        + report.notApplicableRows()
                        + "/"
                        + report.redRows()
                        + "/"
                        + report.missingRows());
        for (String failure : report.failures()) System.out.println("FAILURE: " + failure);
        for (String warning : report.warnings()) System.out.println("WARNING: " + warning);
    }

    private static int repairTail(String[] arguments) throws IOException {
        if (arguments.length < 2)
            throw new IllegalArgumentException("repair-tail requires a database directory");
        List<String> values = Arrays.asList(arguments);
        boolean confirmed = values.contains("--yes");
        boolean backup = !values.contains("--no-backup");
        Path root = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        try (DatabaseLock lock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(lock);
            DatabaseIdentityV1 identity =
                    DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
            ManifestInspection manifest = VersionSet.inspect(root, identity.databaseId());
            List<TailRepair> repairs = new ArrayList<>();
            if (manifest.incompleteTailBytes() > 0)
                repairs.add(
                        new TailRepair(
                                manifest.manifestPath(),
                                manifest.physicalBytes() - manifest.incompleteTailBytes(),
                                manifest.incompleteTailBytes()));
            long minimumWal = manifest.version().minimumWalFileNumber();
            if (minimumWal > 0) {
                Path wal = root.resolve(WalFormatV1.fileName(minimumWal));
                long validEnd = validWalEnd(wal, identity.databaseId(), minimumWal);
                long trailing = Files.size(wal) - validEnd;
                if (trailing > 0) repairs.add(new TailRepair(wal, validEnd, trailing));
            }
            if (repairs.isEmpty()) {
                System.out.println("No eligible incomplete manifest or WAL tail found.");
                return 0;
            }
            for (TailRepair repair : repairs)
                System.out.println(
                        "Eligible repair: truncate "
                                + repair.path.getFileName()
                                + " from "
                                + Files.size(repair.path)
                                + " to "
                                + repair.validBytes
                                + " bytes (remove "
                                + repair.trailingBytes
                                + ")");
            if (!confirmed) {
                System.out.println("No files changed. Re-run with --yes to apply this exact plan.");
                return 2;
            }
            for (TailRepair repair : repairs) {
                if (backup) {
                    String suffix =
                            ".pre-repair-"
                                    + System.currentTimeMillis()
                                    + '-'
                                    + UUID.randomUUID().toString().replace("-", "");
                    copyForced(
                            repair.path,
                            repair.path.resolveSibling(repair.path.getFileName() + suffix));
                }
                try (FileChannel channel =
                        FileChannel.open(repair.path, StandardOpenOption.WRITE)) {
                    channel.truncate(repair.validBytes);
                    channel.force(true);
                }
            }
            syncDirectory(root);
            ManifestInspection verified = VersionSet.inspect(root, identity.databaseId());
            if (verified.incompleteTailBytes() != 0)
                throw new IOException("post-repair manifest verification still reports a tail");
            if (verified.version().minimumWalFileNumber() > 0)
                validWalEnd(
                        root.resolve(
                                WalFormatV1.fileName(verified.version().minimumWalFileNumber())),
                        identity.databaseId(),
                        verified.version().minimumWalFileNumber());
        }
        System.out.println("Tail repair applied and post-repair verification succeeded.");
        return 0;
    }

    private static long validWalEnd(Path path, UUID databaseId, long segmentNumber)
            throws IOException {
        byte[] contents = Files.readAllBytes(path);
        if (contents.length < WalFormatV1.HEADER_BLOCK_BYTES)
            throw new IllegalArgumentException("WAL header is incomplete");
        WalSegmentHeader.decode(
                Arrays.copyOf(contents, WalFormatV1.HEADER_BLOCK_BYTES), databaseId, segmentNumber);
        List<byte[]> groups =
                WalFragmentCodec.reassemble(
                        Arrays.copyOfRange(
                                contents, WalFormatV1.HEADER_BLOCK_BYTES, contents.length),
                        WalFormatV1.HEADER_BLOCK_BYTES);
        long validEnd = WalFormatV1.HEADER_BLOCK_BYTES;
        for (byte[] group : groups)
            validEnd = WalFormatV1.estimateEndOffset(validEnd, group.length);
        return validEnd;
    }

    private static int rebuildCurrent(String[] arguments) throws IOException {
        if (arguments.length < 2)
            throw new IllegalArgumentException("rebuild-current requires a database directory");
        boolean confirmed = Arrays.asList(arguments).contains("--yes");
        Path root = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        try (DatabaseLock lock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(lock);
            DatabaseIdentityV1 identity =
                    DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
            List<ManifestInspection> candidates = new ArrayList<>();
            try (var entries = Files.list(root)) {
                for (Path path :
                        entries.filter(
                                        candidate ->
                                                candidate
                                                        .getFileName()
                                                        .toString()
                                                        .matches("MANIFEST-[0-9]{20}\\.aeman"))
                                .toList()) {
                    try {
                        ManifestInspection inspection =
                                VersionSet.inspectManifest(root, identity.databaseId(), path);
                        if (inspection.incompleteTailBytes() == 0) candidates.add(inspection);
                    } catch (IllegalArgumentException | IOException ignored) {
                        // A corrupt or incomplete candidate cannot establish authority.
                    }
                }
            }
            if (candidates.isEmpty())
                throw new IllegalArgumentException("no complete valid manifest candidate exists");
            candidates.sort(
                    Comparator.comparingLong(candidate -> candidate.header().manifestFileNumber()));
            ManifestInspection selected = candidates.get(candidates.size() - 1);
            if (candidates.size() > 1) {
                ManifestInspection previous = candidates.get(candidates.size() - 2);
                if (!equivalentTerminalVersion(previous.version(), selected.version())) {
                    throw new IllegalArgumentException(
                            "multiple incompatible valid manifests make CURRENT authority"
                                    + " ambiguous");
                }
            }
            System.out.println(
                    "Authoritative candidate: "
                            + selected.manifestPath().getFileName()
                            + " (records="
                            + selected.recordCount()
                            + ", sequence="
                            + selected.version().lastAssignedSequence()
                            + ")");
            if (!confirmed) {
                System.out.println("No files changed. Re-run with --yes to publish CURRENT.");
                return 2;
            }
            Path temporary =
                    root.resolve("CURRENT.tmp-" + UUID.randomUUID().toString().replace("-", ""));
            try {
                writeForced(
                        temporary,
                        CurrentFileV1.encode(
                                identity.databaseId(), selected.header().manifestFileNumber()));
                Files.move(
                        temporary,
                        root.resolve("CURRENT"),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
                syncDirectory(root);
            } finally {
                Files.deleteIfExists(temporary);
            }
            VersionSet.inspect(root, identity.databaseId());
        }
        System.out.println("CURRENT rebuilt and verified without modifying the manifest.");
        return 0;
    }

    private static boolean equivalentTerminalVersion(Version left, Version right) {
        if (left.nextFileNumber() != right.nextFileNumber()
                || left.lastAssignedSequence() != right.lastAssignedSequence()
                || left.persistedSequenceWatermark() != right.persistedSequenceWatermark()
                || left.minimumWalFileNumber() != right.minimumWalFileNumber()
                || left.allFiles().size() != right.allFiles().size()) return false;
        for (int index = 0; index < left.allFiles().size(); index++) {
            if (!left.allFiles().get(index).contentEquals(right.allFiles().get(index)))
                return false;
        }
        return true;
    }

    private static int salvage(String[] arguments) throws IOException {
        if (arguments.length < 3)
            throw new IllegalArgumentException(
                    "salvage requires source and destination directories");
        List<String> values = Arrays.asList(arguments);
        SalvageMode mode = SalvageMode.LATEST_STATE;
        int modeIndex = values.indexOf("--mode");
        if (modeIndex >= 0) {
            if (modeIndex + 1 >= values.size())
                throw new IllegalArgumentException("--mode requires a value");
            try {
                mode = SalvageMode.valueOf(values.get(modeIndex + 1).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException(
                        "unknown salvage mode: " + values.get(modeIndex + 1));
            }
        }
        boolean includeUnreferenced = values.contains("--include-unreferenced");
        Path source = PathSecurityValidator.validateRoot(Path.of(arguments[1]), true);
        Path destination = Path.of(arguments[2]).toAbsolutePath().normalize();
        if (Files.exists(destination) || destination.startsWith(source))
            throw new IllegalArgumentException(
                    "salvage destination must be absent and outside source");
        Path parent = destination.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IllegalArgumentException("salvage destination parent does not exist");
        Path temporary =
                parent.resolve(
                        destination.getFileName()
                                + ".tmp-"
                                + UUID.randomUUID().toString().replace("-", ""));
        List<String> skipped = new ArrayList<>();
        int candidateFiles = 0;
        List<SSTableEntry> recovered = new ArrayList<>();
        try (DatabaseLock lock = DatabaseLock.acquire(source)) {
            java.util.Objects.requireNonNull(lock);
            DatabaseIdentityV1 sourceIdentity =
                    DatabaseIdentityV1.decode(Files.readAllBytes(source.resolve("DB-IDENTITY")));
            ManifestInspection manifest =
                    VersionSet.inspectMetadata(source, sourceIdentity.databaseId());
            java.util.LinkedHashMap<Path, ManifestFileMetadata> candidates =
                    new java.util.LinkedHashMap<>();
            for (ManifestFileMetadata file : manifest.version().allFiles()) {
                candidates.put(source.resolve(VersionSet.sstableName(file.fileNumber())), file);
            }
            if (includeUnreferenced)
                try (var entries = Files.list(source)) {
                    for (Path path :
                            entries.filter(
                                            candidate ->
                                                    candidate
                                                            .getFileName()
                                                            .toString()
                                                            .matches("SST-[0-9]{20}\\.aess"))
                                    .toList()) {
                        candidates.putIfAbsent(path, null);
                    }
                }
            candidateFiles = candidates.size();
            java.util.HashMap<String, SSTableEntry> exact = new java.util.HashMap<>();
            for (var candidate : candidates.entrySet()) {
                try (SSTableReader reader =
                        candidate.getValue() == null
                                ? SSTableReader.open(
                                        candidate.getKey(), sourceIdentity.databaseId())
                                : SSTableReader.open(
                                        candidate.getKey(),
                                        sourceIdentity.databaseId(),
                                        candidate.getValue())) {
                    for (SSTableEntry entry : reader.entries()) {
                        String identity = HexFormat.of().formatHex(entry.key().encode());
                        SSTableEntry previous = exact.putIfAbsent(identity, entry);
                        if (previous != null && !Arrays.equals(previous.value(), entry.value())) {
                            throw new SalvageConflictException(
                                    "conflicting duplicate internal key in salvage source: "
                                            + identity);
                        }
                    }
                } catch (SalvageConflictException conflict) {
                    throw conflict;
                } catch (SSTableCorruptionException
                        | IllegalArgumentException
                        | IOException failure) {
                    skipped.add(candidate.getKey().getFileName() + ": " + failure.getMessage());
                }
            }
            List<Path> walCandidates;
            try (var entries = Files.list(source)) {
                walCandidates =
                        entries.filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .matches("WAL-[0-9]{20}\\.aewal"))
                                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                                .toList();
            }
            candidateFiles += walCandidates.size();
            for (Path walPath : walCandidates) {
                recoverWalForSalvage(walPath, sourceIdentity.databaseId(), exact, skipped);
            }
            recovered.addAll(exact.values());
            recovered.sort(Comparator.comparing(SSTableEntry::key));
            if (mode == SalvageMode.LATEST_STATE) recovered = latestState(recovered);
            publishSalvage(temporary, destination, recovered, mode, candidateFiles, skipped);
        } catch (Throwable failure) {
            cleanupTemporaryDirectory(temporary, failure);
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("salvage failed", failure);
        }
        System.out.println(
                "Salvage published to "
                        + destination
                        + ": recovered "
                        + recovered.size()
                        + " internal records; skipped "
                        + skipped.size()
                        + " files.");
        if (!skipped.isEmpty())
            System.out.println("WARNING: salvage may contain data loss; see SALVAGE-REPORT.json");
        return skipped.isEmpty() ? 0 : 2;
    }

    private static List<SSTableEntry> latestState(List<SSTableEntry> sorted) {
        List<SSTableEntry> result = new ArrayList<>();
        byte[] previous = null;
        for (SSTableEntry entry : sorted) {
            byte[] key = entry.key().userKey();
            if (previous == null || !Arrays.equals(previous, key)) {
                result.add(entry);
                previous = key;
            }
        }
        return List.copyOf(result);
    }

    private static void recoverWalForSalvage(
            Path path,
            UUID databaseId,
            java.util.Map<String, SSTableEntry> exact,
            List<String> skipped)
            throws IOException {
        byte[] contents = Files.readAllBytes(path);
        String file = path.getFileName().toString();
        long segment = Long.parseLong(file.substring(4, 24));
        if (contents.length < WalFormatV1.HEADER_BLOCK_BYTES) {
            skipped.add(file + " bytes [0," + contents.length + "): incomplete segment header");
            return;
        }
        try {
            WalSegmentHeader.decode(
                    Arrays.copyOf(contents, WalFormatV1.HEADER_BLOCK_BYTES), databaseId, segment);
        } catch (RuntimeException failure) {
            skipped.add(file + " bytes [0," + contents.length + "): " + failure.getMessage());
            return;
        }
        WalFragmentCodec.PrefixRecovery prefix =
                WalFragmentCodec.recoverPrefix(
                        Arrays.copyOfRange(
                                contents, WalFormatV1.HEADER_BLOCK_BYTES, contents.length),
                        WalFormatV1.HEADER_BLOCK_BYTES);
        long groupOffset = WalFormatV1.HEADER_BLOCK_BYTES;
        for (byte[] logical : prefix.records()) {
            long groupEnd = WalFormatV1.estimateEndOffset(groupOffset, logical.length);
            try {
                for (SSTableEntry entry : decodeWalGroup(logical)) mergeSalvageEntry(exact, entry);
            } catch (SalvageConflictException conflict) {
                throw conflict;
            } catch (WalCorruptionException | IllegalArgumentException failure) {
                skipped.add(
                        file
                                + " bytes ["
                                + groupOffset
                                + ','
                                + contents.length
                                + "): "
                                + failure.getMessage());
                return;
            }
            groupOffset = groupEnd;
        }
        if (prefix.hasIssue()) {
            skipped.add(
                    file
                            + " bytes ["
                            + prefix.validEndOffset()
                            + ','
                            + contents.length
                            + "): "
                            + prefix.issue());
        }
    }

    private static List<SSTableEntry> decodeWalGroup(byte[] encoded) {
        WalLogicalGroupCodec.DecodedGroup group = WalLogicalGroupCodec.decode(encoded);
        List<SSTableEntry> result = new ArrayList<>(group.mutations().size());
        long sequence = group.firstSequence();
        for (WalLogicalGroupCodec.Mutation mutation : group.mutations()) {
            result.add(
                    new SSTableEntry(
                            new InternalKey(
                                    mutation.key(), sequence, mutation.delete() ? (byte) 2 : 1),
                            mutation.value()));
            sequence++;
        }
        return List.copyOf(result);
    }

    private static void mergeSalvageEntry(
            java.util.Map<String, SSTableEntry> exact, SSTableEntry entry) {
        String identity = HexFormat.of().formatHex(entry.key().encode());
        SSTableEntry previous = exact.putIfAbsent(identity, entry);
        if (previous != null && !Arrays.equals(previous.value(), entry.value())) {
            throw new SalvageConflictException(
                    "conflicting duplicate internal key in salvage source: " + identity);
        }
    }

    private static void publishSalvage(
            Path temporary,
            Path destination,
            List<SSTableEntry> entries,
            SalvageMode mode,
            int candidateFiles,
            List<String> skipped)
            throws IOException {
        Files.createDirectory(temporary);
        UUID databaseId = UUID.randomUUID();
        long now = System.currentTimeMillis();
        writeForced(
                temporary.resolve("DB-IDENTITY"),
                new DatabaseIdentityV1(databaseId, now, 0, 2).encode());
        writeForced(
                temporary.resolve("FORMAT-OPTIONS"), new FormatOptionsV1(databaseId, now).encode());
        writeWalHeader(
                temporary.resolve(WalFormatV1.fileName(1)),
                databaseId,
                Math.addExact(
                        entries.stream().mapToLong(entry -> entry.key().sequence()).max().orElse(0),
                        1),
                now);
        List<ManifestFileMetadata> additions = new ArrayList<>();
        long lastSequence = 0;
        if (!entries.isEmpty()) {
            Path tablePath = temporary.resolve(VersionSet.sstableName(2));
            SSTableBuilder builder = new SSTableBuilder(tablePath, 2, databaseId, now);
            for (SSTableEntry entry : entries) {
                builder.add(entry.key(), entry.value());
                lastSequence = Math.max(lastSequence, entry.key().sequence());
            }
            TableFileMetadata table = builder.finish();
            additions.add(
                    new ManifestFileMetadata(
                            2,
                            0,
                            table.fileSize(),
                            table.entryCount(),
                            table.smallestSequence(),
                            table.largestSequence(),
                            table.smallestInternalKey(),
                            table.largestInternalKey()));
        }
        ManifestEdit snapshot =
                new ManifestEdit(
                        ManifestEdit.Kind.SNAPSHOT,
                        1,
                        entries.isEmpty() ? 2 : 3,
                        lastSequence,
                        lastSequence,
                        1,
                        additions,
                        List.of());
        try (VersionSet versions = VersionSet.create(temporary, databaseId, 1, snapshot, now)) {
            versions.current();
        }
        String report =
                "{\"mode\":\""
                        + mode
                        + "\",\"candidateFiles\":"
                        + candidateFiles
                        + ",\"recoveredInternalRecords\":"
                        + entries.size()
                        + ",\"skippedFiles\":"
                        + skipped.size()
                        + ",\"skipped\":["
                        + skipped.stream()
                                .map(AetherCli::jsonString)
                                .collect(java.util.stream.Collectors.joining(","))
                        + "]"
                        + ",\"possibleDataLoss\":"
                        + !skipped.isEmpty()
                        + "}\n";
        writeForced(
                temporary.resolve("SALVAGE-REPORT.json"),
                report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        syncDirectory(temporary);
        VersionSet.inspect(temporary, databaseId);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
        syncDirectory(destination.getParent());
    }

    private static void writeWalHeader(
            Path path, UUID databaseId, long firstSequence, long creationEpochMillis)
            throws IOException {
        io.aetherdb.wal.format.WalSegmentHeader header =
                new io.aetherdb.wal.format.WalSegmentHeader(
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

    private static String settingsJson(Map<String, String> settings) {
        return settings.entrySet().stream()
                .map(entry -> jsonString(entry.getKey()) + ":" + jsonString(entry.getValue()))
                .collect(java.util.stream.Collectors.joining(","));
    }

    private static void verifyCheckpointDirectory(Path checkpoint) throws IOException {
        CheckpointMetadataV1 metadata =
                CheckpointMetadataV1.decode(
                        Files.readAllBytes(checkpoint.resolve("CHECKPOINT-METADATA")));
        DatabaseIdentityV1 identity =
                DatabaseIdentityV1.decode(Files.readAllBytes(checkpoint.resolve("DB-IDENTITY")));
        FormatOptionsV1 options =
                FormatOptionsV1.decode(Files.readAllBytes(checkpoint.resolve("FORMAT-OPTIONS")));
        if (!identity.databaseId().equals(metadata.databaseId())
                || !identity.databaseId().equals(options.databaseId())
                || !Arrays.equals(
                        metadata.compatibilityFingerprint(), options.compatibilityFingerprint())) {
            throw new IllegalArgumentException("checkpoint identity or fingerprint mismatch");
        }
        ManifestInspection inspection = VersionSet.inspect(checkpoint, identity.databaseId());
        Version version = inspection.version();
        if (inspection.incompleteTailBytes() != 0
                || version.minimumWalFileNumber() != 0
                || version.lastAssignedSequence() != metadata.checkpointSequence()
                || version.persistedSequenceWatermark() != metadata.checkpointSequence()
                || version.allFiles().size() != metadata.sstableFileCount()
                || version.allFiles().stream().mapToLong(ManifestFileMetadata::fileSize).sum()
                        != metadata.totalSstableBytes()
                || version.allFiles().stream()
                        .anyMatch(file -> file.largestSequence() > metadata.checkpointSequence())) {
            throw new IllegalArgumentException(
                    "checkpoint inventory or sequence boundary mismatch");
        }
        try (var entries = Files.list(checkpoint)) {
            if (entries.anyMatch(
                    path -> path.getFileName().toString().matches("WAL-[0-9]{20}\\.aewal"))) {
                throw new IllegalArgumentException("published checkpoint contains a WAL");
            }
        }
    }

    private static void copyForced(Path source, Path destination) throws IOException {
        Files.copy(source, destination);
        try (FileChannel channel = FileChannel.open(destination, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        if (Files.size(source) != Files.size(destination))
            throw new IOException("checkpoint copy size mismatch: " + source.getFileName());
    }

    private static void writeForced(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel =
                FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer input = ByteBuffer.wrap(bytes);
            while (input.hasRemaining()) channel.write(input);
            channel.force(true);
        }
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void cleanupTemporaryDirectory(Path temporary, Throwable failure) {
        if (!Files.exists(temporary)) return;
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  aether version");
        System.out.println("  aether inspect <database-directory> [--json] [--unsafe-no-lock]");
        System.out.println(
                "  aether verify <database-directory> [--level METADATA|CHECKSUMS|FULL] [--json]"
                        + " [--unsafe-no-lock]");
        System.out.println("  aether checkpoint <source-database> <destination-directory>");
        System.out.println("  aether restore-verify <checkpoint-directory>");
        System.out.println(
                "  aether backup-create <checkpoint-directory> <backup-archive> [--json]"
                        + " [--encrypt-key-epoch N --encrypt-key-hex HEX]");
        System.out.println(
                "  aether backup-restore-preflight <backup-archive> <target-directory>"
                        + " [--mode MODE] [--json]");
        System.out.println(
                "  aether backup-restore <backup-archive> <target-directory>"
                        + " [--mode MODE] [--json] [--key-epoch N --key-hex HEX]");
        System.out.println(
                "  aether backup-restore-drill <backup-archive> <target-directory>"
                        + " [--mode MODE] [--json] [--key-epoch N --key-hex HEX]");
        System.out.println("  aether release-certify <manifest-file> [--json]");
        System.out.println("  aether diagnostics <bundle.zip> [--config properties-file] [--json]");
        System.out.println("  aether command-schema [command]");
        System.out.println("  aether repair-tail <database-directory> [--yes] [--no-backup]");
        System.out.println("  aether rebuild-current <database-directory> [--yes]");
        System.out.println(
                "  aether salvage <source-database> <destination-directory> [--mode"
                        + " LATEST_STATE|PRESERVE_VALID_HISTORY] [--include-unreferenced]");
    }

    private enum VerificationLevel {
        METADATA,
        CHECKSUMS,
        FULL
    }

    private enum SalvageMode {
        LATEST_STATE,
        PRESERVE_VALID_HISTORY
    }

    private record BackupPreflightArguments(
            Path archive,
            Path target,
            BackupRestoreMode mode,
            boolean allowNonEmptyTarget,
            int supportedFormatVersion,
            Set<Long> availableKeyEpochs,
            Map<Long, byte[]> keys,
            UUID existingDatabaseId,
            UUID existingClusterId,
            BackupAdmissionOptions admissionOptions,
            boolean json) {
        static BackupPreflightArguments parse(String[] arguments) {
            if (arguments.length < 3)
                throw new IllegalArgumentException(
                        "backup-restore-preflight requires archive and target directories");
            List<String> values = Arrays.asList(arguments);
            BackupRestoreMode mode =
                    BackupRestoreMode.SINGLE_NODE_NEW_DATABASE_ID;
            int modeIndex = values.indexOf("--mode");
            if (modeIndex >= 0) {
                mode =
                        BackupRestoreMode.valueOf(
                                requiredValue(values, modeIndex, "--mode")
                                        .toUpperCase(Locale.ROOT));
            }
            int supportedFormatVersion = 1;
            int formatIndex = values.indexOf("--supported-format-version");
            if (formatIndex >= 0) {
                supportedFormatVersion =
                        Integer.parseInt(
                                requiredValue(
                                        values,
                                        formatIndex,
                                        "--supported-format-version"));
            }
            Set<Long> keyEpochs = new HashSet<>();
            Map<Long, byte[]> keys = new java.util.HashMap<>();
            for (int index = 3; index < values.size(); index++) {
                if (values.get(index).equals("--available-key-epoch")) {
                    keyEpochs.add(
                            Long.parseLong(
                                    requiredValue(values, index, "--available-key-epoch")));
                    index++;
                } else if (values.get(index).equals("--key-epoch")) {
                    long epoch = Long.parseLong(requiredValue(values, index, "--key-epoch"));
                    int keyIndex = index + 2;
                    if (keyIndex >= values.size() || !values.get(keyIndex).equals("--key-hex"))
                        throw new IllegalArgumentException("--key-epoch requires following --key-hex");
                    byte[] key = parseHexKey(requiredValue(values, keyIndex, "--key-hex"));
                    keys.put(epoch, key);
                    keyEpochs.add(epoch);
                    index = keyIndex + 1;
                }
            }
            UUID databaseId = null, clusterId = null;
            int databaseIndex = values.indexOf("--existing-database-id");
            if (databaseIndex >= 0)
                databaseId = UUID.fromString(requiredValue(values, databaseIndex, "--existing-database-id"));
            int clusterIndex = values.indexOf("--existing-cluster-id");
            if (clusterIndex >= 0)
                clusterId = UUID.fromString(requiredValue(values, clusterIndex, "--existing-cluster-id"));
            return new BackupPreflightArguments(
                    Path.of(arguments[1]).toAbsolutePath().normalize(),
                    Path.of(arguments[2]).toAbsolutePath().normalize(),
                    mode,
                    values.contains("--allow-non-empty-target"),
                    supportedFormatVersion,
                    keyEpochs,
                    keys,
                    databaseId,
                    clusterId,
                    BackupAdmissionOptions.parse(values),
                    values.contains("--json"));
        }

        BackupRestorePreflightOptions options() {
            return new BackupRestorePreflightOptions(
                    mode,
                    allowNonEmptyTarget,
                    supportedFormatVersion,
                    availableKeyEpochs,
                    existingDatabaseId,
                    existingClusterId);
        }
    }

    record BackupAdmissionOptions(long hardBytes, long hardObjects) {
        private static final long DEFAULT_HARD_BYTES = Long.MAX_VALUE / 4;
        private static final long DEFAULT_HARD_OBJECTS = Integer.MAX_VALUE;

        BackupAdmissionOptions {
            if (hardBytes <= 0 || hardObjects <= 0)
                throw new IllegalArgumentException("backup admission limits must be positive");
        }

        static BackupAdmissionOptions parse(List<String> values) {
            long hardBytes = DEFAULT_HARD_BYTES;
            int bytesIndex = values.indexOf("--backup-hard-bytes");
            if (bytesIndex >= 0)
                hardBytes = Long.parseLong(requiredValue(values, bytesIndex, "--backup-hard-bytes"));
            long hardObjects = DEFAULT_HARD_OBJECTS;
            int objectsIndex = values.indexOf("--backup-hard-objects");
            if (objectsIndex >= 0)
                hardObjects =
                        Long.parseLong(
                                requiredValue(values, objectsIndex, "--backup-hard-objects"));
            return new BackupAdmissionOptions(hardBytes, hardObjects);
        }

        AdmissionPolicy policy() {
            return AdmissionPolicy.of(
                    new ResourceLimit(
                            AdmissionResource.BACKUP_OPERATION_BYTES,
                            hardBytes,
                            hardBytes,
                            hardBytes),
                    new ResourceLimit(
                            AdmissionResource.BACKUP_OBJECT_COUNT,
                            hardObjects,
                            hardObjects,
                            hardObjects));
        }
    }

    private record BackupEncryptionKey(long epoch, byte[] key) {
        private BackupEncryptionKey {
            if (epoch <= 0) throw new IllegalArgumentException("backup key epoch must be positive");
            if (key == null || key.length != 32)
                throw new IllegalArgumentException("backup encryption key must be 32 bytes");
            key = key.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        static BackupEncryptionKey create(List<String> values) {
            int epochIndex = values.indexOf("--encrypt-key-epoch");
            int keyIndex = values.indexOf("--encrypt-key-hex");
            if (epochIndex < 0 && keyIndex < 0) return null;
            if (epochIndex < 0 || keyIndex < 0)
                throw new IllegalArgumentException(
                        "--encrypt-key-epoch and --encrypt-key-hex must be provided together");
            return new BackupEncryptionKey(
                    Long.parseLong(requiredValue(values, epochIndex, "--encrypt-key-epoch")),
                    parseHexKey(requiredValue(values, keyIndex, "--encrypt-key-hex")));
        }
    }

    private record Arguments(
            Path path, boolean json, boolean unsafeNoLock, VerificationLevel level) {
        static Arguments parse(String[] arguments, VerificationLevel defaultLevel) {
            if (arguments.length < 2)
                throw new IllegalArgumentException("database directory is required");
            List<String> values = Arrays.asList(arguments);
            VerificationLevel level = defaultLevel;
            int levelIndex = values.indexOf("--level");
            if (levelIndex >= 0) {
                if (levelIndex + 1 >= values.size())
                    throw new IllegalArgumentException("--level requires a value");
                try {
                    level =
                            VerificationLevel.valueOf(
                                    values.get(levelIndex + 1).toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException failure) {
                    throw new IllegalArgumentException(
                            "unknown verification level: " + values.get(levelIndex + 1));
                }
            }
            return new Arguments(
                    Path.of(arguments[1]),
                    values.contains("--json"),
                    values.contains("--unsafe-no-lock"),
                    level);
        }
    }

    private static String requiredValue(List<String> values, int optionIndex, String option) {
        if (optionIndex + 1 >= values.size() || values.get(optionIndex + 1).startsWith("--"))
            throw new IllegalArgumentException(option + " requires a value");
        return values.get(optionIndex + 1);
    }

    private static Map<String, String> configOverrides(List<String> values) {
        LinkedHashMap<String, String> overrides = new LinkedHashMap<>();
        for (int index = 2; index < values.size(); index++) {
            String value = values.get(index);
            if (value.equals("--json")) continue;
            if (!value.equals("--set"))
                throw new IllegalArgumentException("unknown config-validate option: " + value);
            String override = requiredValue(values, index, "--set");
            int separator = override.indexOf('=');
            if (separator <= 0)
                throw new IllegalArgumentException("--set requires name=value");
            overrides.put(override.substring(0, separator), override.substring(separator + 1));
            index++;
        }
        return Map.copyOf(overrides);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static byte[] parseHexKey(String value) {
        if (value.length() != 64) throw new IllegalArgumentException("backup key must be 32 bytes");
        try {
            return HexFormat.of().parseHex(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("backup key must be hex", failure);
        }
    }

    private record WalInfo(long number, long bytes, long firstSequence, int groups) {}

    private record TailRepair(Path path, long validBytes, long trailingBytes) {}

    record DiagnosticsBundleReport(Path bundle, int entries) {
        DiagnosticsBundleReport {
            java.util.Objects.requireNonNull(bundle, "bundle");
            if (entries <= 0) throw new IllegalArgumentException("diagnostics bundle requires entries");
        }
    }

    record CliCommandSchema(
            String command, String jsonMode, List<Integer> exitCodes, List<String> jsonFields) {
        CliCommandSchema {
            command = requireText(command, "command");
            jsonMode = requireText(jsonMode, "jsonMode");
            exitCodes = List.copyOf(exitCodes);
            jsonFields = List.copyOf(jsonFields);
            if (exitCodes.isEmpty() || jsonFields.isEmpty())
                throw new IllegalArgumentException("command schema requires codes and fields");
        }

        String toJson() {
            return String.format(
                    Locale.ROOT,
                    "{\"command\":\"%s\",\"jsonMode\":\"%s\",\"exitCodes\":[%s],\"jsonFields\":[%s]}",
                    command,
                    jsonMode,
                    exitCodes.stream()
                            .map(String::valueOf)
                            .collect(java.util.stream.Collectors.joining(",")),
                    jsonFields.stream()
                            .map(AetherCli::jsonString)
                            .collect(java.util.stream.Collectors.joining(",")));
        }
    }

    private record BackupRestoreDrillReport(
            boolean passed,
            UUID backupId,
            UUID databaseId,
            BackupRestoreMode restoreMode,
            Path target,
            long objectCount,
            long totalBytes,
            int restoredObjects,
            boolean verified,
            List<String> failures,
            long elapsedNanos) {
        private BackupRestoreDrillReport {
            java.util.Objects.requireNonNull(backupId, "backupId");
            java.util.Objects.requireNonNull(databaseId, "databaseId");
            java.util.Objects.requireNonNull(restoreMode, "restoreMode");
            java.util.Objects.requireNonNull(target, "target");
            java.util.Objects.requireNonNull(failures, "failures");
            failures = List.copyOf(failures);
            if (passed != failures.isEmpty())
                throw new IllegalArgumentException("passed flag must match failures");
            if (objectCount < 0 || totalBytes < 0 || restoredObjects < 0 || elapsedNanos < 0)
                throw new IllegalArgumentException("invalid restore drill counters");
        }

        static BackupRestoreDrillReport failed(
                BackupArchiveContents contents,
                BackupPreflightArguments arguments,
                BackupRestorePreflightReport preflight,
                String failure,
                long startedNanos) {
            List<String> failures = new ArrayList<>();
            failures.add(failure);
            failures.addAll(preflight.failures());
            return new BackupRestoreDrillReport(
                    false,
                    contents.manifest().backupId(),
                    contents.manifest().databaseId(),
                    arguments.mode(),
                    arguments.target(),
                    preflight.objectCount(),
                    preflight.totalBytes(),
                    0,
                    false,
                    failures,
                    System.nanoTime() - startedNanos);
        }

        static BackupRestoreDrillReport completed(
                BackupArchiveContents contents,
                BackupPreflightArguments arguments,
                BackupRestoreResult result,
                List<String> failures,
                long startedNanos) {
            return new BackupRestoreDrillReport(
                    failures.isEmpty(),
                    contents.manifest().backupId(),
                    contents.manifest().databaseId(),
                    arguments.mode(),
                    arguments.target(),
                    result.preflightReport().objectCount(),
                    result.preflightReport().totalBytes(),
                    result.restoredObjects().size(),
                    failures.isEmpty(),
                    failures,
                    System.nanoTime() - startedNanos);
        }
    }

    private record DatabaseReport(
            DatabaseIdentityV1 identity,
            FormatOptionsV1 options,
            ManifestInspection manifest,
            List<WalInfo> wals,
            int[] levelFiles,
            long[] levelBytes,
            long totalTableBytes,
            long oldestTable,
            long newestTable,
            List<String> warnings,
            VerificationLevel verificationLevel,
            long elapsedNanos) {}

    private static final class LockUnavailableException extends IOException {
        private static final long serialVersionUID = 1L;

        private LockUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private static final class SalvageConflictException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        private SalvageConflictException(String message) {
            super(message);
        }
    }
}
