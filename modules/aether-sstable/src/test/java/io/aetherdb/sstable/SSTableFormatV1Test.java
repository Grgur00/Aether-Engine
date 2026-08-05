package io.aetherdb.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.block.Varint32;
import io.aetherdb.sstable.filter.BloomFilterV1;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SSTableFormatV1Test {
    @Test void internalKeyRoundTripsAndOrdersSequencesDescending() {
        InternalKey newest = new InternalKey(new byte[] {(byte) 0x80}, 9, (byte) 1);
        InternalKey older = new InternalKey(new byte[] {(byte) 0x80}, 2, (byte) 1);
        assertThat(newest.compareTo(older)).isNegative();
        assertThat(InternalKey.decode(newest.encode()).encode()).isEqualTo(newest.encode());
        assertThatThrownBy(() -> InternalKey.decode(new byte[8])).isInstanceOf(SSTableCorruptionException.class);
    }

    @Test void canonicalVarintBoundariesRoundTrip() {
        for (int value : new int[] {0, 127, 128, 16_383, 16_384, (1 << 21) - 1, 1 << 21, Integer.MAX_VALUE}) {
            byte[] encoded = Varint32.encode(value);
            assertThat(Varint32.decode(encoded, 0, encoded.length).value()).isEqualTo(value);
        }
        assertThatThrownBy(() -> Varint32.decode(new byte[] {(byte) 0x80, 0}, 0, 2))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test void restartCompressionReconstructsExactEntries() {
        List<RestartBlock.Entry> entries = List.of(
                new RestartBlock.Entry(bytes("car"), bytes("1")),
                new RestartBlock.Entry(bytes("carbon"), new byte[0]),
                new RestartBlock.Entry(bytes("cart"), bytes("3")),
                new RestartBlock.Entry(bytes("dog"), bytes("4")));
        assertThat(RestartBlock.decode(RestartBlock.encode(entries, 2))).containsExactlyElementsOf(entries);
    }

    @Test void bloomHasNoFalseNegativesAndReasonableFalsePositiveRate() {
        Random random = new Random(91);
        List<byte[]> keys = java.util.stream.IntStream.range(0, 10_000).mapToObj(i -> new byte[] {(byte)(i>>>8), (byte)i}).toList();
        byte[] filter = BloomFilterV1.build(keys);
        for (byte[] key : keys) assertThat(BloomFilterV1.mayContain(filter, key)).isTrue();
        int positives = 0;
        for (int i = 0; i < 10_000; i++) { byte[] absent = new byte[8]; random.nextBytes(absent); if (BloomFilterV1.mayContain(filter, absent)) positives++; }
        assertThat(positives).isLessThan(300);
    }

    private static byte[] bytes(String value) { return value.getBytes(java.nio.charset.StandardCharsets.UTF_8); }
}
