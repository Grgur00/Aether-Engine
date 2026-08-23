package io.aetherdb.release;

import java.util.List;
import java.util.Objects;

/**
 * Evaluated go/no-go report for a release certification manifest.
 *
 * @param productionReady true only when Chapter 38 gates pass
 * @param failures blocking failure messages
 * @param warnings non-blocking warnings
 * @param greenRows number of green evidence rows
 * @param notApplicableRows number of approved non-applicable rows
 * @param redRows number of red evidence rows
 * @param missingRows number of required evidence rows not present
 */
public record ReleaseCertificationReport(
        boolean productionReady,
        List<String> failures,
        List<String> warnings,
        int greenRows,
        int notApplicableRows,
        int redRows,
        int missingRows) {
    public ReleaseCertificationReport {
        failures = List.copyOf(Objects.requireNonNull(failures, "failures"));
        warnings = List.copyOf(Objects.requireNonNull(warnings, "warnings"));
        if (productionReady != failures.isEmpty())
            throw new IllegalArgumentException("productionReady flag must match failures");
        if (greenRows < 0 || notApplicableRows < 0 || redRows < 0 || missingRows < 0)
            throw new IllegalArgumentException("invalid report counters");
    }
}
