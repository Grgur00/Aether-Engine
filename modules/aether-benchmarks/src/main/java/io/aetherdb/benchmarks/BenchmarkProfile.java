package io.aetherdb.benchmarks;

/** Canonical Chapter 30 benchmark profile metadata. */
public record BenchmarkProfile(String id, BenchmarkProfileScope scope, String description) {
    /** Validates stable profile identity and description. */
    public BenchmarkProfile {
        if (id == null
                || id.isBlank()
                || id.length() > 128
                || !id.matches("[a-z0-9_.]+")
                || scope == null
                || description == null
                || description.isBlank()
                || description.length() > 512) {
            throw new IllegalArgumentException("invalid benchmark profile");
        }
        id = id.strip();
        description = description.strip();
    }
}
