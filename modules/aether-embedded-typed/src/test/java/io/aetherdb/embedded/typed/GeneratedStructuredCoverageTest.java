package io.aetherdb.embedded.typed;

import static org.assertj.core.api.Assertions.assertThat;

import io.aetherdb.codec.generated.GeneratedCodecs;

import org.junit.jupiter.api.Test;

class GeneratedStructuredCoverageTest {
    @Test
    void stableEnumAndNestedRecordRoundTripWithoutReflection() {
        Delivery expected =
                new Delivery(DeliveryStatus.IN_TRANSIT, new PostalAddress("Zagreb", "10000"));
        var codec = GeneratedCodecs.forRecord(Delivery.class);

        byte[] first = codec.encode(expected);

        assertThat(codec.encode(expected)).isEqualTo(first);
        assertThat(codec.decode(1, first)).isEqualTo(expected);
        assertThat(
                        new String(
                                GeneratedCodecs.descriptor(codec.schemaId(), 1).orElseThrow(),
                                java.nio.charset.StandardCharsets.UTF_8))
                .contains("|ENUM|")
                .contains("|NESTED|");
    }
}
