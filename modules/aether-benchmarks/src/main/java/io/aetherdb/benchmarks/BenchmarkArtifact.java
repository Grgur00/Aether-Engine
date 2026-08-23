package io.aetherdb.benchmarks;

import java.net.URI;

/** External or local artifact referenced by a benchmark result. */
public record BenchmarkArtifact(String kind, URI uri) {
    /** Validates artifact kind and URI. */
    public BenchmarkArtifact {
        if (kind == null
                || kind.isBlank()
                || kind.length() > 64
                || kind.chars().anyMatch(character -> character == 0 || Character.isISOControl(character))
                || uri == null
                || uri.toString().length() > 2048) {
            throw new IllegalArgumentException("invalid benchmark artifact");
        }
        kind = kind.strip();
    }
}
