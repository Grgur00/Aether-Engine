package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Map;

class BenchmarkResultJsonV1Test {
    @Test
    void encodesCanonicalSchemaFieldsInStableOrder() {
        BenchmarkResultV1 result =
                new BenchmarkResultV1(
                        "local.write.sequential.group_sync",
                        "abc123",
                        false,
                        Map.of("jdk", "21", "os", "Windows"),
                        Map.of("durability", "GROUP_SYNC"),
                        Map.of("records", "100000"),
                        new BenchmarkCounters(100, 100, 0, 0, 0),
                        1234.5678,
                        new BenchmarkLatencyHistogram(100, 10, 20, 30, 40),
                        Map.of("filesystem", "NTFS"),
                        List.of(new BenchmarkArtifact("raw", URI.create("file:///tmp/result.json"))));

        String json = BenchmarkResultJsonV1.encode(result);

        assertThat(json).contains("\"schemaVersion\": 1");
        assertThat(json).contains("\"benchmarkId\": \"local.write.sequential.group_sync\"");
        assertThat(json).contains("\"gitCommit\": \"abc123\"");
        assertThat(json).contains("\"counters\"");
        assertThat(json).contains("\"throughputOpsPerSecond\": 1234.568");
        assertThat(json).contains("\"latencyHistogram\"");
        assertThat(json).contains("\"artifacts\"");
        assertThat(json.indexOf("\"jdk\"")).isLessThan(json.indexOf("\"os\""));
    }

    @Test
    void rejectsInvalidCountersAndLatency() {
        assertThatThrownBy(() -> new BenchmarkCounters(1, 1, 1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BenchmarkLatencyHistogram(1, 20, 10, 30, 40))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
