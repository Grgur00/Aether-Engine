package io.aetherdb.sstable;

import io.aetherdb.sstable.block.BlockHandle;
import io.aetherdb.sstable.block.BlockKind;
import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.block.RestartBlockScanner;
import io.aetherdb.sstable.filter.BloomFilterV1;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.UUID;

/** Sequential authoritative verification; DATA values are never materialized. */
public final class SSTableVerifier {
    private SSTableVerifier() {}

    /** Verifies a table before publication without opening a general-purpose reader. */
    public static VerificationResult verify(Path path, UUID databaseId, ManifestFileMetadata manifest)
            throws IOException {
        long started = System.nanoTime();
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long size = channel.size();
            if (size != manifest.fileSize()
                    || size < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES)
                throw corrupt("file size mismatch");
            var header = SSTableHeaderV1.decodeRegion(SSTableReader.readRange(channel, 0,
                    SSTableHeaderV1.HEADER_REGION_BYTES));
            var footer = SSTableFooterV1.decode(SSTableReader.readRange(channel,
                    size - SSTableFooterV1.FOOTER_BYTES, SSTableFooterV1.FOOTER_BYTES));
            footer.validateHandles();
            byte[] properties = SSTableReader.raw(channel, footer.properties(), BlockKind.PROPERTIES, size);
            var values = SSTableReader.bytewiseMap(RestartBlock.decode(properties));
            var expected = new TableFileMetadata(path, databaseId, manifest.fileNumber(), size,
                    manifest.entryCount(), header.dataBlockCount(), manifest.smallestInternalKey(),
                    manifest.largestInternalKey(), manifest.smallestSequence(), manifest.largestSequence(),
                    SSTableReader.propertyLong(values, "aether.raw.key.bytes"),
                    SSTableReader.propertyLong(values, "aether.raw.value.bytes"));
            SSTableReader.validateIdentity(path, expected, header, footer, size);
            SSTableReader.validateProperties(properties, expected, header);
            SSTableReader.validateMetaindex(SSTableReader.raw(channel, footer.metaindex(),
                    BlockKind.METAINDEX, size), footer);
            var filter = BloomFilterV1.decode(SSTableReader.raw(channel, footer.filter(), BlockKind.FILTER, size));
            var index = RestartBlock.decode(SSTableReader.raw(channel, footer.index(), BlockKind.INDEX, size),
                    (left, right) -> InternalKey.compareEncoded(left, 0, left.length, right, 0, right.length));
            if (index.isEmpty() || index.size() != header.dataBlockCount()) throw corrupt("index count mismatch");
            long bytesRead = SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES
                    + (long) footer.properties().length() + footer.metaindex().length()
                    + footer.filter().length() + footer.index().length();
            long previousEnd = SSTableHeaderV1.HEADER_REGION_BYTES;
            long count = 0, keyBytes = 0, valueBytes = 0, smallest = Long.MAX_VALUE, largest = 0;
            byte[] previous = new byte[128];
            int previousLength = 0;
            byte[] first = expected.smallestInternalKey(), last = expected.largestInternalKey();
            for (var indexEntry : index) {
                long beforeBlock = count;
                var handle = BlockHandle.decode(indexEntry.value());
                handle.validateWithin(size - SSTableFooterV1.FOOTER_BYTES);
                if (handle.offset() < previousEnd || handle.offset() + handle.length() > footer.filter().offset())
                    throw corrupt("data block handles overlap or leave data region");
                previousEnd = handle.offset() + handle.length();
                // v1 intentionally retains the existing checksummed envelope copy.
                var scanner = new RestartBlockScanner(SSTableReader.raw(channel, handle, BlockKind.DATA, size));
                bytesRead += handle.length();
                while (scanner.next()) {
                    byte[] key = scanner.keyBuffer();
                    int length = scanner.keyLength();
                    long sequence = InternalKey.sequence(key, 0, length);
                    if (key[length - 1] == 2 && scanner.valueLength() != 0) throw corrupt("tombstone has a value");
                    if (count > 0 && InternalKey.compareEncoded(previous, 0, previousLength, key, 0, length) >= 0)
                        throw corrupt("table entries are not strictly ordered");
                    if (count == 0 && !Arrays.equals(key, 0, length, first, 0, first.length))
                        throw corrupt("smallest key disagrees with metadata");
                    if (!filter.mayContain(key, 0, length - 9)) throw corrupt("Bloom filter false negative");
                    count++;
                    keyBytes += length;
                    valueBytes += scanner.valueLength();
                    smallest = Math.min(smallest, sequence);
                    largest = Math.max(largest, sequence);
                    if (previous.length < length) previous = new byte[length];
                    System.arraycopy(key, 0, previous, 0, length);
                    previousLength = length;
                }
                if (beforeBlock == 0 && count == 0) throw corrupt("empty first data block");
            }
            if (count != expected.entryCount() || keyBytes != expected.rawKeyBytes()
                    || valueBytes != expected.rawValueBytes() || smallest != expected.smallestSequence()
                    || largest != expected.largestSequence()
                    || !Arrays.equals(previous, 0, previousLength, last, 0, last.length))
                throw corrupt("observed table content disagrees with metadata");
            var result = new VerificationResult(count, keyBytes, valueBytes, smallest, largest, bytesRead,
                    index.size(), System.nanoTime() - started);
            SSTableVerificationTrace.streamed(size, result);
            return result;
        } catch (IllegalArgumentException | IndexOutOfBoundsException failure) {
            throw new SSTableCorruptionException("invalid table structure", failure);
        }
    }

    /** Observed content and actual requested read bytes (not physical-device I/O). */
    public record VerificationResult(long entryCount, long rawKeyBytes, long rawValueBytes,
            long smallestSequence, long largestSequence, long bytesRead, int dataBlockCount,
            long elapsedNs) {}

    private static SSTableCorruptionException corrupt(String message) {
        return new SSTableCorruptionException(message);
    }
}
