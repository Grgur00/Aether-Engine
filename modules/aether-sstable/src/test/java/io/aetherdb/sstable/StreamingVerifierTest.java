package io.aetherdb.sstable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.sstable.block.BlockEnvelope;
import io.aetherdb.sstable.block.BlockHandle;
import io.aetherdb.sstable.block.BlockKind;
import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.block.RestartBlockScanner;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
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
import java.util.function.Consumer;

class StreamingVerifierTest {
    @TempDir Path directory;
    private final UUID id = UUID.fromString("f381f09e-63a6-4dcc-ad22-e510aa7ad7d6");

    @Test void goldenAccountingAndNormalReads() throws Exception {
        var metadata = table(100);
        SSTableVerifier.VerificationResult result;
        try (var trace = SSTableVerificationTrace.begin()) {
            result = SSTableVerifier.verify(metadata.path(), id, manifest(metadata));
            assertThat(trace.snapshot()).containsEntry("tablesFullyVerified", 1L)
                    .containsEntry("bytesFullyVerified", metadata.fileSize());
            assertThat(trace.streamingSnapshot()).containsEntry("implementation", "streaming-v1")
                    .containsEntry("entries", 100L).containsEntry("logicalValueBytes", 102400L);
        }
        assertThat(result.entryCount()).isEqualTo(100);
        assertThat(result.rawKeyBytes()).isEqualTo(1300);
        assertThat(result.rawValueBytes()).isEqualTo(100 * 1024);
        assertThat(result.smallestSequence()).isEqualTo(1);
        assertThat(result.largestSequence()).isEqualTo(100);
        assertThat(result.dataBlockCount()).isEqualTo(metadata.dataBlockCount()).isGreaterThan(1);
        assertThat(result.bytesRead()).isEqualTo(metadata.fileSize());
        try (var reader = SSTableReader.open(metadata.path(), id, manifest(metadata))) {
            assertThat(reader.entries()).hasSize(100);
            assertThat(reader.lookup(new byte[4], 100)).isInstanceOf(SSTableLookup.Found.class);
        }
    }

    @Test void fileAndIdentityCorruptionRejectedByBoth() throws Exception {
        var metadata = table(2);
        byte[] original = Files.readAllBytes(metadata.path());
        for (int offset : new int[] {0, 8, 16, 24, 40, 56, 4096, original.length - 1}) {
            byte[] changed = original.clone();
            changed[offset] ^= 1;
            rejectBoth(metadata, changed);
        }
        rejectBoth(metadata, Arrays.copyOf(original, original.length - 1));
        rejectBoth(metadata, Arrays.copyOf(original, original.length + 1));
        Files.write(metadata.path(), original);
        assertThatThrownBy(() -> SSTableVerifier.verify(metadata.path(), UUID.randomUUID(), manifest(metadata)))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    @Test void authenticatedMalformedDataRejectedByBoth() throws Exception {
        var metadata = table(3);
        byte[] original = Files.readAllBytes(metadata.path());
        BlockHandle data = firstData(original);
        for (Consumer<byte[]> mutation : List.<Consumer<byte[]>>of(
                raw -> raw[0] = 1,
                raw -> raw[1] = 127,
                raw -> { Arrays.fill(raw, 0, 5, (byte) 0x80); },
                raw -> little(raw).putInt(raw.length - 4, 0),
                raw -> little(raw).putInt(raw.length - 8, raw.length),
                raw -> raw[2] = (byte) 0xff)) {
            rejectBoth(metadata, mutateBlock(original, data, BlockKind.DATA, mutation));
        }
    }

    @Test void metadataCountsAndBloomFalseNegativesRejectedByBoth() throws Exception {
        var metadata = table(4);
        byte[] original = Files.readAllBytes(metadata.path());
        var footer = footer(original);
        rejectBoth(metadata, mutateBlock(original, footer.filter(), BlockKind.FILTER,
                raw -> Arrays.fill(raw, 24, raw.length, (byte) 0)));
        for (String property : List.of("aether.raw.key.bytes", "aether.raw.value.bytes",
                "aether.entry.count", "aether.smallest.sequence", "aether.largest.sequence")) {
            rejectBoth(metadata, mutateBlock(original, footer.properties(), BlockKind.PROPERTIES, raw -> {
                var entries = RestartBlock.decode(raw).stream().map(entry -> {
                    if (!new String(entry.key(), java.nio.charset.StandardCharsets.US_ASCII).equals(property))
                        return entry;
                    byte[] value = entry.value();
                    little(value).putLong(0, little(value).getLong(0) + 1);
                    return new RestartBlock.Entry(entry.key(), value);
                }).toList();
                byte[] encoded = RestartBlock.encode(entries, 1);
                // Preserve the builder's restart interval rather than changing block size.
                for (int interval = 1; interval <= entries.size(); interval++) {
                    encoded = RestartBlock.encode(entries, interval);
                    if (encoded.length == raw.length) break;
                }
                assertThat(encoded.length).isEqualTo(raw.length);
                System.arraycopy(encoded, 0, raw, 0, raw.length);
            }));
        }
    }

    @Test void scannerReconstructsKeysWithoutValueCopiesAndBoundsScratch() {
        byte[] first = new InternalKey(new byte[] {1}, 3, (byte) 1).encode();
        byte[] second = new InternalKey(new byte[] {2}, 2, (byte) 1).encode();
        var entries = List.of(new RestartBlock.Entry(first, new byte[200_000]),
                new RestartBlock.Entry(second, new byte[300_000]));
        byte[] raw = RestartBlock.encode(entries, 1, InternalKey::compareEncoded);
        var scanner = new RestartBlockScanner(raw);
        byte[] scratch = null;
        for (var entry : entries) {
            assertThat(scanner.next()).isTrue();
            if (scratch == null) scratch = scanner.keyBuffer();
            assertThat(scanner.keyBuffer()).isSameAs(scratch);
            assertThat(Arrays.copyOf(scanner.keyBuffer(), scanner.keyLength())).isEqualTo(entry.key());
            assertThat(scanner.valueLength()).isEqualTo(entry.value().length);
        }
        assertThat(scanner.next()).isFalse();
        assertThatThrownBy(scanner::keyBuffer).isInstanceOf(IllegalStateException.class);
    }

    @Test void authenticatedKeyOrderTypesAndLengthsRejected() throws Exception {
        var metadata = table(3);
        byte[] original = Files.readAllBytes(metadata.path());
        var handle = firstData(original);
        for (int scenario = 0; scenario < 6; scenario++) {
            final int mode = scenario;
            rejectBoth(metadata, mutateBlock(original, handle, BlockKind.DATA, raw -> {
                var scanner = new RestartBlockScanner(raw);
                assertThat(scanner.next()).isTrue();
                int firstKey = scanner.valueOffset() - scanner.keyLength();
                switch (mode) {
                    case 0 -> raw[firstKey + 3] = 127; // First key after subsequent keys.
                    case 1 -> raw[firstKey + 12] = 2; // Tombstone with a value.
                    case 2 -> raw[firstKey + 12] = 3; // Unknown value type.
                    case 3 -> Arrays.fill(raw, firstKey + 4, firstKey + 12, (byte) 0);
                    case 4 -> raw[1] = 8; // Internal key shorter than its trailer.
                    case 5 -> { raw[2] = (byte) 0xff; raw[3] = 0x7f; } // Value overruns block.
                    default -> throw new AssertionError();
                }
            }));
        }
    }

    @Test void authenticatedHeaderFooterAndEnvelopeFailures() throws Exception {
        var metadata = table(3);
        byte[] original = Files.readAllBytes(metadata.path());
        for (int offset : new int[] {0, 8, 16, 24, 40, 48, 56}) {
            byte[] changed = original.clone();
            changed[offset] ^= 1;
            // Header CRC covers the first 124 bytes.
            little(changed).putInt(124, io.aetherdb.format.checksum.MaskedCrc32c.masked(changed, 0, 124));
            rejectBoth(metadata, changed);
        }
        for (long offset : new long[] {0, original.length + 100L, Long.MAX_VALUE}) {
            byte[] changed = original.clone();
            int start = changed.length - 128;
            little(changed).putLong(start + 16, offset);
            little(changed).putInt(start + 124,
                    io.aetherdb.format.checksum.MaskedCrc32c.masked(changed, start, 124));
            rejectBoth(metadata, changed);
        }
        var data = firstData(original);
        byte[] changed = original.clone();
        byte[] payload = BlockEnvelope.decode(Arrays.copyOfRange(original, (int) data.offset(),
                (int) data.offset() + data.length()), BlockKind.DATA);
        byte[] wrongKind = BlockEnvelope.encode(payload, BlockKind.INDEX);
        System.arraycopy(wrongKind, 0, changed, (int) data.offset(), wrongKind.length);
        rejectBoth(metadata, changed);
    }

    @Test void rangeKeyHelpersMatchObjectComparator() {
        var random = new Random(20261003);
        for (int i = 0; i < 1000; i++) {
            byte[] user = new byte[random.nextInt(256)];
            random.nextBytes(user);
            var left = new InternalKey(user, 1 + random.nextInt(1000), (byte) (1 + random.nextInt(2)));
            var right = new InternalKey(user, 1 + random.nextInt(1000), (byte) (1 + random.nextInt(2)));
            byte[] a = new byte[left.encode().length + 10], b = new byte[right.encode().length + 10];
            System.arraycopy(left.encode(), 0, a, 3, left.encode().length);
            System.arraycopy(right.encode(), 0, b, 5, right.encode().length);
            assertThat(InternalKey.sequence(a, 3, left.encode().length)).isEqualTo(left.sequence());
            assertThat(Integer.signum(InternalKey.compareEncoded(a, 3, left.encode().length,
                    b, 5, right.encode().length))).isEqualTo(Integer.signum(left.compareTo(right)));
        }
    }

    @Test void approvedStricterRestartBoundaryValidation() {
        var entries = List.of(new RestartBlock.Entry(new byte[] {1}, new byte[10]),
                new RestartBlock.Entry(new byte[] {2}, new byte[10]));
        byte[] raw = RestartBlock.encode(entries, 1);
        little(raw).putInt(raw.length - 8, 4);
        assertThat(RestartBlock.decode(raw)).hasSize(2);
        assertThatThrownBy(() -> scan(raw)).isInstanceOf(SSTableCorruptionException.class)
                .hasMessageContaining("boundary");
        byte[] sharedRestart = RestartBlock.encode(List.of(
                new RestartBlock.Entry(new byte[] {1, 1}, new byte[0]),
                new RestartBlock.Entry(new byte[] {1, 2}, new byte[0])), 2);
        byte[] expanded = Arrays.copyOf(sharedRestart, sharedRestart.length + 4);
        int limit = sharedRestart.length - 8;
        little(expanded).putInt(limit, 0).putInt(limit + 4, 5).putInt(limit + 8, 2);
        assertThat(RestartBlock.decode(expanded)).hasSize(2);
        assertThatThrownBy(() -> scan(expanded)).isInstanceOf(SSTableCorruptionException.class)
                .hasMessageContaining("shared prefix");
    }

    @Test void seededScannerMutationsHaveOnlyControlledFailures() {
        var random = new Random(20261001);
        byte[] valid = RestartBlock.encode(List.of(
                new RestartBlock.Entry(new byte[] {1}, new byte[100]),
                new RestartBlock.Entry(new byte[] {2}, new byte[100])), 1);
        for (int iteration = 0; iteration < 5000; iteration++) {
            byte[] changed = valid.clone();
            for (int i = 0; i < 1 + iteration % 4; i++) changed[random.nextInt(changed.length)] = (byte) random.nextInt();
            try { scan(changed); } catch (SSTableCorruptionException expected) { /* Controlled rejection. */ }
        }
    }

    @Test void seededWholeFileMutationsHaveOnlyControlledFailures() throws Exception {
        var metadata = table(10);
        byte[] valid = Files.readAllBytes(metadata.path());
        var random = new Random(20261002);
        for (int i = 0; i < 200; i++) {
            byte[] changed = valid.clone();
            changed[random.nextInt(changed.length)] ^= 1;
            rejectBoth(metadata, changed);
        }
    }

    private static void scan(byte[] raw) {
        var scanner = new RestartBlockScanner(raw);
        int count = 0;
        while (scanner.next()) assertThat(++count).isLessThanOrEqualTo(raw.length);
    }

    private TableFileMetadata table(int count) throws Exception {
        var builder = new SSTableBuilder(directory.resolve("table.aesst"), 1, id, 1234);
        for (int i = 0; i < count; i++) builder.add(new InternalKey(ByteBuffer.allocate(4).putInt(i).array(),
                count - i, (byte) 1), new byte[1024]);
        return builder.finish();
    }

    private static ManifestFileMetadata manifest(TableFileMetadata m) {
        return new ManifestFileMetadata(m.fileNumber(), 0, m.fileSize(), m.entryCount(),
                m.smallestSequence(), m.largestSequence(), m.smallestInternalKey(), m.largestInternalKey());
    }

    private void rejectBoth(TableFileMetadata metadata, byte[] bytes) throws Exception {
        Files.write(metadata.path(), bytes);
        assertThatThrownBy(() -> { try (var reader = SSTableReader.open(metadata.path(), id, manifest(metadata))) { reader.metadata(); } })
                .isInstanceOfAny(SSTableCorruptionException.class, IllegalArgumentException.class);
        assertThatThrownBy(() -> SSTableVerifier.verify(metadata.path(), id, manifest(metadata)))
                .isInstanceOf(SSTableCorruptionException.class);
    }

    private static SSTableFooterV1 footer(byte[] file) {
        return SSTableFooterV1.decode(Arrays.copyOfRange(file, file.length - SSTableFooterV1.FOOTER_BYTES, file.length));
    }

    private static BlockHandle firstData(byte[] file) {
        var index = footer(file).index();
        byte[] raw = BlockEnvelope.decode(Arrays.copyOfRange(file, (int) index.offset(),
                (int) index.offset() + index.length()), BlockKind.INDEX);
        return BlockHandle.decode(RestartBlock.decode(raw, InternalKey::compareEncoded).getFirst().value());
    }

    private static byte[] mutateBlock(byte[] original, BlockHandle handle, BlockKind kind, Consumer<byte[]> mutation) {
        byte[] file = original.clone();
        byte[] raw = BlockEnvelope.decode(Arrays.copyOfRange(file, (int) handle.offset(),
                (int) handle.offset() + handle.length()), kind);
        mutation.accept(raw);
        byte[] encoded = BlockEnvelope.encode(raw, kind);
        System.arraycopy(encoded, 0, file, (int) handle.offset(), encoded.length);
        return file;
    }

    private static ByteBuffer little(byte[] raw) { return ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN); }
}
