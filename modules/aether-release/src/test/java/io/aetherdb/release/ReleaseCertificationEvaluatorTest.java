package io.aetherdb.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

class ReleaseCertificationEvaluatorTest {
    @Test
    void completeGreenManifestIsProductionReady() {
        ReleaseCertificationManifest manifest =
                manifest(allGreenEvidence(), List.of(), List.of(URI.create("file:///sbom.spdx")));

        ReleaseCertificationReport report = ReleaseCertificationEvaluator.evaluate(manifest);

        assertThat(report.productionReady()).isTrue();
        assertThat(report.greenRows()).isEqualTo(CertificationArea.values().length);
        assertThat(report.missingRows()).isZero();
        assertThat(report.redRows()).isZero();
        assertThat(report.failures()).isEmpty();
    }

    @Test
    void missingAndRedEvidenceBlockProductionReadiness() {
        List<ReleaseEvidence> evidence = allGreenEvidence();
        evidence.removeIf(row -> row.area() == CertificationArea.KUBERNETES);
        evidence.removeIf(row -> row.area() == CertificationArea.SECURITY);
        evidence.add(
                new ReleaseEvidence(
                        CertificationArea.SECURITY,
                        EvidenceStatus.RED,
                        "plaintext production RPC still enabled",
                        List.of(URI.create("file:///security-report.json")),
                        ""));
        ReleaseCertificationManifest manifest = manifest(evidence, List.of(), List.of());

        ReleaseCertificationReport report = ReleaseCertificationEvaluator.evaluate(manifest);

        assertThat(report.productionReady()).isFalse();
        assertThat(report.redRows()).isEqualTo(1);
        assertThat(report.missingRows()).isEqualTo(1);
        assertThat(report.failures())
                .contains("red evidence: SECURITY", "missing evidence: KUBERNETES");
    }

    @Test
    void unresolvedBlockerPreventsProductionReadyLabel() {
        ReleaseCertificationManifest manifest =
                manifest(
                        allGreenEvidence(),
                        List.of(
                                new ReleaseBlocker(
                                        "CERT-PLAINTEXT-RPC",
                                        "plaintext production RPC exists",
                                        ReleaseBlockerSeverity.BLOCKER,
                                        false)),
                        List.of());

        ReleaseCertificationReport report = ReleaseCertificationEvaluator.evaluate(manifest);

        assertThat(report.productionReady()).isFalse();
        assertThat(report.failures()).contains("release blocker: CERT-PLAINTEXT-RPC");
    }

    @Test
    void warningBlockerDoesNotFailCertificationButIsReported() {
        ReleaseCertificationManifest manifest =
                manifest(
                        allGreenEvidence(),
                        List.of(
                                new ReleaseBlocker(
                                        "CERT-OPTIONAL-TRACE",
                                        "trace sampling dashboard is incomplete",
                                        ReleaseBlockerSeverity.WARNING,
                                        false)),
                        List.of());

        ReleaseCertificationReport report = ReleaseCertificationEvaluator.evaluate(manifest);

        assertThat(report.productionReady()).isTrue();
        assertThat(report.warnings()).contains("release warning: CERT-OPTIONAL-TRACE");
    }

    @Test
    void notApplicableEvidenceRequiresRationaleAndCountsAsApproved() {
        assertThatThrownBy(
                        () ->
                                new ReleaseEvidence(
                                        CertificationArea.KUBERNETES,
                                        EvidenceStatus.NOT_APPLICABLE,
                                        "single-node embedded release",
                                        List.of(),
                                        ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("rationale");

        List<ReleaseEvidence> evidence = allGreenEvidence();
        evidence.removeIf(row -> row.area() == CertificationArea.KUBERNETES);
        evidence.add(
                new ReleaseEvidence(
                        CertificationArea.KUBERNETES,
                        EvidenceStatus.NOT_APPLICABLE,
                        "single-node embedded release",
                        List.of(),
                        "No operator artifact is shipped in this release."));

        ReleaseCertificationReport report =
                ReleaseCertificationEvaluator.evaluate(manifest(evidence, List.of(), List.of()));

        assertThat(report.productionReady()).isTrue();
        assertThat(report.notApplicableRows()).isEqualTo(1);
    }

    @Test
    void duplicateEvidenceAreasAreRejected() {
        List<ReleaseEvidence> evidence = allGreenEvidence();
        evidence.add(
                new ReleaseEvidence(
                        CertificationArea.CORRECTNESS,
                        EvidenceStatus.GREEN,
                        "duplicate",
                        List.of(URI.create("file:///duplicate.json")),
                        ""));

        assertThatThrownBy(() -> manifest(evidence, List.of(), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate evidence area");
    }

    @Test
    void jsonReportContainsGoNoGoCountersAndEscapedFailures() {
        ReleaseCertificationManifest manifest =
                manifest(
                        allGreenEvidence(),
                        List.of(
                                new ReleaseBlocker(
                                        "CERT-QUOTE",
                                        "contains \"quoted\" text",
                                        ReleaseBlockerSeverity.BLOCKER,
                                        false)),
                        List.of(URI.create("file:///release/certification.json")));
        ReleaseCertificationReport report = ReleaseCertificationEvaluator.evaluate(manifest);

        String json = ReleaseCertificationEvaluator.toJson(manifest, report);

        assertThat(json)
                .contains("\"mode\":\"release-certification\"")
                .contains("\"productionReady\":false")
                .contains("\"greenRows\":13")
                .contains("\"missingRows\":0")
                .contains("\"artifactUris\":[\"file:///release/certification.json\"]")
                .contains("\"failures\":[\"release blocker: CERT-QUOTE\"]");
    }

    @Test
    void manifestCodecRoundTripsDeterministicPropertiesArtifact() {
        List<ReleaseEvidence> evidence = allGreenEvidence();
        evidence.removeIf(row -> row.area() == CertificationArea.KUBERNETES);
        evidence.add(
                new ReleaseEvidence(
                        CertificationArea.KUBERNETES,
                        EvidenceStatus.NOT_APPLICABLE,
                        "embedded release only",
                        List.of(),
                        "No Kubernetes artifact is shipped."));
        ReleaseCertificationManifest manifest =
                manifest(
                        evidence,
                        List.of(
                                new ReleaseBlocker(
                                        "CERT-WARN",
                                        "non-blocking note",
                                        ReleaseBlockerSeverity.WARNING,
                                        false)),
                        List.of(URI.create("file:///release.json")));

        String encoded = ReleaseCertificationManifestCodec.encode(manifest);
        ReleaseCertificationManifest decoded = ReleaseCertificationManifestCodec.decode(encoded);

        assertThat(encoded)
                .contains("release.version=0.2.0-rc.1")
                .contains("evidence.KUBERNETES.status=NOT_APPLICABLE")
                .contains("blocker.0.code=CERT-WARN");
        assertThat(ReleaseCertificationManifestCodec.encode(decoded)).isEqualTo(encoded);
        assertThat(decoded.evidence().get(CertificationArea.KUBERNETES).nonApplicabilityRationale())
                .isEqualTo("No Kubernetes artifact is shipped.");
        assertThat(decoded.blockers()).hasSize(1);
    }

    private static ReleaseCertificationManifest manifest(
            List<ReleaseEvidence> evidence,
            List<ReleaseBlocker> blockers,
            List<URI> artifactUris) {
        return ReleaseCertificationManifest.of(
                "0.2.0-rc.1",
                "0123456789abcdef0123456789abcdef01234567",
                Instant.parse("2026-08-19T12:00:00Z"),
                evidence,
                blockers,
                artifactUris);
    }

    private static List<ReleaseEvidence> allGreenEvidence() {
        List<ReleaseEvidence> evidence = new ArrayList<>();
        for (CertificationArea area : CertificationArea.values()) {
            evidence.add(
                    new ReleaseEvidence(
                            area,
                            EvidenceStatus.GREEN,
                            area.name().toLowerCase(java.util.Locale.ROOT) + " evidence passed",
                            List.of(URI.create("file:///" + area.name().toLowerCase(java.util.Locale.ROOT) + ".json")),
                            ""));
        }
        return evidence;
    }
}
