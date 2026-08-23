package io.aetherdb.benchmarks;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Deterministic in-repository storage model for CI/nightly benchmark baselines. */
public record BenchmarkBaselineStore(List<BenchmarkBaselineEntry> entries) {
    /** Validates uniqueness by profile ID and stores entries in profile order. */
    public BenchmarkBaselineStore {
        if (entries == null || entries.size() > 512) throw new IllegalArgumentException("invalid baseline store");
        Map<String, BenchmarkBaselineEntry> byProfile = new LinkedHashMap<>();
        for (BenchmarkBaselineEntry entry : entries) {
            if (byProfile.put(entry.profileId(), entry) != null) {
                throw new IllegalArgumentException("duplicate baseline profile: " + entry.profileId());
            }
        }
        entries =
                byProfile.values().stream()
                        .sorted(Comparator.comparing(BenchmarkBaselineEntry::profileId))
                        .toList();
    }

    /** Finds a stored baseline by profile ID. */
    public Optional<BenchmarkBaselineEntry> find(String profileId) {
        return entries.stream().filter(entry -> entry.profileId().equals(profileId)).findFirst();
    }

    /** Serializes the baseline store as deterministic line-oriented properties. */
    public String encode() {
        StringBuilder out = new StringBuilder();
        out.append("schemaVersion=1\n");
        out.append("entries=").append(entries.size()).append('\n');
        for (int index = 0; index < entries.size(); index++) {
            BenchmarkBaselineEntry entry = entries.get(index);
            String prefix = "entry." + index + '.';
            out.append(prefix).append("profileId=").append(escape(entry.profileId())).append('\n');
            out.append(prefix).append("resultUri=").append(escape(entry.resultUri())).append('\n');
            out.append(prefix).append("gitCommit=").append(escape(entry.gitCommit())).append('\n');
            out.append(prefix).append("throughputOpsPerSecond=").append(entry.throughputOpsPerSecond()).append('\n');
            out.append(prefix).append("p50Nanos=").append(entry.p50Nanos()).append('\n');
            out.append(prefix).append("p95Nanos=").append(entry.p95Nanos()).append('\n');
            out.append(prefix).append("p99Nanos=").append(entry.p99Nanos()).append('\n');
            out.append(prefix).append("acknowledged=").append(entry.acknowledged()).append('\n');
        }
        return out.toString();
    }

    /** Decodes the deterministic baseline-store format. */
    public static BenchmarkBaselineStore decode(String input) {
        if (input == null) throw new IllegalArgumentException("baseline store is required");
        Map<String, String> values = new LinkedHashMap<>();
        for (String line : input.split("\\R")) {
            if (line.isBlank()) continue;
            int equals = line.indexOf('=');
            if (equals <= 0) throw new IllegalArgumentException("invalid baseline line");
            values.put(line.substring(0, equals), unescape(line.substring(equals + 1)));
        }
        if (!"1".equals(values.get("schemaVersion"))) {
            throw new IllegalArgumentException("unsupported baseline schema");
        }
        int count = Integer.parseInt(values.getOrDefault("entries", "-1"));
        if (count < 0) throw new IllegalArgumentException("invalid baseline entry count");
        java.util.ArrayList<BenchmarkBaselineEntry> entries = new java.util.ArrayList<>();
        for (int index = 0; index < count; index++) {
            String prefix = "entry." + index + '.';
            entries.add(
                    new BenchmarkBaselineEntry(
                            required(values, prefix + "profileId"),
                            required(values, prefix + "resultUri"),
                            required(values, prefix + "gitCommit"),
                            Double.parseDouble(required(values, prefix + "throughputOpsPerSecond")),
                            Long.parseLong(required(values, prefix + "p50Nanos")),
                            Long.parseLong(required(values, prefix + "p95Nanos")),
                            Long.parseLong(required(values, prefix + "p99Nanos")),
                            Long.parseLong(required(values, prefix + "acknowledged"))));
        }
        return new BenchmarkBaselineStore(entries);
    }

    private static String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null) throw new IllegalArgumentException("missing baseline field: " + key);
        return value;
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\n", "\\n").replace("=", "\\e");
    }

    private static String unescape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (!escaped && character == '\\') {
                escaped = true;
                continue;
            }
            if (escaped) {
                out.append(character == 'n' ? '\n' : character == 'e' ? '=' : character);
                escaped = false;
            } else {
                out.append(character);
            }
        }
        if (escaped) out.append('\\');
        return out.toString();
    }
}
