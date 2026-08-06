package io.aetherdb.sstable;

import io.aetherdb.sstable.block.BlockEnvelope;
import io.aetherdb.sstable.block.BlockHandle;
import io.aetherdb.sstable.block.BlockKind;
import io.aetherdb.sstable.block.RestartBlock;
import io.aetherdb.sstable.filter.BloomFilterV1;
import java.io.ByteArrayOutputStream;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Builds one immutable SSTable v1 from strictly ordered internal entries. */
public final class SSTableBuilder {
    private static final Comparator<byte[]> INTERNAL_ORDER = (left, right) ->
            InternalKey.decode(left).compareTo(InternalKey.decode(right));
    private static final int MAX_RAW_BLOCK_BYTES = 32 * 1024 * 1024;
    private final Path path;
    private final long fileNumber;
    private final UUID databaseId;
    private final long creationEpochMillis;
    private final List<Entry> entries = new ArrayList<>();
    private State state = State.OPEN;

    /**
     * Creates a builder targeting a new temporary table file.
     *
     * @param path output path, which must not exist at finish time
     * @param fileNumber positive durable file number
     * @param databaseId owning database identity
     * @param creationEpochMillis diagnostic creation time
     */
    public SSTableBuilder(Path path, long fileNumber, UUID databaseId, long creationEpochMillis) {
        this.path = Objects.requireNonNull(path, "path");
        if (fileNumber <= 0) throw new IllegalArgumentException("file number must be positive");
        this.fileNumber = fileNumber;
        this.databaseId = Objects.requireNonNull(databaseId, "databaseId");
        this.creationEpochMillis = creationEpochMillis;
    }

    /**
     * Adds one strictly ordered internal entry.
     *
     * @param key internal key
     * @param value raw user value; tombstones require an empty value
     */
    public void add(InternalKey key, byte[] value) {
        requireOpen(); Objects.requireNonNull(key, "key"); Objects.requireNonNull(value, "value");
        byte[] encoded = key.encode();
        if (key.type() == 2 && value.length != 0) throw new IllegalArgumentException("tombstone value must be empty");
        if (!entries.isEmpty() && INTERNAL_ORDER.compare(entries.get(entries.size() - 1).key, encoded) >= 0) {
            throw new IllegalArgumentException("entries must be strictly ordered");
        }
        entries.add(new Entry(encoded, value.clone()));
    }

    /**
     * Writes, verifies, and closes the table.
     *
     * @return immutable table metadata
     * @throws IOException when exclusive file creation or writing fails
     */
    public TableFileMetadata finish() throws IOException {
        requireOpen();
        if (entries.isEmpty()) throw new IllegalStateException("normal SSTable cannot be empty");
        state = State.FINISHED;
        List<List<Entry>> partitions = partitionDataBlocks();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.writeBytes(new byte[SSTableHeaderV1.HEADER_REGION_BYTES]);
        List<BlockDescription> dataBlocks = new ArrayList<>();
        for (List<Entry> partition : partitions) {
            byte[] raw = RestartBlock.encode(toRestartEntries(partition), SSTableHeaderV1.RESTART_INTERVAL, INTERNAL_ORDER);
            dataBlocks.add(writeBlock(body, raw, BlockKind.DATA, partition.get(partition.size() - 1).key));
        }

        List<byte[]> userKeys = distinctUserKeys();
        BlockDescription filter = writeBlock(body, BloomFilterV1.build(userKeys), BlockKind.FILTER, null);
        Metrics metrics = metrics();
        byte[] propertiesPlaceholder = properties(metrics, 0);
        int propertiesPhysicalLength = propertiesPlaceholder.length + BlockEnvelope.TRAILER_BYTES;
        long propertiesOffset = body.size();
        long metaindexOffset = propertiesOffset + propertiesPhysicalLength;
        BlockHandle prospectiveProperties = new BlockHandle(propertiesOffset, propertiesPhysicalLength);
        byte[] prospectiveMetaindex = metaindex(filter.handle, prospectiveProperties);
        long indexOffset = metaindexOffset + prospectiveMetaindex.length + BlockEnvelope.TRAILER_BYTES;
        byte[] rawIndex = index(dataBlocks);
        long footerOffset = indexOffset + rawIndex.length + BlockEnvelope.TRAILER_BYTES;
        long finalSize = footerOffset + SSTableFooterV1.FOOTER_BYTES;

        BlockDescription properties = writeBlock(body, properties(metrics, finalSize), BlockKind.PROPERTIES, null);
        BlockDescription metaindex = writeBlock(body, metaindex(filter.handle, properties.handle), BlockKind.METAINDEX, null);
        BlockDescription index = writeBlock(body, rawIndex, BlockKind.INDEX, null);
        if (body.size() != footerOffset) throw new IllegalStateException("SSTable size planning diverged");
        SSTableFooterV1 footer = new SSTableFooterV1(metaindex.handle, index.handle, filter.handle,
                properties.handle, fileNumber, finalSize, databaseId);
        body.writeBytes(footer.encode());
        byte[] table = body.toByteArray();
        SSTableHeaderV1 header = new SSTableHeaderV1(fileNumber, databaseId, entries.size(),
                metrics.smallestSequence, metrics.largestSequence, dataBlocks.size(), creationEpochMillis, table.length);
        System.arraycopy(header.encode(), 0, table, 0, SSTableHeaderV1.HEADER_BYTES);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer bytes = ByteBuffer.wrap(table);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        }
        TableFileMetadata metadata = metadata(metrics, dataBlocks.size(), table.length);
        try (SSTableReader ignored = SSTableReader.open(path, metadata)) { ignored.verify(); }
        return metadata;
    }

    /** Abandons this builder without creating a file. */
    public void abandon() { if (state == State.OPEN) state = State.ABANDONED; }

    private List<List<Entry>> partitionDataBlocks() {
        List<List<Entry>> result = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        for (Entry entry : entries) {
            List<Entry> candidate = new ArrayList<>(current); candidate.add(entry);
            int size = RestartBlock.encode(toRestartEntries(candidate), SSTableHeaderV1.RESTART_INTERVAL, INTERNAL_ORDER).length;
            if (!current.isEmpty() && size > SSTableHeaderV1.TARGET_DATA_BLOCK_BYTES) {
                result.add(List.copyOf(current)); current.clear(); current.add(entry);
            } else current.add(entry);
            int raw = RestartBlock.encode(toRestartEntries(current), SSTableHeaderV1.RESTART_INTERVAL, INTERNAL_ORDER).length;
            if (raw > MAX_RAW_BLOCK_BYTES) throw new IllegalArgumentException("raw data block exceeds 32 MiB");
        }
        result.add(List.copyOf(current)); return result;
    }

    private static List<RestartBlock.Entry> toRestartEntries(List<Entry> source) {
        return source.stream().map(entry -> new RestartBlock.Entry(entry.key, entry.value)).toList();
    }

    private static BlockDescription writeBlock(ByteArrayOutputStream output, byte[] raw, BlockKind kind, byte[] largestKey) {
        byte[] physical = BlockEnvelope.encode(raw, kind);
        BlockHandle handle = new BlockHandle(output.size(), physical.length);
        output.writeBytes(physical); return new BlockDescription(handle, largestKey);
    }

    private byte[] index(List<BlockDescription> blocks) {
        List<RestartBlock.Entry> indexEntries = blocks.stream()
                .map(block -> new RestartBlock.Entry(block.largestKey, block.handle.encode())).toList();
        return RestartBlock.encode(indexEntries, 1, INTERNAL_ORDER);
    }

    private static byte[] metaindex(BlockHandle filter, BlockHandle properties) {
        return RestartBlock.encode(List.of(
                new RestartBlock.Entry(ascii("aether.filter.bloom.v1"), filter.encode()),
                new RestartBlock.Entry(ascii("aether.properties.v1"), properties.encode())), 1);
    }

    private byte[] properties(Metrics metrics, long fileSize) {
        Map<String, byte[]> values = new TreeMap<>();
        values.put("aether.comparator", ascii("aether.unsigned-bytewise.internal-v1"));
        values.put("aether.file.number", littleLong(fileNumber)); values.put("aether.file.size", littleLong(fileSize));
        values.put("aether.entry.count", littleLong(entries.size()));
        values.put("aether.data.block.count", littleInt(metrics.dataBlockCount));
        values.put("aether.raw.key.bytes", littleLong(metrics.rawKeyBytes));
        values.put("aether.raw.value.bytes", littleLong(metrics.rawValueBytes));
        values.put("aether.smallest.sequence", littleLong(metrics.smallestSequence));
        values.put("aether.largest.sequence", littleLong(metrics.largestSequence));
        values.put("aether.smallest.internal.key", entries.get(0).key);
        values.put("aether.largest.internal.key", entries.get(entries.size() - 1).key);
        values.put("aether.filter.policy", ascii("aether.bloom.full.v1:10:7"));
        values.put("aether.compression", ascii("none"));
        values.put("aether.creation.epoch.millis", littleLong(creationEpochMillis));
        List<RestartBlock.Entry> encoded = values.entrySet().stream()
                .map(entry -> new RestartBlock.Entry(ascii(entry.getKey()), entry.getValue())).toList();
        return RestartBlock.encode(encoded, 1);
    }

    private List<byte[]> distinctUserKeys() {
        Map<Key, byte[]> unique = new LinkedHashMap<>();
        for (Entry entry : entries) { byte[] user = InternalKey.decode(entry.key).userKey(); unique.putIfAbsent(new Key(user), user); }
        return List.copyOf(unique.values());
    }

    private Metrics metrics() {
        long smallest = Long.MAX_VALUE, largest = 0, keyBytes = 0, valueBytes = 0;
        for (Entry entry : entries) {
            long sequence = InternalKey.decode(entry.key).sequence();
            smallest = Math.min(smallest, sequence); largest = Math.max(largest, sequence);
            keyBytes = Math.addExact(keyBytes, entry.key.length); valueBytes = Math.addExact(valueBytes, entry.value.length);
        }
        return new Metrics(smallest, largest, keyBytes, valueBytes, partitionDataBlocks().size());
    }

    private TableFileMetadata metadata(Metrics metrics, int blocks, long size) {
        return new TableFileMetadata(path, databaseId, fileNumber, size, entries.size(), blocks,
                entries.get(0).key, entries.get(entries.size() - 1).key, metrics.smallestSequence,
                metrics.largestSequence, metrics.rawKeyBytes, metrics.rawValueBytes);
    }

    private void requireOpen() { if (state != State.OPEN) throw new IllegalStateException("builder is " + state); }
    private static byte[] ascii(String value) { return value.getBytes(StandardCharsets.US_ASCII); }
    private static byte[] littleLong(long value) { return ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(value).array(); }
    private static byte[] littleInt(int value) { return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array(); }
    private enum State { OPEN, FINISHED, ABANDONED }
    private record Entry(byte[] key, byte[] value) {}
    private record BlockDescription(BlockHandle handle, byte[] largestKey) {}
    private record Metrics(long smallestSequence, long largestSequence, long rawKeyBytes, long rawValueBytes, int dataBlockCount) {}
    private record Key(byte[] bytes) {
        private Key { bytes = bytes.clone(); }
        @Override public boolean equals(Object other) { return other instanceof Key key && Arrays.equals(bytes, key.bytes); }
        @Override public int hashCode() { return Arrays.hashCode(bytes); }
    }
}
