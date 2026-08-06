package io.aetherdb.codec.generated;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CanonicalContainerCodecTest {
    private static final CanonicalContainerCodec.ElementCodec<String> STRINGS =
            new CanonicalContainerCodec.ElementCodec<>() {
                @Override public byte[] encode(String value) {
                    return CanonicalRecordWriter.string(value, 100);
                }
                @Override public String decode(byte[] encoded) {
                    return new String(encoded, StandardCharsets.UTF_8);
                }
            };

    @Test void listPreservesOrderAndChecksCountBeforeConstruction() {
        byte[] encoded = CanonicalContainerCodec.list(List.of("z", "a"), 2, 100, STRINGS);
        assertThat(CanonicalContainerCodec.list(encoded, 2, 100, STRINGS)).containsExactly("z", "a");
        assertThatThrownBy(() -> CanonicalContainerCodec.list(List.of("a", "b", "c"), 2, 100, STRINGS))
                .hasMessage("CONTAINER_COUNT_EXCEEDED");
        assertThatThrownBy(() -> CanonicalContainerCodec.list(encoded, 1, 100, STRINGS))
                .hasMessage("CONTAINER_COUNT_EXCEEDED");
    }

    @Test void setAndMapBytesAreIndependentOfSourceIterationOrder() {
        Set<String> firstSet = new LinkedHashSet<>(List.of("z", "a"));
        Set<String> secondSet = new LinkedHashSet<>(List.of("a", "z"));
        assertThat(CanonicalContainerCodec.set(firstSet, 10, 100, STRINGS))
                .isEqualTo(CanonicalContainerCodec.set(secondSet, 10, 100, STRINGS));

        Map<String, String> firstMap = new LinkedHashMap<>(); firstMap.put("z", "last"); firstMap.put("a", "first");
        Map<String, String> secondMap = new LinkedHashMap<>(); secondMap.put("a", "first"); secondMap.put("z", "last");
        byte[] encoded = CanonicalContainerCodec.map(firstMap, 10, 200, STRINGS, STRINGS);
        assertThat(encoded).isEqualTo(CanonicalContainerCodec.map(secondMap, 10, 200, STRINGS, STRINGS));
        Map<String, String> canonicalOrder = new LinkedHashMap<>();
        canonicalOrder.put("a", "first"); canonicalOrder.put("z", "last");
        assertThat(CanonicalContainerCodec.map(encoded, 10, 200, STRINGS, STRINGS))
                .containsExactlyEntriesOf(canonicalOrder);
    }

    @Test void malformedOrderingAndTrailingBytesFail() {
        byte[] canonical = CanonicalContainerCodec.set(Set.of("a", "z"), 10, 100, STRINGS);
        byte[] reversed = canonical.clone();
        // count=2, then [length=1,'a'], [length=1,'z']; exchange payload bytes only.
        reversed[2] = 'z'; reversed[4] = 'a';
        assertThatThrownBy(() -> CanonicalContainerCodec.set(reversed, 10, 100, STRINGS))
                .hasMessage("CONTAINER_DUPLICATE_CANONICAL_KEY");
        byte[] trailing = java.util.Arrays.copyOf(canonical, canonical.length + 1);
        assertThatThrownBy(() -> CanonicalContainerCodec.set(trailing, 10, 100, STRINGS))
                .hasMessage("container trailing bytes");
    }
}
