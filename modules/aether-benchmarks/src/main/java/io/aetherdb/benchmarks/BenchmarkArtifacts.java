package io.aetherdb.benchmarks;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Builds canonical benchmark artifact references from result paths and runtime settings. */
public final class BenchmarkArtifacts {
    private static final String JFR_PROPERTY = "aether.benchmark.jfr.path";
    private static final String JFR_ENVIRONMENT = "AETHER_JFR_PATH";

    private BenchmarkArtifacts() {}

    /** Returns canonical artifacts for a result JSON file plus optional JFR recording. */
    public static List<BenchmarkArtifact> forResult(Path output) {
        if (output == null) throw new IllegalArgumentException("output is required");
        List<BenchmarkArtifact> artifacts = new ArrayList<>();
        artifacts.add(new BenchmarkArtifact("result-json", output.toAbsolutePath().normalize().toUri()));
        jfrUri().ifPresent(uri -> artifacts.add(new BenchmarkArtifact("jfr-recording", uri)));
        return List.copyOf(artifacts);
    }

    private static java.util.Optional<URI> jfrUri() {
        String configured = System.getProperty(JFR_PROPERTY);
        if (configured == null || configured.isBlank()) configured = System.getenv(JFR_ENVIRONMENT);
        if (configured == null || configured.isBlank()) return java.util.Optional.empty();
        return java.util.Optional.of(Path.of(configured).toAbsolutePath().normalize().toUri());
    }
}
