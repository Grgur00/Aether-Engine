package io.aetherdb.embedded.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.codec.generated.GeneratedCodecs;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

class GeneratedContainerCoverageTest {
    @Test
    void generatedContainersRoundTripAndCanonicalizeUnorderedInputs() {
        Map<String, Integer> firstCounters = new LinkedHashMap<>();
        firstCounters.put("z", 26);
        firstCounters.put("a", 1);
        Map<String, Integer> secondCounters = new LinkedHashMap<>();
        secondCounters.put("a", 1);
        secondCounters.put("z", 26);
        ContainerRecord first =
                new ContainerRecord(
                        Optional.of("ace"),
                        List.of(3L, -1L, 3L),
                        new LinkedHashSet<>(List.of("z", "a")),
                        firstCounters,
                        List.of(List.of("b", "a"), List.of("z")));
        ContainerRecord second =
                new ContainerRecord(
                        Optional.of("ace"),
                        List.of(3L, -1L, 3L),
                        new LinkedHashSet<>(List.of("a", "z")),
                        secondCounters,
                        List.of(List.of("b", "a"), List.of("z")));
        var codec = GeneratedCodecs.forRecord(ContainerRecord.class);

        byte[] encoded = codec.encode(first);
        ContainerRecord decoded = codec.decode(1, encoded);

        assertThat(codec.encode(second)).isEqualTo(encoded);
        assertThat(decoded.alias()).contains("ace");
        assertThat(decoded.scores()).containsExactly(3L, -1L, 3L);
        assertThat(decoded.tags()).containsExactly("a", "z");
        assertThat(decoded.counters()).containsExactlyEntriesOf(secondCounters);
        assertThat(decoded.matrix()).containsExactly(List.of("b", "a"), List.of("z"));
    }
}
