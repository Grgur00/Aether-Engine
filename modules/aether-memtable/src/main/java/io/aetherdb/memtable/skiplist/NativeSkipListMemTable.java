package io.aetherdb.memtable.skiplist;

import io.aetherdb.memory.DefaultNativeRegionFactory;
import io.aetherdb.memory.NativeAccess;
import io.aetherdb.memory.NativeAllocator;
import io.aetherdb.memory.NativeMemoryBudget;
import io.aetherdb.memory.NativeRecordFormatV1;
import io.aetherdb.memory.NativeRecordReader;
import io.aetherdb.memory.NativeRecordView;
import io.aetherdb.memory.NativeRecordWriter;
import io.aetherdb.memory.NativeRegion;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/** Off-heap skip-list with serialized writers and acquire/release reader publication. */
@SuppressWarnings("preview")
public final class NativeSkipListMemTable implements AutoCloseable {
    public enum State {
        ACTIVE,
        FROZEN,
        RETIRED,
        CLOSED
    }

    public enum InsertResult {
        INSERTED,
        FULL,
        DUPLICATE,
        FROZEN
    }

    private final NativeRegion region;
    private final NativeRecordReader records;
    private final NativeRecordWriter writer;
    private final SkipListHeightGenerator heights;
    private final ReentrantLock writerLock = new ReentrantLock();
    private final AtomicInteger references = new AtomicInteger(1);
    private volatile State state = State.ACTIVE;
    private volatile int currentMaxHeight = 1;
    private long entryCount;

    public NativeSkipListMemTable(
            NativeMemoryBudget budget, long capacityBytes, String id, long seed) {
        region = new DefaultNativeRegionFactory(budget).create(capacityBytes, id);
        records = new NativeRecordReader(region);
        writer = new NativeRecordWriter(region);
        heights = new SkipListHeightGenerator(seed);
        NativeAllocator.Allocation head =
                region.allocator().tryAllocate(NativeSkipListNodeFormat.HEAD_BYTES, 8);
        if (!head.allocated() || head.offset() != NativeSkipListNodeFormat.HEAD_OFFSET)
            throw new IllegalStateException("head offset invariant");
        MemorySegment root = region.rootSegment();
        NativeAccess.setInt(root, 64, NativeSkipListNodeFormat.HEAD_BYTES);
        NativeAccess.setInt(root, 68, 0);
        NativeAccess.setByte(root, 72, (byte) SkipListHeightGenerator.MAX_HEIGHT);
        NativeAccess.setByte(root, 73, NativeSkipListNodeFormat.VERSION);
        NativeAccess.setShort(root, 74, NativeSkipListNodeFormat.HEAD_FLAG);
    }

    public InsertResult put(byte[] key, byte[] value, long sequence) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        return insert(key, value, sequence, NativeRecordFormatV1.VALUE);
    }

    public InsertResult delete(byte[] key, long sequence) {
        return insert(key, new byte[0], sequence, NativeRecordFormatV1.TOMBSTONE);
    }

    private InsertResult insert(byte[] key, byte[] value, long sequence, byte type) {
        if (key == null || sequence <= 0) throw new IllegalArgumentException("invalid mutation");
        NativeRecordFormatV1.validateLengths(key.length, value.length);
        writerLock.lock();
        try {
            if (state != State.ACTIVE) return InsertResult.FROZEN;
            int[] predecessors = new int[SkipListHeightGenerator.MAX_HEIGHT];
            int candidate = findPredecessors(key, sequence, type, predecessors);
            if (candidate != 0 && compareNode(candidate, key, sequence, type) == 0)
                return InsertResult.DUPLICATE;
            int height = heights.nextHeight();
            int recordBytes = NativeRecordFormatV1.totalLength(key.length, value.length);
            int allocationBytes = NativeSkipListNodeFormat.allocationBytes(height, recordBytes);
            NativeAllocator.Allocation allocation =
                    region.allocator().tryAllocate(allocationBytes, 8);
            if (!allocation.allocated()) return InsertResult.FULL;
            int node = allocation.offset();
            int record = node + NativeSkipListNodeFormat.prefixBytes(height);
            MemorySegment root = region.rootSegment();
            NativeAccess.checkedSlice(region, node, allocationBytes).fill((byte) 0);
            NativeAccess.setInt(root, node, allocationBytes);
            NativeAccess.setInt(root, node + 4L, record);
            NativeAccess.setByte(root, node + 8L, (byte) height);
            NativeAccess.setByte(root, node + 9L, NativeSkipListNodeFormat.VERSION);
            if (type == NativeRecordFormatV1.VALUE)
                writer.writeValueAt(record, key, value, sequence);
            else writer.writeTombstoneAt(record, key, sequence);
            for (int level = 0; level < height; level++) {
                int next = next(predecessors[level], level);
                NativeAccess.setInt(root, node + NativeSkipListNodeFormat.linkOffset(level), next);
            }
            setNextRelease(predecessors[0], 0, node);
            for (int level = 1; level < height; level++)
                setNextRelease(predecessors[level], level, node);
            currentMaxHeight = Math.max(currentMaxHeight, height);
            entryCount++;
            return InsertResult.INSERTED;
        } finally {
            writerLock.unlock();
        }
    }

    public MemTableLookupResult get(byte[] key, long visibleSequence) {
        ensureReadable();
        if (key == null || visibleSequence < 0)
            throw new IllegalArgumentException("invalid lookup");
        int node = lowerBound(key, visibleSequence, (byte) 0);
        if (node == 0) return MemTableLookupResult.notFound();
        NativeRecordView record = record(node);
        if (record.compareKey(key) != 0 || record.sequence() > visibleSequence)
            return MemTableLookupResult.notFound();
        return record.isTombstone()
                ? MemTableLookupResult.tombstone()
                : MemTableLookupResult.value(record.copyValue());
    }

    public List<Entry> scan(byte[] startInclusive, byte[] endExclusive, long visibleSequence) {
        ensureReadable();
        if (startInclusive == null
                || endExclusive == null
                || compareBytes(startInclusive, endExclusive) > 0)
            throw new IllegalArgumentException("invalid bounds");
        List<Entry> result = new ArrayList<>();
        int node = lowerBound(startInclusive, Long.MAX_VALUE, (byte) 0);
        while (node != 0) {
            NativeRecordView first = record(node);
            byte[] key = first.copyKey();
            if (compareBytes(key, endExclusive) >= 0) break;
            NativeRecordView visible = null;
            while (node != 0) {
                NativeRecordView current = record(node);
                if (current.compareKey(key) != 0) break;
                if (visible == null && current.sequence() <= visibleSequence) visible = current;
                node = next(node, 0);
            }
            if (visible != null && !visible.isTombstone())
                result.add(new Entry(key, visible.copyValue()));
        }
        return result;
    }

    /**
     * Materializes every internal record in strict user-key/descending-sequence order. This
     * flush-only view preserves overwritten values and tombstones.
     *
     * @return defensive immutable internal entries
     */
    public List<InternalEntry> internalEntries() {
        ensureReadable();
        if (state == State.ACTIVE)
            throw new IllegalStateException("MemTable must be frozen before flush iteration");
        List<InternalEntry> result = new ArrayList<>();
        int node = next(NativeSkipListNodeFormat.HEAD_OFFSET, 0);
        while (node != 0) {
            NativeRecordView record = record(node);
            boolean tombstone = record.isTombstone();
            result.add(
                    new InternalEntry(
                            record.copyKey(),
                            tombstone ? new byte[0] : record.copyValue(),
                            record.sequence(),
                            tombstone));
            node = next(node, 0);
        }
        return List.copyOf(result);
    }

    /**
     * Materializes each distinct user key without requiring the table to be frozen.
     *
     * @return keys in unsigned bytewise order
     */
    public List<byte[]> userKeys() {
        ensureReadable();
        List<byte[]> result = new ArrayList<>();
        byte[] previous = null;
        int node = next(NativeSkipListNodeFormat.HEAD_OFFSET, 0);
        while (node != 0) {
            NativeRecordView record = record(node);
            byte[] key = record.copyKey();
            if (previous == null || compareBytes(previous, key) != 0) {
                result.add(key);
                previous = key;
            }
            node = next(node, 0);
        }
        return List.copyOf(result);
    }

    /**
     * Returns a conservative maximum allocation for one mutation with the supplied payload lengths.
     *
     * @param keyBytes user-key bytes
     * @param valueBytes value bytes, zero for a tombstone
     * @return worst-case aligned node and native-record bytes
     */
    public static int maximumInsertionBytes(int keyBytes, int valueBytes) {
        int recordBytes = NativeRecordFormatV1.totalLength(keyBytes, valueBytes);
        return Math.addExact(
                7,
                NativeSkipListNodeFormat.allocationBytes(
                        SkipListHeightGenerator.MAX_HEIGHT, recordBytes));
    }

    /** Returns currently unallocated native bytes. */
    public long nativeRemainingBytes() {
        return region.allocator().remainingBytes();
    }

    private int lowerBound(byte[] key, long sequence, byte type) {
        int current = NativeSkipListNodeFormat.HEAD_OFFSET;
        for (int level = currentMaxHeight - 1; level >= 0; level--) {
            int next = next(current, level);
            while (next != 0 && compareNode(next, key, sequence, type) < 0) {
                current = next;
                next = next(current, level);
            }
        }
        return next(current, 0);
    }

    private int findPredecessors(byte[] key, long sequence, byte type, int[] predecessors) {
        int current = NativeSkipListNodeFormat.HEAD_OFFSET;
        for (int level = SkipListHeightGenerator.MAX_HEIGHT - 1; level >= 0; level--) {
            int next = next(current, level);
            while (next != 0 && compareNode(next, key, sequence, type) < 0) {
                current = next;
                next = next(current, level);
            }
            predecessors[level] = current;
        }
        return next(predecessors[0], 0);
    }

    private int compareNode(int node, byte[] key, long sequence, byte type) {
        NativeRecordView record = record(node);
        int keyComparison = record.compareKey(key);
        if (keyComparison != 0) return keyComparison;
        int sequenceComparison = Long.compare(sequence, record.sequence());
        if (sequenceComparison != 0) return sequenceComparison;
        return Integer.compare(Byte.toUnsignedInt(record.recordType()), Byte.toUnsignedInt(type));
    }

    private NativeRecordView record(int node) {
        return records.openChecked(NativeAccess.getInt(region.rootSegment(), node + 4L));
    }

    private int next(int node, int level) {
        return NativeAccess.getIntAcquire(
                region.rootSegment(), node + NativeSkipListNodeFormat.linkOffset(level));
    }

    private void setNextRelease(int node, int level, int target) {
        NativeAccess.setIntRelease(
                region.rootSegment(), node + NativeSkipListNodeFormat.linkOffset(level), target);
    }

    public void freeze() {
        writerLock.lock();
        try {
            if (state == State.ACTIVE) {
                state = State.FROZEN;
                region.freeze();
            }
        } finally {
            writerLock.unlock();
        }
    }

    public Lease retain() {
        while (true) {
            if (state == State.RETIRED || state == State.CLOSED)
                throw new IllegalStateException("MemTable retired");
            int current = references.get();
            if (references.compareAndSet(current, current + 1)) return new Lease(this);
        }
    }

    public void retire() {
        freeze();
        state = State.RETIRED;
        release();
    }

    private void release() {
        int remaining = references.decrementAndGet();
        if (remaining < 0) throw new IllegalStateException("negative reference count");
        if (remaining == 0 && state == State.RETIRED) {
            region.close();
            state = State.CLOSED;
        }
    }

    @Override
    public void close() {
        if (state != State.RETIRED && state != State.CLOSED) retire();
    }

    public State state() {
        return state;
    }

    public long entryCount() {
        return entryCount;
    }

    public long nativeUsedBytes() {
        return region.allocator().usedBytes();
    }

    public int headOffset() {
        return NativeSkipListNodeFormat.HEAD_OFFSET;
    }

    private void ensureReadable() {
        if (state == State.CLOSED) throw new IllegalStateException("MemTable is closed");
    }

    private static int compareBytes(byte[] left, byte[] right) {
        return java.util.Arrays.compareUnsigned(left, right);
    }

    public record Entry(byte[] key, byte[] value) {
        public Entry {
            key = key.clone();
            value = value.clone();
        }

        @Override
        public byte[] key() {
            return key.clone();
        }

        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    /** One flush-visible internal record, including its sequence and tombstone state. */
    public record InternalEntry(byte[] key, byte[] value, long sequence, boolean tombstone) {
        /** Takes defensive payload copies and validates durable fields. */
        public InternalEntry {
            if (key == null || value == null || sequence <= 0 || tombstone && value.length != 0) {
                throw new IllegalArgumentException("invalid internal MemTable entry");
            }
            key = key.clone();
            value = value.clone();
        }

        /** Returns a defensive user-key copy. */
        @Override
        public byte[] key() {
            return key.clone();
        }

        /** Returns a defensive value copy. */
        @Override
        public byte[] value() {
            return value.clone();
        }
    }

    public static final class Lease implements AutoCloseable {
        private NativeSkipListMemTable owner;

        private Lease(NativeSkipListMemTable owner) {
            this.owner = owner;
        }

        public NativeSkipListMemTable table() {
            if (owner == null) throw new IllegalStateException("lease closed");
            return owner;
        }

        @Override
        public void close() {
            if (owner != null) {
                NativeSkipListMemTable value = owner;
                owner = null;
                value.release();
            }
        }
    }
}
