package io.aetherdb.sstable;

import io.aetherdb.sstable.block.BlockEnvelope;
import io.aetherdb.sstable.block.BlockHandle;
import io.aetherdb.sstable.block.BlockKind;
import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.filter.BloomFilterV1;
import io.aetherdb.sstable.manifest.ManifestFileMetadata;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Strict reader and verifier for one immutable SSTable v1. */
public final class SSTableReader implements AutoCloseable {
    private static final Comparator<byte[]> INTERNAL_ORDER = (left, right) ->
            InternalKey.decode(left).compareTo(InternalKey.decode(right));
    private final Path path;
    private final TableFileMetadata expected;
    private final byte[] file;
    private final SSTableHeaderV1 header;
    private final SSTableFooterV1 footer;
    private final List<DataBlock> dataBlocks;
    private final byte[] filter;
    private boolean closed;

    private SSTableReader(Path path, TableFileMetadata expected, byte[] file, SSTableHeaderV1 header,
                          SSTableFooterV1 footer, List<DataBlock> dataBlocks, byte[] filter) {
        this.path = path; this.expected = expected; this.file = file; this.header = header;
        this.footer = footer; this.dataBlocks = dataBlocks; this.filter = filter;
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
        byte[] file = Files.readAllBytes(path);
        if (file.length != expected.fileSize() || file.length < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES) {
            throw corrupt("table size does not match metadata");
        }
        SSTableHeaderV1 header = SSTableHeaderV1.decodeRegion(Arrays.copyOf(file, SSTableHeaderV1.HEADER_REGION_BYTES));
        SSTableFooterV1 footer = SSTableFooterV1.decode(Arrays.copyOfRange(file,
                file.length - SSTableFooterV1.FOOTER_BYTES, file.length));
        validateIdentity(path, expected, header, footer, file.length);
        footer.validateHandles();
        byte[] filter = raw(file, footer.filter(), BlockKind.FILTER);
        byte[] propertyRaw = raw(file, footer.properties(), BlockKind.PROPERTIES);
        byte[] metaindexRaw = raw(file, footer.metaindex(), BlockKind.METAINDEX);
        byte[] indexRaw = raw(file, footer.index(), BlockKind.INDEX);
        validateMetaindex(metaindexRaw, footer);
        validateProperties(propertyRaw, expected, header);
        List<DataBlock> blocks = decodeIndexAndData(file, indexRaw, footer, header);
        SSTableReader reader = new SSTableReader(path, expected, file, header, footer, blocks, filter);
        reader.verify();
        return reader;
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
        byte[] file = Files.readAllBytes(path);
        if (file.length != manifest.fileSize() || file.length < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES) {
            throw corrupt("table size does not match manifest");
        }
        SSTableHeaderV1 header = SSTableHeaderV1.decodeRegion(Arrays.copyOf(file, SSTableHeaderV1.HEADER_REGION_BYTES));
        SSTableFooterV1 footer = SSTableFooterV1.decode(Arrays.copyOfRange(file,
                file.length - SSTableFooterV1.FOOTER_BYTES, file.length));
        Map<String, byte[]> properties = bytewiseMap(RestartBlock.decode(raw(file, footer.properties(), BlockKind.PROPERTIES)));
        TableFileMetadata expected = new TableFileMetadata(path, databaseId, manifest.fileNumber(), manifest.fileSize(),
                manifest.entryCount(), header.dataBlockCount(), manifest.smallestInternalKey(), manifest.largestInternalKey(),
                manifest.smallestSequence(), manifest.largestSequence(), propertyLong(properties, "aether.raw.key.bytes"),
                propertyLong(properties, "aether.raw.value.bytes"));
        if (!Arrays.equals(manifest.smallestInternalKey(), expected.smallestInternalKey())
                || !Arrays.equals(manifest.largestInternalKey(), expected.largestInternalKey())) {
            throw corrupt("manifest key bounds disagree with table metadata");
        }
        return open(path, expected);
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
        Objects.requireNonNull(path, "path"); Objects.requireNonNull(databaseId, "databaseId"); byte[] file = Files.readAllBytes(path);
        if (file.length < SSTableHeaderV1.HEADER_REGION_BYTES + SSTableFooterV1.FOOTER_BYTES) throw corrupt("table is too short");
        SSTableHeaderV1 header = SSTableHeaderV1.decodeRegion(Arrays.copyOf(file, SSTableHeaderV1.HEADER_REGION_BYTES));
        SSTableFooterV1 footer = SSTableFooterV1.decode(Arrays.copyOfRange(file,
                file.length - SSTableFooterV1.FOOTER_BYTES, file.length));
        Map<String, byte[]> properties = bytewiseMap(RestartBlock.decode(raw(file, footer.properties(), BlockKind.PROPERTIES)));
        TableFileMetadata expected = new TableFileMetadata(path, databaseId, header.fileNumber(), file.length,
                header.entryCount(), header.dataBlockCount(), propertyBytes(properties, "aether.smallest.internal.key"),
                propertyBytes(properties, "aether.largest.internal.key"), header.smallestSequence(), header.largestSequence(),
                propertyLong(properties, "aether.raw.key.bytes"), propertyLong(properties, "aether.raw.value.bytes"));
        return open(path, expected);
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
        for (DataBlock block : dataBlocks) for (SSTableEntry entry : block.entries) {
            InternalKey key = entry.key();
            int order = Arrays.compareUnsigned(key.userKey(), userKey);
            if (order > 0) return new SSTableLookup.Absent();
            if (order == 0 && key.sequence() <= visibleSequence) {
                return key.type() == 2 ? new SSTableLookup.Tombstone(key.sequence())
                        : new SSTableLookup.Found(key.sequence(), entry.value());
            }
        }
        return new SSTableLookup.Absent();
    }

    /**
     * Materializes the strict internal iteration order.
     *
     * @return immutable copied entries
     */
    public List<SSTableEntry> entries() {
        ensureOpen(); List<SSTableEntry> result = new ArrayList<>();
        for (DataBlock block : dataBlocks) for (SSTableEntry entry : block.entries) result.add(new SSTableEntry(entry.key(), entry.value()));
        return List.copyOf(result);
    }

    /** Performs complete structural and metadata consistency verification. */
    public void verify() {
        ensureOpen();
        long count = 0, keyBytes = 0, valueBytes = 0, smallest = Long.MAX_VALUE, largest = 0;
        byte[] previous = null;
        for (DataBlock block : dataBlocks) {
            for (SSTableEntry entry : block.entries) {
                byte[] encoded = entry.key().encode();
                if (previous != null && INTERNAL_ORDER.compare(previous, encoded) >= 0) throw corrupt("table entries are not strictly ordered");
                previous = encoded; count++; keyBytes += encoded.length; valueBytes += entry.value().length;
                smallest = Math.min(smallest, entry.key().sequence()); largest = Math.max(largest, entry.key().sequence());
                if (!BloomFilterV1.mayContain(filter, entry.key().userKey())) throw corrupt("Bloom filter false negative");
            }
        }
        if (count != expected.entryCount() || keyBytes != expected.rawKeyBytes() || valueBytes != expected.rawValueBytes()
                || smallest != expected.smallestSequence() || largest != expected.largestSequence()
                || !Arrays.equals(dataBlocks.get(0).entries.get(0).key().encode(), expected.smallestInternalKey())
                || !Arrays.equals(previous, expected.largestInternalKey())) throw corrupt("observed table content disagrees with metadata");
    }

    /**
     * Returns the manifest metadata used during open.
     *
     * @return authoritative metadata used to open this reader
     */
    public TableFileMetadata metadata() { ensureOpen(); return expected; }

    /** Closes this reader and invalidates subsequent operations. */
    @Override public void close() { closed = true; }

    private static List<DataBlock> decodeIndexAndData(byte[] file, byte[] rawIndex,
                                                       SSTableFooterV1 footer, SSTableHeaderV1 header) {
        List<RestartBlock.Entry> index = RestartBlock.decode(rawIndex, INTERNAL_ORDER);
        if (index.size() != header.dataBlockCount() || index.isEmpty()) throw corrupt("index count mismatch");
        List<DataBlock> blocks = new ArrayList<>();
        long previousEnd = SSTableHeaderV1.HEADER_REGION_BYTES;
        for (RestartBlock.Entry indexEntry : index) {
            BlockHandle handle = BlockHandle.decode(indexEntry.value()); handle.validateWithin(file.length - SSTableFooterV1.FOOTER_BYTES);
            if (handle.offset() < previousEnd || handle.offset() + handle.length() > footer.filter().offset()) {
                throw corrupt("data block handles overlap or leave data region");
            }
            List<RestartBlock.Entry> rawEntries = RestartBlock.decode(raw(file, handle, BlockKind.DATA), INTERNAL_ORDER);
            if (rawEntries.isEmpty() || !Arrays.equals(rawEntries.get(rawEntries.size() - 1).key(), indexEntry.key())) {
                throw corrupt("index upper bound does not match data block");
            }
            List<SSTableEntry> entries = rawEntries.stream().map(entry -> {
                InternalKey key = InternalKey.decode(entry.key());
                if (key.type() == 2 && entry.value().length != 0) throw corrupt("tombstone has a value");
                return new SSTableEntry(key, entry.value());
            }).toList();
            blocks.add(new DataBlock(handle, entries)); previousEnd = handle.offset() + handle.length();
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

    private static byte[] raw(byte[] file, BlockHandle handle, BlockKind kind) {
        handle.validateWithin(file.length - SSTableFooterV1.FOOTER_BYTES);
        int start;
        try { start = Math.toIntExact(handle.offset()); }
        catch (ArithmeticException failure) { throw corrupt("block offset exceeds addressable file"); }
        return BlockEnvelope.decode(Arrays.copyOfRange(file, start, start + handle.length()), kind);
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
    private record DataBlock(BlockHandle handle, List<SSTableEntry> entries) {}
}
