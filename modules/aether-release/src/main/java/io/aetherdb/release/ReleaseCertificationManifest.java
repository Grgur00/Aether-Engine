package io.aetherdb.release;

import java.net.URI;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable release candidate certification manifest.
 *
 * @param releaseVersion artifact version being certified
 * @param gitCommit exact source commit
 * @param createdAt manifest creation time
 * @param evidence evidence rows keyed by certification area
 * @param blockers release-rule blockers
 * @param artifactUris release-level artifact references
 */
public record ReleaseCertificationManifest(
        String releaseVersion,
        String gitCommit,
        Instant createdAt,
        Map<CertificationArea, ReleaseEvidence> evidence,
        List<ReleaseBlocker> blockers,
        List<URI> artifactUris) {
    public ReleaseCertificationManifest {
        releaseVersion = requireText(releaseVersion, "releaseVersion");
        gitCommit = requireText(gitCommit, "gitCommit");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(evidence, "evidence");
        EnumMap<CertificationArea, ReleaseEvidence> copied =
                new EnumMap<>(CertificationArea.class);
        for (Map.Entry<CertificationArea, ReleaseEvidence> entry : evidence.entrySet()) {
            if (entry.getKey() != entry.getValue().area())
                throw new IllegalArgumentException("evidence key does not match area");
            copied.put(entry.getKey(), entry.getValue());
        }
        evidence = Map.copyOf(copied);
        blockers = List.copyOf(Objects.requireNonNull(blockers, "blockers"));
        artifactUris = List.copyOf(Objects.requireNonNull(artifactUris, "artifactUris"));
    }

    public static ReleaseCertificationManifest of(
            String releaseVersion,
            String gitCommit,
            Instant createdAt,
            List<ReleaseEvidence> evidence,
            List<ReleaseBlocker> blockers,
            List<URI> artifactUris) {
        EnumMap<CertificationArea, ReleaseEvidence> byArea =
                new EnumMap<>(CertificationArea.class);
        for (ReleaseEvidence row : evidence) {
            ReleaseEvidence previous = byArea.put(row.area(), row);
            if (previous != null) throw new IllegalArgumentException("duplicate evidence area");
        }
        return new ReleaseCertificationManifest(
                releaseVersion, gitCommit, createdAt, byArea, blockers, artifactUris);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
