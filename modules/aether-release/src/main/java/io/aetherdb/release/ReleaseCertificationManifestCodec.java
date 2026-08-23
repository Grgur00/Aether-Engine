package io.aetherdb.release;

import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.Writer;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

/** UTF-8 properties codec for release certification manifest artifacts. */
public final class ReleaseCertificationManifestCodec {
    private ReleaseCertificationManifestCodec() {}

    public static String encode(ReleaseCertificationManifest manifest) {
        Properties properties = new Properties();
        properties.setProperty("release.version", manifest.releaseVersion());
        properties.setProperty("git.commit", manifest.gitCommit());
        properties.setProperty("created.at", manifest.createdAt().toString());
        properties.setProperty("artifact.count", Integer.toString(manifest.artifactUris().size()));
        for (int index = 0; index < manifest.artifactUris().size(); index++) {
            properties.setProperty(
                    "artifact." + index + ".uri", manifest.artifactUris().get(index).toString());
        }
        for (CertificationArea area : CertificationArea.values()) {
            ReleaseEvidence evidence = manifest.evidence().get(area);
            if (evidence == null) continue;
            String prefix = "evidence." + area.name() + ".";
            properties.setProperty(prefix + "status", evidence.status().name());
            properties.setProperty(prefix + "summary", evidence.summary());
            properties.setProperty(prefix + "rationale", evidence.nonApplicabilityRationale());
            properties.setProperty(
                    prefix + "artifact.count", Integer.toString(evidence.artifactUris().size()));
            for (int index = 0; index < evidence.artifactUris().size(); index++) {
                properties.setProperty(
                        prefix + "artifact." + index + ".uri",
                        evidence.artifactUris().get(index).toString());
            }
        }
        properties.setProperty("blocker.count", Integer.toString(manifest.blockers().size()));
        for (int index = 0; index < manifest.blockers().size(); index++) {
            ReleaseBlocker blocker = manifest.blockers().get(index);
            String prefix = "blocker." + index + ".";
            properties.setProperty(prefix + "code", blocker.code());
            properties.setProperty(prefix + "description", blocker.description());
            properties.setProperty(prefix + "severity", blocker.severity().name());
            properties.setProperty(prefix + "resolved", Boolean.toString(blocker.resolved()));
        }
        return storeDeterministic(properties);
    }

    public static ReleaseCertificationManifest decode(String encoded) {
        Properties properties = new Properties();
        try (Reader reader = new StringReader(encoded)) {
            properties.load(reader);
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
        List<ReleaseEvidence> evidence = new ArrayList<>();
        for (CertificationArea area : CertificationArea.values()) {
            String prefix = "evidence." + area.name() + ".";
            String status = properties.getProperty(prefix + "status");
            if (status == null) continue;
            evidence.add(
                    new ReleaseEvidence(
                            area,
                            EvidenceStatus.valueOf(status),
                            required(properties, prefix + "summary"),
                            uris(properties, prefix + "artifact."),
                            properties.getProperty(prefix + "rationale", "")));
        }
        List<ReleaseBlocker> blockers = new ArrayList<>();
        int blockerCount = intProperty(properties, "blocker.count");
        for (int index = 0; index < blockerCount; index++) {
            String prefix = "blocker." + index + ".";
            blockers.add(
                    new ReleaseBlocker(
                            required(properties, prefix + "code"),
                            required(properties, prefix + "description"),
                            ReleaseBlockerSeverity.valueOf(required(properties, prefix + "severity")),
                            Boolean.parseBoolean(required(properties, prefix + "resolved"))));
        }
        return ReleaseCertificationManifest.of(
                required(properties, "release.version"),
                required(properties, "git.commit"),
                Instant.parse(required(properties, "created.at")),
                evidence,
                blockers,
                uris(properties, "artifact."));
    }

    private static List<URI> uris(Properties properties, String prefix) {
        int count = intProperty(properties, prefix + "count");
        List<URI> uris = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            uris.add(URI.create(required(properties, prefix + index + ".uri")));
        }
        return uris;
    }

    private static int intProperty(Properties properties, String key) {
        return Integer.parseInt(required(properties, key));
    }

    private static String required(Properties properties, String key) {
        String value = properties.getProperty(key);
        if (value == null) throw new IllegalArgumentException("release manifest missing: " + key);
        return value;
    }

    private static String storeDeterministic(Properties properties) {
        StringWriter writer = new StringWriter();
        properties.stringPropertyNames().stream()
                .sorted(Comparator.naturalOrder())
                .forEach(name -> writeProperty(writer, name, properties.getProperty(name)));
        return writer.toString();
    }

    private static void writeProperty(Writer writer, String key, String value) {
        try {
            writer.write(escape(key));
            writer.write('=');
            writer.write(escape(value));
            writer.write('\n');
        } catch (IOException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                case '=' -> escaped.append("\\=");
                case ':' -> escaped.append("\\:");
                case '#' -> escaped.append("\\#");
                case '!' -> escaped.append("\\!");
                default -> escaped.append(character);
            }
        }
        return escaped.toString();
    }
}
