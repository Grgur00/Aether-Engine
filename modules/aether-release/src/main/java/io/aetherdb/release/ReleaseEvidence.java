package io.aetherdb.release;

import java.net.URI;
import java.util.List;
import java.util.Objects;

/**
 * Evidence for one Chapter 38 certification area.
 *
 * @param area certification area
 * @param status evidence status
 * @param summary concise evidence summary
 * @param artifactUris report, CI, benchmark, SBOM, or audit artifact locations
 * @param nonApplicabilityRationale required when status is NOT_APPLICABLE
 */
public record ReleaseEvidence(
        CertificationArea area,
        EvidenceStatus status,
        String summary,
        List<URI> artifactUris,
        String nonApplicabilityRationale) {
    public ReleaseEvidence {
        Objects.requireNonNull(area, "area");
        Objects.requireNonNull(status, "status");
        summary = requireText(summary, "summary");
        artifactUris = List.copyOf(Objects.requireNonNull(artifactUris, "artifactUris"));
        nonApplicabilityRationale =
                nonApplicabilityRationale == null ? "" : nonApplicabilityRationale.trim();
        if (status == EvidenceStatus.GREEN && artifactUris.isEmpty())
            throw new IllegalArgumentException("green evidence requires at least one artifact");
        if (status == EvidenceStatus.NOT_APPLICABLE && nonApplicabilityRationale.isBlank())
            throw new IllegalArgumentException("not-applicable evidence requires rationale");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
