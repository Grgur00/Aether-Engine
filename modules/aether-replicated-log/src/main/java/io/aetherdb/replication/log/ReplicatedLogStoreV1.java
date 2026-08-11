package io.aetherdb.replication.log;

import io.aetherdb.io.DatabaseLock;
import io.aetherdb.io.PathSecurityValidator;
import io.aetherdb.replication.api.ReplicatedEntryType;
import io.aetherdb.replication.api.ReplicatedLogEntry;
import io.aetherdb.replication.api.ReplicatedLogStore;
import io.aetherdb.replication.api.ReplicatedLogStoreIdentity;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

/** Durable Chapter 18 replicated-log store with exact v1 records and guarded suffix truncation. */
public final class ReplicatedLogStoreV1 implements ReplicatedLogStore {
    private static final String IDENTITY_FILE = "RLOG-IDENTITY";
    private final Path root;
    private final ReplicatedLogIdentityV1 durableIdentity;
    private final ReplicatedLogStoreIdentity identity;
    private final DatabaseLock lock;
    private final NavigableMap<Long, Location> index = new TreeMap<>();
    private FileChannel active;
    private Path activePath;
    private long activeSegmentNumber;
    private long nextSegmentNumber;
    private long durableIndex;
    private long lastStateSequence;
    private Throwable failure;
    private boolean closed;

    /**
     * Opens or creates a process-exclusive replicated log bound to the supplied cluster and node.
     */
    public static ReplicatedLogStoreV1 open(Path requested, UUID clusterId, UUID nodeId) {
        DatabaseLock lock = null;
        ReplicatedLogStoreV1 store = null;
        try {
            Path absolute = requested.toAbsolutePath().normalize();
            Files.createDirectories(absolute);
            Path root = PathSecurityValidator.validateRoot(absolute, true);
            lock = DatabaseLock.acquire(root);
            ReplicatedLogIdentityV1 durable = openIdentity(root, clusterId, nodeId);
            store = new ReplicatedLogStoreV1(root, durable, lock);
            lock = null;
            store.recover();
            return store;
        } catch (Throwable problem) {
            if (store != null) closeSuppressed(store, problem);
            else closeSuppressed(lock, problem);
            throw failure("cannot open replicated log: " + requested, problem);
        }
    }

    private ReplicatedLogStoreV1(
            Path root, ReplicatedLogIdentityV1 durableIdentity, DatabaseLock lock) {
        this.root = root;
        this.durableIdentity = durableIdentity;
        this.lock = lock;
        identity =
                new ReplicatedLogStoreIdentity(
                        durableIdentity.clusterId(), durableIdentity.nodeId());
    }

    @Override
    public synchronized ReplicatedLogStoreIdentity identity() {
        ensureOpen();
        return identity;
    }

    @Override
    public synchronized long firstIndex() {
        ensureOpen();
        return index.isEmpty() ? 0 : index.firstKey();
    }

    @Override
    public synchronized long lastIndex() {
        ensureOpen();
        return index.isEmpty() ? 0 : index.lastKey();
    }

    @Override
    public synchronized long lastTerm() {
        ensureOpen();
        return index.isEmpty() ? 0 : index.lastEntry().getValue().term;
    }

    @Override
    public synchronized long lastStateSequence() {
        ensureOpen();
        return lastStateSequence;
    }

    @Override
    public synchronized long durableIndex() {
        ensureOpen();
        return durableIndex;
    }

    @Override
    public synchronized void append(List<ReplicatedLogEntry> entries) {
        appendInternal(entries, false);
    }

    @Override
    public synchronized void appendAndForce(List<ReplicatedLogEntry> entries) {
        appendInternal(entries, true);
    }

    private void appendInternal(List<ReplicatedLogEntry> entries, boolean force) {
        ensureWritable();
        if (entries == null || entries.isEmpty())
            throw new IllegalArgumentException("append requires entries");
        List<ReplicatedLogEntry> owned = List.copyOf(entries);
        List<byte[]> records = new ArrayList<>(owned.size());
        long expectedIndex = lastIndexRaw() + 1,
                previousTerm = lastTermRaw(),
                state = lastStateSequence;
        byte[] previousHash = lastHashRaw();
        for (ReplicatedLogEntry entry : owned) {
            validateNext(entry, expectedIndex, previousTerm, state, previousHash);
            byte[] record = ReplicatedLogEntryCodecV1.encode(entry);
            records.add(record);
            expectedIndex++;
            previousTerm = entry.term();
            state = entry.stateSequenceAfter();
            previousHash = entry.entryHash();
        }
        try {
            for (int position = 0; position < owned.size(); position++)
                appendOne(owned.get(position), records.get(position));
            if (force) forceThrough(lastIndexRaw());
        } catch (Throwable problem) {
            failure = problem;
            throw failure("replicated-log append failed", problem);
        }
    }

    private void appendOne(ReplicatedLogEntry entry, byte[] record) throws IOException {
        long size = active.size();
        if (size > ReplicatedLogFormatV1.SEGMENT_HEADER_REGION_BYTES
                && size + record.length > ReplicatedLogFormatV1.TARGET_SEGMENT_BYTES) rotate();
        size = active.size();
        if (size + record.length > ReplicatedLogFormatV1.HARD_SEGMENT_BYTES) {
            throw new IOException("replicated segment hard limit exceeded");
        }
        long offset = size;
        writeFully(active, ByteBuffer.wrap(record), offset);
        index.put(
                entry.index(),
                location(activePath, activeSegmentNumber, offset, record.length, entry));
        lastStateSequence = entry.stateSequenceAfter();
    }

    @Override
    public synchronized void forceThrough(long requestedIndex) {
        ensureWritable();
        if (requestedIndex < 0 || requestedIndex > lastIndexRaw())
            throw new IllegalArgumentException("force index is not appended");
        if (requestedIndex <= durableIndex) return;
        try {
            active.force(true);
            durableIndex = lastIndexRaw();
        } catch (IOException problem) {
            failure = problem;
            throw failure("replicated-log force failed", problem);
        }
    }

    @Override
    public synchronized ReplicatedLogEntry read(long requestedIndex) {
        ensureOpen();
        Location location = index.get(requestedIndex);
        if (location == null)
            throw new IllegalArgumentException(
                    "replicated log index is not retained: " + requestedIndex);
        try {
            return readLocation(location);
        } catch (IOException problem) {
            throw failure("replicated-log read failed", problem);
        }
    }

    @Override
    public synchronized List<ReplicatedLogEntry> readRange(
            long startInclusive, long endExclusive, long byteLimit, int entryLimit) {
        ensureOpen();
        if (startInclusive < 1
                || endExclusive < startInclusive
                || byteLimit < 1
                || entryLimit < 1) {
            throw new IllegalArgumentException("invalid replicated-log range limits");
        }
        List<ReplicatedLogEntry> result = new ArrayList<>();
        long bytes = 0;
        for (Location location : index.subMap(startInclusive, true, endExclusive, false).values()) {
            if (result.size() == entryLimit
                    || !result.isEmpty() && bytes + location.length > byteLimit) break;
            try {
                result.add(readLocation(location));
            } catch (IOException problem) {
                throw failure("replicated-log range read failed", problem);
            }
            bytes += location.length;
            if (bytes >= byteLimit) break;
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized long termAt(long requestedIndex) {
        ensureOpen();
        Location location = requiredLocation(requestedIndex);
        return location.term;
    }

    @Override
    public synchronized byte[] entryHashAt(long requestedIndex) {
        ensureOpen();
        return requiredLocation(requestedIndex).entryHash.clone();
    }

    @Override
    public synchronized void truncateSuffix(long fromIndex, long commitIndex, long appliedIndex) {
        ensureWritable();
        long last = lastIndexRaw();
        if (fromIndex < 1
                || fromIndex > last + 1
                || commitIndex < 0
                || appliedIndex < 0
                || fromIndex <= commitIndex
                || fromIndex <= appliedIndex) {
            throw new IllegalArgumentException("suffix truncation crosses commit/applied boundary");
        }
        if (fromIndex == last + 1) return;
        Location firstRemoved = requiredLocation(fromIndex);
        List<Path> laterPaths =
                index.tailMap(fromIndex, true).values().stream()
                        .map(Location::path)
                        .distinct()
                        .filter(path -> !path.equals(firstRemoved.path))
                        .toList();
        try {
            active.close();
            for (Path path : laterPaths) Files.deleteIfExists(path);
            try (FileChannel containing =
                    FileChannel.open(firstRemoved.path, StandardOpenOption.WRITE)) {
                containing.truncate(firstRemoved.offset);
                containing.force(true);
            }
            index.tailMap(fromIndex, true).clear();
            syncDirectory(root);
            activePath = firstRemoved.path;
            activeSegmentNumber = firstRemoved.segmentNumber;
            active =
                    FileChannel.open(activePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
            active.position(active.size());
            durableIndex = lastIndexRaw();
            lastStateSequence =
                    index.isEmpty() ? 0 : index.lastEntry().getValue().stateSequenceAfter;
        } catch (Throwable problem) {
            failure = problem;
            throw failure("replicated-log suffix truncation failed", problem);
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        Throwable problem = null;
        try {
            if (active != null && active.isOpen()) {
                active.force(true);
                active.close();
            }
        } catch (Throwable closeFailure) {
            problem = closeFailure;
        }
        try {
            lock.close();
        } catch (Throwable closeFailure) {
            if (problem == null) problem = closeFailure;
            else problem.addSuppressed(closeFailure);
        }
        if (problem != null) throw failure("replicated-log close failed", problem);
    }

    private void recover() throws IOException {
        List<Path> segments = discoverSegments();
        if (segments.isEmpty()) {
            createSegment(1, 1, 0, 0, new byte[32]);
            nextSegmentNumber = 2;
            return;
        }
        long expectedIndex = 1, previousTerm = 0, state = 0;
        byte[] previousHash = new byte[32];
        long maximumSegment = 0;
        for (int segmentIndex = 0; segmentIndex < segments.size(); segmentIndex++) {
            Path path = segments.get(segmentIndex);
            long number = segmentNumber(path);
            maximumSegment = Math.max(maximumSegment, number);
            boolean lastSegment = segmentIndex == segments.size() - 1;
            try (FileChannel channel =
                    FileChannel.open(
                            path,
                            StandardOpenOption.READ,
                            lastSegment ? StandardOpenOption.WRITE : StandardOpenOption.READ)) {
                if (channel.size() < ReplicatedLogFormatV1.SEGMENT_HEADER_REGION_BYTES)
                    throw new IOException(
                            "truncated replicated segment header: " + path.getFileName());
                byte[] region =
                        readFully(channel, 0, ReplicatedLogFormatV1.SEGMENT_HEADER_REGION_BYTES);
                ReplicatedLogSegmentHeaderV1 header =
                        ReplicatedLogSegmentHeaderV1.decodeRegion(
                                region,
                                durableIdentity.clusterId(),
                                durableIdentity.nodeId(),
                                number);
                if (header.firstIndex() != expectedIndex
                        || header.previousIndex() != expectedIndex - 1
                        || header.previousTerm() != previousTerm
                        || !Arrays.equals(header.previousEntryHash(), previousHash)) {
                    throw new IOException(
                            "replicated segment chain mismatch: " + path.getFileName());
                }
                long offset = ReplicatedLogFormatV1.SEGMENT_HEADER_REGION_BYTES;
                int entries = 0;
                while (offset < channel.size()) {
                    long remaining = channel.size() - offset;
                    if (remaining < ReplicatedLogFormatV1.ENTRY_HEADER_BYTES) {
                        if (!lastSegment)
                            throw new IOException("incomplete interior replicated entry header");
                        channel.truncate(offset);
                        channel.force(true);
                        break;
                    }
                    byte[] entryHeader =
                            readFully(channel, offset, ReplicatedLogFormatV1.ENTRY_HEADER_BYTES);
                    int recordLength = validatedRecordLength(entryHeader);
                    if (remaining < recordLength) {
                        if (!lastSegment)
                            throw new IOException("incomplete interior replicated entry record");
                        channel.truncate(offset);
                        channel.force(true);
                        break;
                    }
                    ReplicatedLogEntry entry =
                            ReplicatedLogEntryCodecV1.decode(
                                    readFully(channel, offset, recordLength));
                    validateNext(entry, expectedIndex, previousTerm, state, previousHash);
                    index.put(entry.index(), location(path, number, offset, recordLength, entry));
                    expectedIndex++;
                    previousTerm = entry.term();
                    state = entry.stateSequenceAfter();
                    previousHash = entry.entryHash();
                    offset += recordLength;
                    entries++;
                }
                if (!lastSegment && entries == 0)
                    throw new IOException("empty nonfinal replicated segment");
            }
        }
        activePath = segments.get(segments.size() - 1);
        activeSegmentNumber = segmentNumber(activePath);
        active = FileChannel.open(activePath, StandardOpenOption.READ, StandardOpenOption.WRITE);
        active.position(active.size());
        nextSegmentNumber = Math.addExact(maximumSegment, 1);
        durableIndex = lastIndexRaw();
        lastStateSequence = state;
    }

    private List<Path> discoverSegments() throws IOException {
        List<Path> segments = new ArrayList<>();
        try (var paths = Files.list(root)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (name.equals(IDENTITY_FILE)
                        || name.matches("RLOG-IDENTITY\\.tmp-[0-9a-f]{32}")) {
                    continue;
                } else if (name.matches("RLOG-[0-9]{20}\\.aerlog")) segments.add(path);
                else if (name.startsWith("RLOG-")
                        && !name.matches("RLOG-[0-9]{20}\\.aerlog\\.tmp-[0-9a-f]{32}")) {
                    throw new IOException("malformed managed replicated-log name: " + name);
                }
            }
        }
        if (segments.size() > 1_000_000)
            throw new IOException("replicated segment discovery limit exceeded");
        segments.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return List.copyOf(segments);
    }

    private void rotate() throws IOException {
        active.force(true);
        durableIndex = lastIndexRaw();
        active.close();
        createSegment(
                nextSegmentNumber++,
                lastIndexRaw() + 1,
                lastIndexRaw(),
                lastTermRaw(),
                lastHashRaw());
    }

    private void createSegment(
            long number,
            long firstIndex,
            long previousIndex,
            long previousTerm,
            byte[] previousHash)
            throws IOException {
        Path path = root.resolve(ReplicatedLogFormatV1.segmentName(number));
        ReplicatedLogSegmentHeaderV1 header =
                new ReplicatedLogSegmentHeaderV1(
                        durableIdentity.clusterId(),
                        durableIdentity.nodeId(),
                        number,
                        firstIndex,
                        previousIndex,
                        previousTerm,
                        previousHash,
                        System.currentTimeMillis());
        FileChannel channel = null;
        try {
            channel =
                    FileChannel.open(
                            path,
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.READ,
                            StandardOpenOption.WRITE);
            writeFully(channel, ByteBuffer.wrap(header.encodeRegion()), 0);
            channel.force(true);
            syncDirectory(root);
            active = channel;
            activePath = path;
            activeSegmentNumber = number;
            channel = null;
        } finally {
            if (channel != null) channel.close();
        }
    }

    private static ReplicatedLogIdentityV1 openIdentity(Path root, UUID clusterId, UUID nodeId)
            throws IOException {
        Path path = root.resolve(IDENTITY_FILE);
        if (Files.exists(path)) {
            ReplicatedLogIdentityV1 identity =
                    ReplicatedLogIdentityV1.decode(Files.readAllBytes(path));
            if (!identity.clusterId().equals(clusterId) || !identity.nodeId().equals(nodeId)) {
                throw new IOException("RLOG-IDENTITY cluster/node mismatch");
            }
            return identity;
        }
        ReplicatedLogIdentityV1 identity =
                new ReplicatedLogIdentityV1(clusterId, nodeId, System.currentTimeMillis());
        Path temporary =
                root.resolve(
                        IDENTITY_FILE + ".tmp-" + UUID.randomUUID().toString().replace("-", ""));
        try {
            try (FileChannel channel =
                    FileChannel.open(
                            temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                writeFully(channel, ByteBuffer.wrap(identity.encode()), 0);
                channel.force(true);
            }
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                throw new IOException("atomic identity publication unsupported", unsupported);
            }
            syncDirectory(root);
            return identity;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void validateNext(
            ReplicatedLogEntry entry,
            long expectedIndex,
            long previousTerm,
            long previousStateSequence,
            byte[] previousHash) {
        if (entry == null
                || entry.index() != expectedIndex
                || entry.term() < previousTerm
                || !Arrays.equals(entry.previousEntryHash(), previousHash)) {
            throw new IllegalArgumentException(
                    "replicated entry index/term/hash is not contiguous");
        }
        if (entry.type() == ReplicatedEntryType.COMMAND) {
            StateSequencePlanner.validate(
                    previousStateSequence,
                    Math.toIntExact(entry.stateSequenceAfter() - entry.sequenceStart() + 1),
                    new io.aetherdb.replication.api.StateSequenceRange(
                            entry.sequenceStart(), entry.stateSequenceAfter()));
        } else if (entry.sequenceStart() != 0
                || entry.stateSequenceAfter() != previousStateSequence) {
            throw new IllegalArgumentException("non-command entry changed state sequence");
        }
    }

    private static int validatedRecordLength(byte[] header) {
        if (header.length != ReplicatedLogFormatV1.ENTRY_HEADER_BYTES
                || header[0] != 'A'
                || header[1] != 'E'
                || header[2] != 'R'
                || header[3] != 'E')
            throw new IllegalArgumentException("invalid replicated entry prefix");
        int length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(8);
        if (length < 200
                || length > ReplicatedLogFormatV1.MAXIMUM_ENTRY_RECORD_BYTES
                || length % 8 != 0) {
            throw new IllegalArgumentException("invalid replicated entry declared length");
        }
        if (ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(168)
                != io.aetherdb.format.checksum.MaskedCrc32c.masked(header, 0, 168)) {
            throw new IllegalArgumentException("replicated entry header checksum mismatch");
        }
        return length;
    }

    private ReplicatedLogEntry readLocation(Location location) throws IOException {
        try (FileChannel channel = FileChannel.open(location.path, StandardOpenOption.READ)) {
            ReplicatedLogEntry entry =
                    ReplicatedLogEntryCodecV1.decode(
                            readFully(channel, location.offset, location.length));
            if (entry.index() != location.index
                    || entry.term() != location.term
                    || !Arrays.equals(entry.entryHash(), location.entryHash)) {
                throw new IOException("replicated-log index metadata mismatch");
            }
            return entry;
        }
    }

    private Location requiredLocation(long requestedIndex) {
        Location location = index.get(requestedIndex);
        if (location == null)
            throw new IllegalArgumentException(
                    "replicated log index is not retained: " + requestedIndex);
        return location;
    }

    private static Location location(
            Path path, long segment, long offset, int length, ReplicatedLogEntry entry) {
        return new Location(
                path,
                segment,
                offset,
                length,
                entry.index(),
                entry.term(),
                entry.stateSequenceAfter(),
                entry.entryHash());
    }

    private long lastIndexRaw() {
        return index.isEmpty() ? 0 : index.lastKey();
    }

    private long lastTermRaw() {
        return index.isEmpty() ? 0 : index.lastEntry().getValue().term;
    }

    private byte[] lastHashRaw() {
        return index.isEmpty() ? new byte[32] : index.lastEntry().getValue().entryHash.clone();
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("replicated log is closed");
    }

    private void ensureWritable() {
        ensureOpen();
        if (failure != null) throw failure("replicated log has failed", failure);
    }

    private static long segmentNumber(Path path) {
        return Long.parseLong(path.getFileName().toString().substring(5, 25));
    }

    private static void writeFully(FileChannel channel, ByteBuffer bytes, long offset)
            throws IOException {
        while (bytes.hasRemaining()) {
            int written = channel.write(bytes, offset);
            if (written < 0) throw new EOFException("channel closed during write");
            offset += written;
        }
    }

    private static byte[] readFully(FileChannel channel, long offset, int length)
            throws IOException {
        byte[] result = new byte[length];
        ByteBuffer bytes = ByteBuffer.wrap(result);
        while (bytes.hasRemaining()) {
            int read = channel.read(bytes, offset);
            if (read < 0) throw new EOFException("truncated replicated-log file");
            offset += read;
        }
        return result;
    }

    private static void syncDirectory(Path directory) throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        }
    }

    private static IllegalStateException failure(String message, Throwable cause) {
        return new IllegalStateException(message, cause);
    }

    private static void closeSuppressed(AutoCloseable closeable, Throwable failure) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private record Location(
            Path path,
            long segmentNumber,
            long offset,
            int length,
            long index,
            long term,
            long stateSequenceAfter,
            byte[] entryHash) {
        private Location {
            entryHash = entryHash.clone();
        }

        @Override
        public byte[] entryHash() {
            return entryHash.clone();
        }
    }
}
