package io.aetherdb.io;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Result of writing verified backup archive objects to a restore target.
 *
 * @param targetDirectory restore target directory
 * @param preflightReport preflight report that allowed the write
 * @param restoredObjects relative object paths written
 */
public record BackupRestoreResult(
        Path targetDirectory,
        BackupRestorePreflightReport preflightReport,
        List<String> restoredObjects) {
    public BackupRestoreResult {
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Objects.requireNonNull(preflightReport, "preflightReport");
        restoredObjects = List.copyOf(restoredObjects);
        if (!preflightReport.passed()) throw new IllegalArgumentException("preflight did not pass");
    }
}
