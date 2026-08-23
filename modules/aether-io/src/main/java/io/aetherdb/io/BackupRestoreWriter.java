package io.aetherdb.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Writes verified portable backup archive objects to a restore target after preflight passes. */
public final class BackupRestoreWriter {
    private BackupRestoreWriter() {}

    /**
     * Runs preflight and writes every manifest object to the target directory.
     *
     * @param contents verified archive contents
     * @param targetDirectory restore target
     * @param options restore preflight options
     * @return restore write result
     */
    public static BackupRestoreResult restore(
            BackupArchiveContents contents,
            Path targetDirectory,
            BackupRestorePreflightOptions options)
            throws IOException {
        return restore(contents, targetDirectory, options, false);
    }

    /**
     * Restores checkpoint backup objects into an Aether checkpoint/database root layout.
     *
     * @param contents verified archive contents using the {@code objects/} archive namespace
     * @param targetDirectory restore target
     * @param options restore preflight options
     * @return restore write result
     */
    public static BackupRestoreResult restoreCheckpointLayout(
            BackupArchiveContents contents,
            Path targetDirectory,
            BackupRestorePreflightOptions options)
            throws IOException {
        return restore(contents, targetDirectory, options, true);
    }

    private static BackupRestoreResult restore(
            BackupArchiveContents contents,
            Path targetDirectory,
            BackupRestorePreflightOptions options,
            boolean checkpointLayout)
            throws IOException {
        Objects.requireNonNull(contents, "contents");
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Objects.requireNonNull(options, "options");
        Path target = targetDirectory.toAbsolutePath().normalize();
        BackupRestorePreflightReport report =
                BackupRestorePreflight.check(contents, target, options);
        if (!report.passed())
            throw new IOException("backup restore preflight failed: " + String.join("; ", report.failures()));
        Files.createDirectories(target);
        List<String> restored = new ArrayList<>();
        try {
            for (BackupManifestObject object : contents.manifest().objects()) {
                String restoredPath =
                        checkpointLayout ? checkpointRestorePath(object) : object.path();
                Path destination = resolveObject(target, restoredPath);
                Path parent = destination.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(
                        destination,
                        contents.objectBytes(object.path()),
                        StandardOpenOption.CREATE_NEW,
                        StandardOpenOption.WRITE);
                restored.add(restoredPath);
            }
        } catch (Throwable failure) {
            cleanupRestored(target, restored, failure);
            if (failure instanceof IOException exception) throw exception;
            if (failure instanceof RuntimeException exception) throw exception;
            throw new IOException("backup restore failed", failure);
        }
        return new BackupRestoreResult(target, report, restored);
    }

    private static String checkpointRestorePath(BackupManifestObject object) throws IOException {
        if (!object.path().startsWith("objects/"))
            throw new IOException("checkpoint backup object is outside objects namespace");
        String name = object.path().substring("objects/".length());
        if (name.isBlank() || name.contains("/") || name.contains("\\") || name.equals(".") || name.equals(".."))
            throw new IOException("invalid checkpoint backup object path");
        if (switch (object.kind()) {
            case DATABASE_IDENTITY -> name.equals("DB-IDENTITY");
            case FORMAT_OPTIONS -> name.equals("FORMAT-OPTIONS");
            case CHECKPOINT_METADATA -> name.equals("CHECKPOINT-METADATA");
            case CURRENT -> name.equals("CURRENT");
            case MANIFEST -> name.startsWith("MANIFEST-") && name.endsWith(".aeman");
            case SSTABLE -> name.startsWith("SST-") && name.endsWith(".aess");
            default -> false;
        }) return name;
        throw new IOException("unsupported checkpoint backup object: " + object.path());
    }

    private static Path resolveObject(Path target, String path) throws IOException {
        Path destination = target.resolve(path).normalize();
        if (!destination.startsWith(target))
            throw new IOException("backup object path escapes restore target");
        return destination;
    }

    private static void cleanupRestored(Path target, List<String> restored, Throwable failure) {
        for (String path : restored.reversed()) {
            try {
                Files.deleteIfExists(resolveObject(target, path));
            } catch (IOException cleanup) {
                failure.addSuppressed(cleanup);
            }
        }
        try (var paths = Files.walk(target)) {
            for (Path path :
                    paths.sorted(Comparator.reverseOrder())
                            .filter(candidate -> !candidate.equals(target))
                            .toList()) {
                try {
                    if (Files.isDirectory(path)) Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Leave non-empty user directories in place.
                }
            }
        } catch (IOException cleanup) {
            failure.addSuppressed(cleanup);
        }
    }
}
