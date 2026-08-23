package io.aetherdb.release;

import java.net.URI;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/** Evaluates Chapter 38 release certification manifests. */
public final class ReleaseCertificationEvaluator {
    private ReleaseCertificationEvaluator() {}

    public static ReleaseCertificationReport evaluate(ReleaseCertificationManifest manifest) {
        EnumSet<CertificationArea> missing = EnumSet.allOf(CertificationArea.class);
        List<String> failures = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        int green = 0, notApplicable = 0, red = 0;
        for (ReleaseEvidence row : manifest.evidence().values()) {
            missing.remove(row.area());
            switch (row.status()) {
                case GREEN -> green++;
                case NOT_APPLICABLE -> notApplicable++;
                case RED -> {
                    red++;
                    failures.add("red evidence: " + row.area());
                }
            }
        }
        for (CertificationArea area : missing) failures.add("missing evidence: " + area);
        for (ReleaseBlocker blocker : manifest.blockers()) {
            if (blocker.blocking()) failures.add("release blocker: " + blocker.code());
            else if (!blocker.resolved()) warnings.add("release warning: " + blocker.code());
        }
        return new ReleaseCertificationReport(
                failures.isEmpty(),
                failures,
                warnings,
                green,
                notApplicable,
                red,
                missing.size());
    }

    public static String toJson(
            ReleaseCertificationManifest manifest, ReleaseCertificationReport report) {
        String artifacts =
                manifest.artifactUris().stream()
                        .map(URI::toString)
                        .map(ReleaseCertificationEvaluator::jsonString)
                        .collect(java.util.stream.Collectors.joining(","));
        String failures =
                report.failures().stream()
                        .map(ReleaseCertificationEvaluator::jsonString)
                        .collect(java.util.stream.Collectors.joining(","));
        String warnings =
                report.warnings().stream()
                        .map(ReleaseCertificationEvaluator::jsonString)
                        .collect(java.util.stream.Collectors.joining(","));
        return String.format(
                Locale.ROOT,
                "{\"mode\":\"release-certification\",\"productionReady\":%s,"
                        + "\"releaseVersion\":%s,\"gitCommit\":%s,"
                        + "\"createdAt\":%s,\"greenRows\":%d,"
                        + "\"notApplicableRows\":%d,\"redRows\":%d,\"missingRows\":%d,"
                        + "\"artifactUris\":[%s],\"failures\":[%s],\"warnings\":[%s]}",
                report.productionReady(),
                jsonString(manifest.releaseVersion()),
                jsonString(manifest.gitCommit()),
                jsonString(manifest.createdAt().toString()),
                report.greenRows(),
                report.notApplicableRows(),
                report.redRows(),
                report.missingRows(),
                artifacts,
                failures,
                warnings);
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
}
