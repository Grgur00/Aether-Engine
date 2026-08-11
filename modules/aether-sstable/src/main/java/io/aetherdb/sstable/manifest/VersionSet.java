package io.aetherdb.sstable.manifest;

import io.aetherdb.io.PathSecurityValidator;
import io.aetherdb.sstable.SSTableReader;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/** Owns the current immutable {@link Version} and its durable append-only manifest. */
public final class VersionSet implements AutoCloseable {
    private static final String CURRENT = "CURRENT";
    private final Path root;
    private final UUID databaseId;
    private final Path manifestPath;
    private final FileChannel writer;
    private Version current;
    private boolean closed;

    private VersionSet(
            Path root, UUID databaseId, Path manifestPath, FileChannel writer, Version current) {
        this.root = root;
        this.databaseId = databaseId;
        this.manifestPath = manifestPath;
        this.writer = writer;
        this.current = current;
    }

    /**
     * Creates and atomically publishes a new manifest containing one complete snapshot.
     *
     * @param root validated database directory
     * @param databaseId owning database identity
     * @param manifestGeneration positive manifest file number
     * @param snapshot authoritative first edit
     * @param creationEpochMillis diagnostic creation time
     * @return open version set positioned after the snapshot
     * @throws IOException when durable publication fails
     */
    public static VersionSet create(
            Path root,
            UUID databaseId,
            long manifestGeneration,
            ManifestEdit snapshot,
            long creationEpochMillis)
            throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(databaseId, "databaseId");
        if (snapshot == null
                || snapshot.kind() != ManifestEdit.Kind.SNAPSHOT
                || snapshot.editNumber() != 1
                || manifestGeneration <= 0
                || snapshot.nextFileNumber() <= manifestGeneration) {
            throw new IllegalArgumentException("invalid initial manifest snapshot");
        }
        Path safeRoot = PathSecurityValidator.validateRoot(root, true);
        String name = CurrentFileV1.manifestName(manifestGeneration);
        Path target = PathSecurityValidator.managed(safeRoot, name);
        if (Files.exists(target) || Files.exists(safeRoot.resolve(CURRENT)))
            throw new IOException("manifest or CURRENT already exists");
        Path temporary =
                safeRoot.resolve(name + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        ManifestHeaderV1 header =
                new ManifestHeaderV1(
                        databaseId,
                        manifestGeneration,
                        creationEpochMillis,
                        snapshot.nextFileNumber(),
                        snapshot.lastAssignedSequence());
        byte[] record = ManifestCodecV1.encodeRecord(snapshot);
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(header.encodeRegion()));
                writeFully(channel, ByteBuffer.wrap(record));
                channel.force(true);
            }
            atomicMove(temporary, target);
            syncDirectory(safeRoot);
            verifyInventory(safeRoot, databaseId, snapshot.additions());
            publishCurrent(safeRoot, databaseId, manifestGeneration);
            FileChannel writer =
                    FileChannel.open(target, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            return new VersionSet(
                    safeRoot,
                    databaseId,
                    target,
                    writer,
                    Version.fromSnapshot(snapshot, manifestGeneration));
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /**
     * Recovers CURRENT and every complete manifest record, truncating only an incomplete final
     * record.
     *
     * @param root validated database directory
     * @param databaseId expected database identity
     * @return recovered open version set
     * @throws IOException when files cannot be read or repaired
     */
    public static VersionSet recover(Path root, UUID databaseId) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(databaseId, "databaseId");
        Path safeRoot = PathSecurityValidator.validateRoot(root, true);
        CurrentFileV1.Pointer pointer =
                CurrentFileV1.decode(Files.readAllBytes(safeRoot.resolve(CURRENT)), databaseId);
        Path manifest = PathSecurityValidator.managed(safeRoot, pointer.manifestName());
        byte[] contents = Files.readAllBytes(manifest);
        if (contents.length < ManifestHeaderV1.HEADER_REGION_BYTES)
            throw new ManifestCorruptionException("manifest header is truncated");
        ManifestHeaderV1 header =
                ManifestHeaderV1.decodeRegion(
                        Arrays.copyOf(contents, ManifestHeaderV1.HEADER_REGION_BYTES));
        if (!header.databaseId().equals(databaseId)
                || header.manifestFileNumber() != pointer.generation()) {
            throw new ManifestCorruptionException("manifest identity does not match CURRENT");
        }
        int offset = ManifestHeaderV1.HEADER_REGION_BYTES;
        long expectedRecord = 1;
        Version version = null;
        while (offset < contents.length) {
            int remaining = contents.length - offset;
            if (remaining < ManifestCodecV1.RECORD_HEADER_BYTES) break;
            byte[] physicalHeader =
                    Arrays.copyOfRange(
                            contents, offset, offset + ManifestCodecV1.RECORD_HEADER_BYTES);
            int physicalBytes = ManifestCodecV1.physicalRecordBytes(physicalHeader);
            if (physicalBytes > remaining) break;
            ManifestEdit edit =
                    ManifestCodecV1.decodeRecord(
                            Arrays.copyOfRange(contents, offset, offset + physicalBytes));
            if (edit.editNumber() != expectedRecord)
                throw new ManifestCorruptionException("manifest record numbers are not contiguous");
            if (version == null) {
                if (edit.kind() != ManifestEdit.Kind.SNAPSHOT)
                    throw new ManifestCorruptionException(
                            "first manifest record is not a snapshot");
                version = Version.fromSnapshot(edit, pointer.generation());
                if (header.initialNextFileNumber() != edit.nextFileNumber()
                        || header.initialLastSequence() != edit.lastAssignedSequence()) {
                    throw new ManifestCorruptionException(
                            "manifest header diagnostics disagree with snapshot");
                }
            } else {
                try {
                    version = version.apply(edit);
                } catch (IllegalArgumentException failure) {
                    throw new ManifestCorruptionException(
                            "invalid manifest version transition", failure);
                }
            }
            expectedRecord++;
            offset += physicalBytes;
        }
        if (version == null)
            throw new ManifestCorruptionException("manifest contains no complete snapshot");
        if (offset != contents.length) {
            try (FileChannel repair = FileChannel.open(manifest, StandardOpenOption.WRITE)) {
                repair.truncate(offset);
                repair.force(true);
            }
            syncDirectory(safeRoot);
        }
        verifyInventory(safeRoot, databaseId, version.allFiles());
        cleanupObsoleteTables(safeRoot, version);
        FileChannel writer =
                FileChannel.open(manifest, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        return new VersionSet(safeRoot, databaseId, manifest, writer, version);
    }

    /**
     * Reconstructs and verifies the current Version without truncating or deleting any file.
     *
     * @param root validated database directory
     * @param databaseId expected database identity
     * @return complete-record accounting and terminal version
     * @throws IOException when required files cannot be read
     */
    public static ManifestInspection inspect(Path root, UUID databaseId) throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(databaseId, "databaseId");
        Path safeRoot = PathSecurityValidator.validateRoot(root, true);
        CurrentFileV1.Pointer pointer =
                CurrentFileV1.decode(Files.readAllBytes(safeRoot.resolve(CURRENT)), databaseId);
        Path manifest = PathSecurityValidator.managed(safeRoot, pointer.manifestName());
        return inspectResolved(safeRoot, databaseId, manifest, pointer.generation(), true);
    }

    /**
     * Reconstructs current manifest metadata without opening referenced SSTables. Intended for
     * explicit forensic salvage where damaged tables are reported individually.
     *
     * @param root validated database directory
     * @param databaseId expected database identity
     * @return terminal metadata even when an individual table is unreadable
     * @throws IOException when CURRENT or the manifest cannot be read
     */
    public static ManifestInspection inspectMetadata(Path root, UUID databaseId)
            throws IOException {
        Path safeRoot = PathSecurityValidator.validateRoot(root, true);
        CurrentFileV1.Pointer pointer =
                CurrentFileV1.decode(Files.readAllBytes(safeRoot.resolve(CURRENT)), databaseId);
        Path manifest = PathSecurityValidator.managed(safeRoot, pointer.manifestName());
        return inspectResolved(safeRoot, databaseId, manifest, pointer.generation(), false);
    }

    /**
     * Inspects one canonical manifest candidate without consulting or changing CURRENT.
     *
     * @param root validated database directory
     * @param databaseId expected database identity
     * @param manifest candidate manifest path beneath the database root
     * @return complete-record accounting and terminal version
     * @throws IOException when the candidate cannot be read
     */
    public static ManifestInspection inspectManifest(Path root, UUID databaseId, Path manifest)
            throws IOException {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(databaseId, "databaseId");
        Objects.requireNonNull(manifest, "manifest");
        Path safeRoot = PathSecurityValidator.validateRoot(root, true);
        String name = manifest.getFileName().toString();
        if (!name.matches("MANIFEST-[0-9]{20}\\.aeman"))
            throw new IllegalArgumentException("noncanonical manifest candidate name");
        Path candidate = PathSecurityValidator.managed(safeRoot, name);
        if (!candidate.equals(manifest.toAbsolutePath().normalize()))
            throw new IllegalArgumentException("manifest candidate is outside database root");
        long generation = Long.parseLong(name.substring(9, 29));
        return inspectResolved(safeRoot, databaseId, candidate, generation, true);
    }

    private static ManifestInspection inspectResolved(
            Path safeRoot,
            UUID databaseId,
            Path manifest,
            long expectedGeneration,
            boolean verifyTables)
            throws IOException {
        byte[] contents = Files.readAllBytes(manifest);
        if (contents.length < ManifestHeaderV1.HEADER_REGION_BYTES)
            throw new ManifestCorruptionException("manifest header is truncated");
        ManifestHeaderV1 header =
                ManifestHeaderV1.decodeRegion(
                        Arrays.copyOf(contents, ManifestHeaderV1.HEADER_REGION_BYTES));
        if (!header.databaseId().equals(databaseId)
                || header.manifestFileNumber() != expectedGeneration) {
            throw new ManifestCorruptionException("manifest identity does not match CURRENT");
        }
        int offset = ManifestHeaderV1.HEADER_REGION_BYTES;
        long expectedRecord = 1;
        Version version = null;
        while (offset < contents.length) {
            int remaining = contents.length - offset;
            if (remaining < ManifestCodecV1.RECORD_HEADER_BYTES) break;
            int physicalBytes =
                    ManifestCodecV1.physicalRecordBytes(
                            Arrays.copyOfRange(
                                    contents,
                                    offset,
                                    offset + ManifestCodecV1.RECORD_HEADER_BYTES));
            if (physicalBytes > remaining) break;
            ManifestEdit edit =
                    ManifestCodecV1.decodeRecord(
                            Arrays.copyOfRange(contents, offset, offset + physicalBytes));
            if (edit.editNumber() != expectedRecord)
                throw new ManifestCorruptionException("manifest record numbers are not contiguous");
            if (version == null) {
                if (edit.kind() != ManifestEdit.Kind.SNAPSHOT)
                    throw new ManifestCorruptionException(
                            "first manifest record is not a snapshot");
                version = Version.fromSnapshot(edit, expectedGeneration);
                if (header.initialNextFileNumber() != edit.nextFileNumber()
                        || header.initialLastSequence() != edit.lastAssignedSequence()) {
                    throw new ManifestCorruptionException(
                            "manifest header diagnostics disagree with snapshot");
                }
            } else {
                try {
                    version = version.apply(edit);
                } catch (IllegalArgumentException failure) {
                    throw new ManifestCorruptionException(
                            "invalid manifest version transition", failure);
                }
            }
            expectedRecord++;
            offset += physicalBytes;
        }
        if (version == null)
            throw new ManifestCorruptionException("manifest contains no complete snapshot");
        if (verifyTables) verifyInventory(safeRoot, databaseId, version.allFiles());
        return new ManifestInspection(
                manifest,
                header,
                version,
                expectedRecord - 1,
                contents.length,
                contents.length - (long) offset);
    }

    /**
     * Forces a contiguous delta before publishing the resulting in-memory version.
     *
     * @param delta next manifest edit
     * @return newly published version
     * @throws IOException when append or force fails
     */
    public synchronized Version logAndApply(ManifestEdit delta) throws IOException {
        ensureOpen();
        Version candidate = current.apply(delta);
        verifyInventory(root, databaseId, delta.additions());
        byte[] record = ManifestCodecV1.encodeRecord(delta);
        writeFully(writer, ByteBuffer.wrap(record));
        writer.force(true);
        current = candidate;
        return candidate;
    }

    /**
     * Returns the current immutable version.
     *
     * @return most recently forced and published version
     */
    public synchronized Version current() {
        ensureOpen();
        return current;
    }

    /**
     * Returns the owning database identity.
     *
     * @return immutable database UUID
     */
    public UUID databaseId() {
        return databaseId;
    }

    /**
     * Returns the active manifest path.
     *
     * @return canonical manifest path
     */
    public Path manifestPath() {
        return manifestPath;
    }

    /** Forces and closes the manifest writer. */
    @Override
    public synchronized void close() throws IOException {
        if (closed) return;
        closed = true;
        writer.force(true);
        writer.close();
    }

    /**
     * Returns the canonical SSTable filename for a durable file number.
     *
     * @param fileNumber positive durable file number
     * @return canonical zero-padded filename
     */
    public static String sstableName(long fileNumber) {
        if (fileNumber <= 0) throw new IllegalArgumentException("file number must be positive");
        return String.format(Locale.ROOT, "SST-%020d.aess", fileNumber);
    }

    private static void verifyInventory(
            Path root, UUID databaseId, java.util.List<ManifestFileMetadata> files)
            throws IOException {
        for (ManifestFileMetadata file : files) {
            Path path = PathSecurityValidator.managed(root, sstableName(file.fileNumber()));
            if (!Files.isRegularFile(path)
                    || Files.isSymbolicLink(path)
                    || Files.size(path) != file.fileSize()) {
                throw new IOException(
                        "referenced SSTable is missing, unsafe, or has the wrong size: "
                                + path.getFileName());
            }
            try (SSTableReader verified = SSTableReader.open(path, databaseId, file)) {
                verified.metadata();
            }
        }
    }

    private static void cleanupObsoleteTables(Path root, Version version) throws IOException {
        java.util.Set<String> live =
                version.allFiles().stream()
                        .map(file -> sstableName(file.fileNumber()))
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        boolean deleted = false;
        try (var entries = Files.list(root)) {
            for (Path entry : entries.toList()) {
                String name = entry.getFileName().toString();
                if (name.matches("SST-[0-9]{20}\\.aess") && !live.contains(name)) {
                    if (Files.isSymbolicLink(entry) || !Files.isRegularFile(entry)) {
                        throw new IOException("unsafe obsolete SSTable path: " + name);
                    }
                    Files.delete(entry);
                    deleted = true;
                }
            }
        }
        if (deleted) syncDirectory(root);
    }

    private static void publishCurrent(Path root, UUID databaseId, long generation)
            throws IOException {
        Path temporary =
                root.resolve("CURRENT.tmp-" + UUID.randomUUID().toString().replace("-", ""));
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(CurrentFileV1.encode(databaseId, generation)));
                channel.force(true);
            }
            atomicMove(temporary, root.resolve(CURRENT));
            syncDirectory(root);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void atomicMove(Path source, Path target) throws IOException {
        Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) channel.write(bytes);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("version set is closed");
    }
}
