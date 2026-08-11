package io.aetherdb.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.sstable.block.BlockEnvelope;
import io.aetherdb.sstable.block.BlockHandle;
import io.aetherdb.sstable.block.BlockKind;
import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.block.Varint32;
import io.aetherdb.sstable.filter.BloomFilterV1;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.UUID;

class SSTableFormatV1Test {
    @TempDir Path temporaryDirectory;

    @Test
    void internalKeyRoundTripsAndOrdersSequencesDescending() {
        InternalKey newest = new InternalKey(new byte[] {(byte) 0x80}, 9, (byte) 1);
        InternalKey older = new InternalKey(new byte[] {(byte) 0x80}, 2, (byte) 1);
        assertThat(newest.compareTo(older)).isNegative();
        assertThat(InternalKey.decode(newest.encode()).encode()).isEqualTo(newest.encode());
        assertThatThrownBy(() -> InternalKey.decode(new byte[8]))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test
    void canonicalVarintBoundariesRoundTrip() {
        for (int value :
                new int[] {
                    0, 127, 128, 16_383, 16_384, (1 << 21) - 1, 1 << 21, Integer.MAX_VALUE
                }) {
            byte[] encoded = Varint32.encode(value);
            assertThat(Varint32.encodedLength(value)).isEqualTo(encoded.length);
            assertThat(Varint32.decode(encoded, 0, encoded.length).value()).isEqualTo(value);
        }
        assertThatThrownBy(() -> Varint32.decode(new byte[] {(byte) 0x80, 0}, 0, 2))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test
    void rangeComparatorMatchesCanonicalComparatorAcrossEdgeCases() {
        assertThat(InternalKey.compare(new byte[] {}, 0, 0, new byte[] {}, 0, 0)).isZero();
        assertThat(InternalKey.compare(bytes("abc"), 0, 3, bytes("abc"), 0, 3)).isZero();
        assertThat(InternalKey.compare(bytes("ab"), 0, 2, bytes("abc"), 0, 3)).isNegative();
        assertThat(InternalKey.compare(bytes("abc"), 0, 3, bytes("ab"), 0, 2)).isPositive();
        assertThat(
                        InternalKey.compare(
                                new byte[] {(byte) 0xFF}, 0, 1, new byte[] {(byte) 0x80}, 0, 1))
                .isPositive();
        assertThat(
                        InternalKey.compare(
                                new byte[] {(byte) 0x80, 0x01},
                                0,
                                2,
                                new byte[] {(byte) 0x80},
                                0,
                                1))
                .isPositive();

        Random random = new Random(17);
        for (int index = 0; index < 500; index++) {
            byte[] left = randomBytes(random, 1 + random.nextInt(16));
            byte[] right = randomBytes(random, 1 + random.nextInt(16));
            int expected = Arrays.compareUnsigned(left, right);
            assertThat(
                            Integer.signum(
                                    InternalKey.compare(
                                            left, 0, left.length, right, 0, right.length)))
                    .isEqualTo(Integer.signum(expected));
        }
    }

    @Test
    void restartCompressionReconstructsExactEntries() {
        List<RestartBlock.Entry> entries =
                List.of(
                        new RestartBlock.Entry(bytes("car"), bytes("1")),
                        new RestartBlock.Entry(bytes("carbon"), new byte[0]),
                        new RestartBlock.Entry(bytes("cart"), bytes("3")),
                        new RestartBlock.Entry(bytes("dog"), bytes("4")));
        assertThat(RestartBlock.decode(RestartBlock.encode(entries, 2)))
                .containsExactlyElementsOf(entries);
        int bodySize = 0;
        int restartCount = 0;
        byte[] previous = new byte[0];
        for (int index = 0; index < entries.size(); index++) {
            boolean restart = index % 2 == 0;
            bodySize += RestartBlock.encodedEntrySize(previous, entries.get(index), restart);
            if (restart) restartCount++;
            previous = entries.get(index).key();
        }
        assertThat(bodySize + restartCount * 4 + 4)
                .isEqualTo(RestartBlock.encode(entries, 2).length);
    }

    @Test
    void bloomHasNoFalseNegativesAndReasonableFalsePositiveRate() {
        Random random = new Random(91);
        List<byte[]> keys =
                java.util.stream.IntStream.range(0, 10_000)
                        .mapToObj(i -> new byte[] {(byte) (i >>> 8), (byte) i})
                        .toList();
        byte[] filter = BloomFilterV1.build(keys);
        BloomFilterV1.Filter decoded = BloomFilterV1.decode(filter);
        for (byte[] key : keys) assertThat(decoded.mayContain(key)).isTrue();
        byte[] present = keys.get(42);
        byte[] wrappedKey = new byte[] {99, present[0], present[1], 99};
        assertThat(decoded.mayContain(wrappedKey, 1, present.length)).isTrue();
        int positives = 0;
        for (int i = 0; i < 10_000; i++) {
            byte[] absent = new byte[8];
            random.nextBytes(absent);
            if (decoded.mayContain(absent)) positives++;
        }
        assertThat(positives).isLessThan(300);
    }

    @Test
    void bloomDecoderRejectsMalformedHeadersBeforeCreatingReusableView() {
        byte[] valid = BloomFilterV1.build(List.of(bytes("key")));
        assertThat(BloomFilterV1.decode(valid).mayContain(bytes("key"))).isTrue();

        byte[] wrongVersion = valid.clone();
        wrongVersion[0] = 2;
        assertThatThrownBy(() -> BloomFilterV1.decode(wrongVersion))
                .isInstanceOf(SSTableCorruptionException.class);

        byte[] wrongLength = valid.clone();
        ByteBuffer.wrap(wrongLength).order(ByteOrder.LITTLE_ENDIAN).putInt(20, 9);
        assertThatThrownBy(() -> BloomFilterV1.decode(wrongLength))
                .isInstanceOf(SSTableCorruptionException.class);

        assertThatThrownBy(() -> BloomFilterV1.decode(new byte[31]))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test
    void blockEnvelopeAuthenticatesRawBytesAndTrailerMetadata() {
        byte[] physical = BlockEnvelope.encode(bytes("payload"), BlockKind.DATA);
        assertThat(BlockEnvelope.decode(physical, BlockKind.DATA)).isEqualTo(bytes("payload"));

        byte[] corruptPayload = physical.clone();
        corruptPayload[0] ^= 1;
        assertThatThrownBy(() -> BlockEnvelope.decode(corruptPayload, BlockKind.DATA))
                .isInstanceOf(SSTableCorruptionException.class)
                .hasMessageContaining("checksum");
        assertThatThrownBy(() -> BlockEnvelope.decode(physical, BlockKind.INDEX))
                .isInstanceOf(SSTableCorruptionException.class)
                .hasMessageContaining("metadata");
    }

    @Test
    void blockHandleIsCanonicalBoundedAndRejectsReservedBytes() {
        BlockHandle handle = new BlockHandle(4_096, 200);
        assertThat(BlockHandle.decode(handle.encode())).isEqualTo(handle);
        handle.validateWithin(8_192);
        assertThatThrownBy(() -> handle.validateWithin(4_200))
                .isInstanceOf(SSTableCorruptionException.class);
        byte[] reserved = handle.encode();
        reserved[12] = 1;
        assertThatThrownBy(() -> BlockHandle.decode(reserved))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test
    void exactHeaderRegionRoundTripsAndRejectsReservedOrChecksumCorruption() {
        UUID databaseId = UUID.fromString("3b80c2d5-5044-4e3c-b34d-c574805d47e2");
        SSTableHeaderV1 expected = new SSTableHeaderV1(7, databaseId, 19, 2, 31, 3, 1234, 8_192);
        byte[] region = new byte[SSTableHeaderV1.HEADER_REGION_BYTES];
        System.arraycopy(expected.encode(), 0, region, 0, SSTableHeaderV1.HEADER_BYTES);
        assertThat(SSTableHeaderV1.decodeRegion(region)).isEqualTo(expected);

        byte[] reserved = region.clone();
        reserved[500] = 1;
        assertThatThrownBy(() -> SSTableHeaderV1.decodeRegion(reserved))
                .isInstanceOf(SSTableCorruptionException.class);
        byte[] corrupt = region.clone();
        corrupt[56] ^= 1;
        assertThatThrownBy(() -> SSTableHeaderV1.decodeRegion(corrupt))
                .isInstanceOf(SSTableCorruptionException.class);
        assertThat(ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN).getLong(16)).isEqualTo(7);
    }

    @Test
    void footerRoundTripsValidatesIdentityHandlesAndOverlap() {
        UUID databaseId = UUID.fromString("a791fb43-012a-4af8-93a9-34ae5ed988a7");
        SSTableFooterV1 footer =
                new SSTableFooterV1(
                        new BlockHandle(4_096, 80),
                        new BlockHandle(4_176, 80),
                        new BlockHandle(4_256, 80),
                        new BlockHandle(4_336, 80),
                        9,
                        8_192,
                        databaseId);
        assertThat(SSTableFooterV1.decode(footer.encode())).isEqualTo(footer);
        footer.validateHandles();

        SSTableFooterV1 overlap =
                new SSTableFooterV1(
                        new BlockHandle(4_096, 80),
                        new BlockHandle(4_100, 80),
                        new BlockHandle(4_256, 80),
                        new BlockHandle(4_336, 80),
                        9,
                        8_192,
                        databaseId);
        assertThatThrownBy(overlap::validateHandles).isInstanceOf(SSTableCorruptionException.class);
        byte[] corrupt = footer.encode();
        corrupt[124] ^= 1;
        assertThatThrownBy(() -> SSTableFooterV1.decode(corrupt))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test
    void completeTableBuildOpenLookupIterateAndVerifyRoundTrip() throws Exception {
        Path table = temporaryDirectory.resolve("SST-00000000000000000042.aesst");
        UUID databaseId = UUID.fromString("f381f09e-63a6-4dcc-ad22-e510aa7ad7d6");
        SSTableBuilder builder = new SSTableBuilder(table, 42, databaseId, 12_345);
        builder.add(new InternalKey(bytes("a"), 9, (byte) 1), bytes("new"));
        builder.add(new InternalKey(bytes("a"), 4, (byte) 2), new byte[0]);
        builder.add(new InternalKey(bytes("b"), 8, (byte) 1), bytes("bee"));
        builder.add(new InternalKey(bytes("empty"), 7, (byte) 1), new byte[0]);
        TableFileMetadata metadata = builder.finish();

        assertThat(metadata.entryCount()).isEqualTo(4);
        assertThat(metadata.smallestSequence()).isEqualTo(4);
        assertThat(metadata.largestSequence()).isEqualTo(9);
        try (SSTableReader reader = SSTableReader.open(table, metadata)) {
            assertThat(reader.lookup(bytes("a"), 20))
                    .isInstanceOfSatisfying(
                            SSTableLookup.Found.class,
                            found -> assertThat(found.value()).isEqualTo(bytes("new")));
            assertThat(reader.lookup(bytes("a"), 5)).isInstanceOf(SSTableLookup.Tombstone.class);
            assertThat(reader.lookup(bytes("a"), 3)).isInstanceOf(SSTableLookup.Absent.class);
            assertThat(reader.lookup(bytes("missing"), 20))
                    .isInstanceOf(SSTableLookup.Absent.class);
            assertThat(reader.entries()).hasSize(4);
            reader.verify();
        }
    }

    @Test
    void completeTableSpansBlocksAndDetectsAuthenticatedCorruption() throws Exception {
        Path table = temporaryDirectory.resolve("SST-00000000000000000077.aesst");
        UUID databaseId = UUID.fromString("e82b385c-c36d-478b-aaef-45e5d790fa1d");
        SSTableBuilder builder = new SSTableBuilder(table, 77, databaseId, 1);
        byte[] value = new byte[1_024];
        for (int index = 0; index < 100; index++) {
            builder.add(
                    new InternalKey(
                            ByteBuffer.allocate(4).putInt(index).array(), 100 - index, (byte) 1),
                    value);
        }
        TableFileMetadata metadata = builder.finish();
        assertThat(metadata.dataBlockCount()).isGreaterThan(1);
        byte[] bytes = Files.readAllBytes(table);
        bytes[SSTableHeaderV1.HEADER_REGION_BYTES + 3] ^= 1;
        Files.write(table, bytes);
        assertThatThrownBy(() -> SSTableReader.open(table, metadata))
                .isInstanceOf(SSTableCorruptionException.class)
                .hasMessageContaining("checksum");
    }

    @Test
    void indexedLookupResolvesEntriesAcrossBlockBoundaries() throws Exception {
        Path table = temporaryDirectory.resolve("SST-00000000000000000088.aesst");
        UUID databaseId = UUID.fromString("c98f846d-939f-44f6-9d8a-51084c7cb0d1");
        SSTableBuilder builder = new SSTableBuilder(table, 88, databaseId, 22);
        for (int index = 0; index < 80; index++) {
            byte[] key = ByteBuffer.allocate(4).putInt(index).array();
            builder.add(new InternalKey(key, 100 - index, (byte) 1), bytes("value-" + index));
        }
        TableFileMetadata metadata = builder.finish();
        try (SSTableReader reader = SSTableReader.open(table, metadata)) {
            assertThat(reader.lookup(bytes("\u0000\u0000\u0000\u0000"), 100))
                    .isInstanceOfSatisfying(
                            SSTableLookup.Found.class,
                            found -> assertThat(found.value()).isEqualTo(bytes("value-0")));
            SSTableLookup lookup = reader.lookup(ByteBuffer.allocate(4).putInt(80).array(), 100);
            assertThat(lookup).isInstanceOf(SSTableLookup.Absent.class);
            assertThat(reader.lookup(bytes("\u0000\u0000\u0000\u003f"), 50))
                    .isInstanceOfSatisfying(
                            SSTableLookup.Found.class,
                            found -> assertThat(found.value()).isEqualTo(bytes("value-63")));
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static byte[] randomBytes(Random random, int length) {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }
}
