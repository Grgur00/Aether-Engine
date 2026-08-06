package io.aetherdb.sstable;

import io.aetherdb.sstable.block.BlockEnvelope;
import io.aetherdb.sstable.block.BlockHandle;
import io.aetherdb.sstable.block.BlockKind;
import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.filter.BloomFilterV1;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict reader and verifier for one immutable SSTable v1. */
public final class SSTableReader implements AutoCloseable {
    private static final Comparator<byte[]> INTERNAL_ORDER = (left, right) -> InternalKey.compareEncoded(left, right);
    private final Path path;
    private final TableFileMetadata expected;
    private final FileChannel channel;
    private final long fileSize;
    private final List<DataBlock> dataBlocks;
    private final byte[] filter;
    private boolean closed;

    private SSTableReader(Path path, TableFileMetadata expected, FileChannel channel, long fileSize,
                          SSTableFooterV1 footer, List<DataBlock> dataBlocks, byte[] filter) {
        this.path = path; this.expected = expected; this.channel = channel; this.fileSize = fileSize;
        this.dataBlocks = dataBlocks; this.filter = filter;
    }

    /**
     * Opens and fully validates a table against manifest metadata.
     *
     * @param path table path
     * @param expected authoritative file metadata
     * @return open reader
     * @throws IOException when file reading fails
     */
    public static SSTableReader open(Path path, TableFileMetadata expected) throws IOException {
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(expected, "expected");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = Files.size(path);
            if (fileSize != expected.fileSize() || fileSize < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES) {
                throw corrupt("table size does not match metadata");
            }
            byte[] headerRegion = readRange(channel, 0, SSTableHeaderV1.HEADER_REGION_BYTES);
            byte[] footerBytes = readRange(channel, fileSize - SSTableFooterV1.FOOTER_BYTES, SSTableFooterV1.FOOTER_BYTES);
            SSTableHeaderV1 header = SSTableHeaderV1.decodeRegion(headerRegion);
            SSTableFooterV1 footer = SSTableFooterV1.decode(footerBytes);
            validateIdentity(path, expected, header, footer, fileSize);
            footer.validateHandles();
            byte[] filter = raw(channel, footer.filter(), BlockKind.FILTER, fileSize);
            byte[] propertyRaw = raw(channel, footer.properties(), BlockKind.PROPERTIES, fileSize);
            byte[] metaindexRaw = raw(channel, footer.metaindex(), BlockKind.METAINDEX, fileSize);
            byte[] indexRaw = raw(channel, footer.index(), BlockKind.INDEX, fileSize);
            validateMetaindex(metaindexRaw, footer);
            validateProperties(propertyRaw, expected, header);
            List<DataBlock> blocks = decodeIndexAndData(channel, fileSize, footer, header, indexRaw);
            SSTableReader reader = new SSTableReader(path, expected, channel, fileSize, footer, blocks, filter);
            reader.verify();
            return reader;
        } catch (Throwable failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /**
     * Opens a table from the fields persisted in a manifest and verifies its complete contents.
     *
     * @param path canonical table path
     * @param databaseId expected owning database identity
     * @param manifest authoritative manifest metadata
     * @return fully verified reader
     * @throws IOException when file reading fails
     */
    public static SSTableReader open(Path path, java.util.UUID databaseId, ManifestFileMetadata manifest) throws IOException {
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(databaseId, "databaseId");
        Objects.requireNonNull(manifest, "manifest");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = Files.size(path);
            if (fileSize != manifest.fileSize() || fileSize < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES) {
                throw corrupt("table size does not match manifest");
            }
            byte[] headerRegion = readRange(channel, 0, SSTableHeaderV1.HEADER_REGION_BYTES);
            byte[] footerBytes = readRange(channel, fileSize - SSTableFooterV1.FOOTER_BYTES, SSTableFooterV1.FOOTER_BYTES);
            SSTableHeaderV1 header = SSTableHeaderV1.decodeRegion(headerRegion);
            SSTableFooterV1 footer = SSTableFooterV1.decode(footerBytes);
            Map<String, byte[]> properties = bytewiseMap(RestartBlock.decode(raw(channel, footer.properties(), BlockKind.PROPERTIES, fileSize)));
            TableFileMetadata expected = new TableFileMetadata(path, databaseId, manifest.fileNumber(), manifest.fileSize(),
                    manifest.entryCount(), header.dataBlockCount(), manifest.smallestInternalKey(), manifest.largestInternalKey(),
                    manifest.smallestSequence(), manifest.largestSequence(), propertyLong(properties, "aether.raw.key.bytes"),
                    propertyLong(properties, "aether.raw.value.bytes"));
            if (!Arrays.equals(manifest.smallestInternalKey(), expected.smallestInternalKey())
                    || !Arrays.equals(manifest.largestInternalKey(), expected.largestInternalKey())) {
                throw corrupt("manifest key bounds disagree with table metadata");
            }
            return open(path, expected);
        } catch (Throwable failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /**
     * Opens an unreferenced canonical table using its self-describing header and properties.
     *
     * @param path candidate table path
     * @param databaseId expected owning database identity
     * @return fully verified reader
     * @throws IOException when file reading fails
     */
    public static SSTableReader open(Path path, java.util.UUID databaseId) throws IOException {
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(databaseId, "databaseId");
        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            long fileSize = Files.size(path);
            if (fileSize < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES) throw corrupt("table is too short");
            byte[] headerRegion = readRange(channel, 0, SSTableHeaderV1.HEADER_REGION_BYTES);
            byte[] footerBytes = readRange(channel, fileSize - SSTableFooterV1.FOOTER_BYTES, SSTableFooterV1.FOOTER_BYTES);
            SSTableHeaderV1 header = SSTableHeaderV1.decodeRegion(headerRegion);
            SSTableFooterV1 footer = SSTableFooterV1.decode(footerBytes);
            Map<String, byte[]> properties = bytewiseMap(RestartBlock.decode(raw(channel, footer.properties(), BlockKind.PROPERTIES, fileSize)));
            TableFileMetadata expected = new TableFileMetadata(path, databaseId, header.fileNumber(), fileSize,
                    header.entryCount(), header.dataBlockCount(), propertyBytes(properties, "aether.smallest.internal.key"),
                    propertyBytes(properties, "aether.largest.internal.key"), header.smallestSequence(), header.largestSequence(),
                    propertyLong(properties, "aether.raw.key.bytes"), propertyLong(properties, "aether.raw.value.bytes"));
            return open(path, expected);
        } catch (Throwable failure) {
            try { channel.close(); } catch (IOException closeFailure) { failure.addSuppressed(closeFailure); }
            throw failure;
        }
    }

    /**
     * Looks up the newest version not newer than a visibility boundary.
     *
     * @param userKey logical user key
     * @param visibleSequence maximum visible sequence
     * @return found, tombstone, or absent result
     */
    public SSTableLookup lookup(byte[] userKey, long visibleSequence) {
        ensureOpen(); Objects.requireNonNull(userKey, "userKey");
        if (visibleSequence < 0) throw new IllegalArgumentException("visible sequence must be nonnegative");
        if (!BloomFilterV1.mayContain(filter, userKey)) return new SSTableLookup.Absent();
        int blockIndex = findCandidateBlock(userKey);
        if (blockIndex < 0) return new SSTableLookup.Absent();
        DataBlock block = dataBlocks.get(blockIndex);
        try {
            List<DataBlockEntry> entries = block.loadEntries(channel, fileSize);
            int start = binarySearch(entries, userKey);
            if (start < 0 || start >= entries.size()) return new SSTableLookup.Absent();
            while (start > 0 && sameUserKey(entries.get(start - 1).encodedKey(), userKey)) start--;
            for (int index = start; index < entries.size() && sameUserKey(entries.get(index).encodedKey(), userKey); index++) {
                long sequence = sequence(entries.get(index).encodedKey());
                if (sequence <= visibleSequence) {
                    return entries.get(index).value().length == 0
                            ? new SSTableLookup.Tombstone(sequence)
                            : new SSTableLookup.Found(sequence, entries.get(index).value());
                }
            }
        } catch (IOException failure) {
            throw new SSTableCorruptionException("cannot read data block", failure);
        }
        return new SSTableLookup.Absent();
    }

    /**
     * Materializes the strict internal iteration order.
     *
     * @return immutable copied entries
     */
    public List<SSTableEntry> entries() {
        ensureOpen();
        List<SSTableEntry> result = new ArrayList<>();
        for (DataBlock block : dataBlocks) {
            for (DataBlockEntry entry : block.loadEntriesUnchecked(channel, fileSize)) {
                result.add(new SSTableEntry(InternalKey.decode(entry.encodedKey()), entry.value()));
            }
        }
        return List.copyOf(result);
    }

    /** Performs complete structural and metadata consistency verification. */
    public void verify() {
        ensureOpen();
        long count = 0, keyBytes = 0, valueBytes = 0, smallest = Long.MAX_VALUE, largest = 0;
        byte[] previous = null;
        for (DataBlock block : dataBlocks) {
            for (DataBlockEntry entry : block.loadEntriesUnchecked(channel, fileSize)) {
                byte[] encoded = entry.encodedKey();
                InternalKey decoded = InternalKey.decode(encoded);
                if (previous != null && INTERNAL_ORDER.compare(previous, encoded) >= 0) throw corrupt("table entries are not strictly ordered");
                previous = encoded; count++; keyBytes += encoded.length; valueBytes += entry.value().length;
                smallest = Math.min(smallest, decoded.sequence()); largest = Math.max(largest, decoded.sequence());
                if (!BloomFilterV1.mayContain(filter, decoded.userKey())) throw corrupt("Bloom filter false negative");
            }
        }
        if (count != expected.entryCount() || keyBytes != expected.rawKeyBytes() || valueBytes != expected.rawValueBytes()
                || smallest != expected.smallestSequence() || largest != expected.largestSequence()
                || !Arrays.equals(dataBlocks.get(0).loadEntriesUnchecked(channel, fileSize).get(0).encodedKey(), expected.smallestInternalKey())
                || !Arrays.equals(previous, expected.largestInternalKey())) throw corrupt("observed table content disagrees with metadata");
    }

    /**
     * Returns the manifest metadata used during open.
     *
     * @return authoritative metadata used to open this reader
     */
    public TableFileMetadata metadata() { ensureOpen(); return expected; }

    /** Closes this reader and invalidates subsequent operations. */
    @Override public void close() throws IOException { closed = true; channel.close(); }

    private int findCandidateBlock(byte[] userKey) {
        int low = 0, high = dataBlocks.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            byte[] upperBound = dataBlocks.get(mid).upperBoundKey();
            int order = InternalKey.compareUserKey(upperBound, userKey);
            if (order < 0) low = mid + 1; else high = mid;
        }
        return low < dataBlocks.size() ? low : -1;
    }

    private static int binarySearch(List<DataBlockEntry> entries, byte[] userKey) {
        int low = 0, high = entries.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            byte[] encoded = entries.get(mid).encodedKey();
            int order = InternalKey.compareUserKey(encoded, userKey);
            if (order < 0) low = mid + 1; else high = mid;
        }
        return low;
    }

    private static boolean sameUserKey(byte[] encoded, byte[] userKey) {
        return InternalKey.compareUserKey(encoded, userKey) == 0;
    }

    private static long sequence(byte[] encoded) {
        int userLength = encoded.length - 9;
        return ByteBuffer.wrap(encoded, userLength, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private static List<DataBlock> decodeIndexAndData(FileChannel channel, long fileSize, SSTableFooterV1 footer,
                                                      SSTableHeaderV1 header, byte[] rawIndex) throws IOException {
        List<RestartBlock.Entry> index = RestartBlock.decode(rawIndex, INTERNAL_ORDER);
        if (index.size() != header.dataBlockCount() || index.isEmpty()) throw corrupt("index count mismatch");
        List<DataBlock> blocks = new ArrayList<>();
        long previousEnd = SSTableHeaderV1.HEADER_REGION_BYTES;
        for (RestartBlock.Entry indexEntry : index) {
            BlockHandle handle = BlockHandle.decode(indexEntry.value()); handle.validateWithin(fileSize - SSTableFooterV1.FOOTER_BYTES);
            if (handle.offset() < previousEnd || handle.offset() + handle.length() > footer.filter().offset()) {
                throw corrupt("data block handles overlap or leave data region");
            }
            blocks.add(new DataBlock(handle, indexEntry.key(), null)); previousEnd = handle.offset() + handle.length();
        }
        return List.copyOf(blocks);
    }

    private static void validateMetaindex(byte[] raw, SSTableFooterV1 footer) {
        Map<String, byte[]> entries = bytewiseMap(RestartBlock.decode(raw));
        requireHandle(entries, "aether.filter.bloom.v1", footer.filter());
        requireHandle(entries, "aether.properties.v1", footer.properties());
    }

    private static void validateProperties(byte[] raw, TableFileMetadata expected, SSTableHeaderV1 header) {
        Map<String, byte[]> values = bytewiseMap(RestartBlock.decode(raw));
        requireAscii(values, "aether.comparator", "aether.unsigned-bytewise.internal-v1");
        requireLong(values, "aether.file.number", expected.fileNumber()); requireLong(values, "aether.file.size", expected.fileSize());
        requireLong(values, "aether.entry.count", expected.entryCount()); requireInt(values, "aether.data.block.count", expected.dataBlockCount());
        requireLong(values, "aether.raw.key.bytes", expected.rawKeyBytes()); requireLong(values, "aether.raw.value.bytes", expected.rawValueBytes());
        requireLong(values, "aether.smallest.sequence", expected.smallestSequence()); requireLong(values, "aether.largest.sequence", expected.largestSequence());
        requireBytes(values, "aether.smallest.internal.key", expected.smallestInternalKey());
        requireBytes(values, "aether.largest.internal.key", expected.largestInternalKey());
        requireAscii(values, "aether.filter.policy", "aether.bloom.full.v1:10:7"); requireAscii(values, "aether.compression", "none");
        requireLong(values, "aether.creation.epoch.millis", header.creationEpochMillis());
    }

    private static Map<String, byte[]> bytewiseMap(List<RestartBlock.Entry> entries) {
        Map<String, byte[]> values = new HashMap<>();
        for (RestartBlock.Entry entry : entries) {
            String key = new String(entry.key(), StandardCharsets.US_ASCII);
            if (values.put(key, entry.value()) != null) throw corrupt("duplicate metadata key");
        }
        return values;
    }

    private static byte[] raw(FileChannel channel, BlockHandle handle, BlockKind kind, long fileSize) throws IOException {
        handle.validateWithin(fileSize - SSTableFooterV1.FOOTER_BYTES);
        int start;
        try { start = Math.toIntExact(handle.offset()); }
        catch (ArithmeticException failure) { throw corrupt("block offset exceeds addressable file"); }
        byte[] block = readRange(channel, start, handle.length());
        return BlockEnvelope.decode(block, kind);
    }

    private static byte[] readRange(FileChannel channel, long offset, int length) throws IOException {
        byte[] result = new byte[length];
        ByteBuffer bytes = ByteBuffer.wrap(result);
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset + bytes.position());
            if (read < 0) throw new EOFException();
        }
        return result;
    }

    private static void validateIdentity(Path path, TableFileMetadata expected, SSTableHeaderV1 header,
                                         SSTableFooterV1 footer, long fileSize) {
        if (header.fileNumber() != expected.fileNumber() || footer.fileNumber() != expected.fileNumber()
                || header.fileSize() != fileSize || footer.fileSize() != fileSize
                || !header.databaseId().equals(expected.databaseId()) || !footer.databaseId().equals(expected.databaseId())
                || header.entryCount() != expected.entryCount() || header.dataBlockCount() != expected.dataBlockCount()
                || header.smallestSequence() != expected.smallestSequence() || header.largestSequence() != expected.largestSequence()) {
            throw corrupt("header/footer/manifest identity mismatch for " + path);
        }
    }

    private static void requireHandle(Map<String, byte[]> values, String key, BlockHandle expected) {
        byte[] encoded = values.get(key); if (encoded == null || !BlockHandle.decode(encoded).equals(expected)) throw corrupt("missing or inconsistent " + key);
    }
    private static void requireAscii(Map<String, byte[]> values, String key, String expected) { requireBytes(values, key, expected.getBytes(StandardCharsets.US_ASCII)); }
    private static void requireLong(Map<String, byte[]> values, String key, long expected) {
        byte[] value = values.get(key); if (value == null || value.length != 8 || ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getLong() != expected) throw corrupt("invalid property " + key);
    }
    private static long propertyLong(Map<String, byte[]> values, String key) {
        byte[] value = values.get(key);
        if (value == null || value.length != 8) throw corrupt("invalid property " + key);
        return ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }
    private static byte[] propertyBytes(Map<String, byte[]> values, String key) {
        byte[] value = values.get(key); if (value == null) throw corrupt("missing property " + key); return value.clone();
    }
    private static void requireInt(Map<String, byte[]> values, String key, int expected) {
        byte[] value = values.get(key); if (value == null || value.length != 4 || ByteBuffer.wrap(value).order(ByteOrder.LITTLE_ENDIAN).getInt() != expected) throw corrupt("invalid property " + key);
    }
    private static void requireBytes(Map<String, byte[]> values, String key, byte[] expected) {
        if (!Arrays.equals(values.get(key), expected)) throw corrupt("invalid property " + key);
    }
    private void ensureOpen() { if (closed) throw new IllegalStateException("SSTable reader is closed: " + path); }
    private static SSTableCorruptionException corrupt(String message) { return new SSTableCorruptionException(message); }
    private static final class DataBlock {
        private final BlockHandle handle;
        private final byte[] upperBoundKey;
        private volatile List<DataBlockEntry> entries;

        private DataBlock(BlockHandle handle, byte[] upperBoundKey, List<DataBlockEntry> entries) {
            this.handle = handle; this.upperBoundKey = upperBoundKey; this.entries = entries;
        }

        private List<DataBlockEntry> loadEntries(FileChannel channel, long fileSize) throws IOException {
            if (entries == null) {
                synchronized (this) {
                    if (entries == null) {
                        byte[] rawBlock = raw(channel, handle, BlockKind.DATA, fileSize);
                        List<RestartBlock.Entry> decoded = RestartBlock.decode(rawBlock, INTERNAL_ORDER);
                        List<DataBlockEntry> converted = new ArrayList<>(decoded.size());
                        for (RestartBlock.Entry entry : decoded) {
                            InternalKey key = InternalKey.decode(entry.key());
                            if (key.type() == 2 && entry.value().length != 0) throw corrupt("tombstone has a value");
                            converted.add(new DataBlockEntry(entry.key(), entry.value()));
                        }
                        entries = List.copyOf(converted);
                    }
                }
            }
            return entries;
        }

        private List<DataBlockEntry> loadEntriesUnchecked(FileChannel channel, long fileSize) {
            try { return loadEntries(channel, fileSize); } catch (IOException failure) { throw new IllegalStateException(failure); }
        }

        private byte[] upperBoundKey() { return upperBoundKey; }
    }

    private record DataBlockEntry(byte[] encodedKey, byte[] value) {}
}
