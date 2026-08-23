package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

class BenchmarkArtifactsTest {
    @Test
    void includesResultJsonOnlyByDefault() {
        String prior = System.getProperty("aether.benchmark.jfr.path");
        System.clearProperty("aether.benchmark.jfr.path");
        try {
            assertThat(BenchmarkArtifacts.forResult(Path.of("result.json")))
                    .extracting(BenchmarkArtifact::kind)
                    .containsExactly("result-json");
        } finally {
            restore(prior);
        }
    }

    @Test
    void includesJfrRecordingWhenConfigured() {
        String prior = System.getProperty("aether.benchmark.jfr.path");
        System.setProperty("aether.benchmark.jfr.path", "build/jfr/aether-benchmark.jfr");
        try {
            assertThat(BenchmarkArtifacts.forResult(Path.of("result.json")))
                    .extracting(BenchmarkArtifact::kind)
                    .containsExactly("result-json", "jfr-recording");
        } finally {
            restore(prior);
        }
    }

    private static void restore(String prior) {
        if (prior == null) {
            System.clearProperty("aether.benchmark.jfr.path");
        } else {
            System.setProperty("aether.benchmark.jfr.path", prior);
        }
    }
}
