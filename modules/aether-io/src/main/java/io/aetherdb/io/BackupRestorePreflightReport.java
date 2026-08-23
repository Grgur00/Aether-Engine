package io.aetherdb.io;

import java.util.List;
import java.util.Objects;

/**
 * Result of restore preflight checks.
 *
 * @param passed whether restore may proceed
 * @param failures blocking failures
 * @param objectCount verified backup object count
 * @param totalBytes verified backup object bytes
 */
public record BackupRestorePreflightReport(
        boolean passed, List<String> failures, long objectCount, long totalBytes) {
    public BackupRestorePreflightReport {
        Objects.requireNonNull(failures, "failures");
        failures = List.copyOf(failures);
        if (passed != failures.isEmpty())
            throw new IllegalArgumentException("passed flag must match failures");
        if (objectCount < 0 || totalBytes < 0)
            throw new IllegalArgumentException("invalid preflight totals");
    }
}
