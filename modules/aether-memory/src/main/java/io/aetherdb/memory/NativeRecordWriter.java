package io.aetherdb.memory;

import java.lang.foreign.MemorySegment;

/** Copies value or tombstone records into a native region. */
@SuppressWarnings("preview")
public final class NativeRecordWriter {
    private final NativeRegion region;
    public NativeRecordWriter(NativeRegion region) { this.region = region; }

    public NativeAllocator.Allocation writeValue(byte[] key, byte[] value, long sequence) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        return write(key, value, sequence, NativeRecordFormatV1.VALUE);
    }

    public NativeAllocator.Allocation writeTombstone(byte[] key, long sequence) {
        return write(key, new byte[0], sequence, NativeRecordFormatV1.TOMBSTONE);
    }

    private NativeAllocator.Allocation write(byte[] key, byte[] value, long sequence, byte type) {
        if (key == null) throw new IllegalArgumentException("key must not be null");
        if (sequence <= 0) throw new IllegalArgumentException("sequence must be positive");
        int total = NativeRecordFormatV1.totalLength(key.length, value.length);
        NativeAllocator.Allocation allocation = region.allocator().tryAllocate(total, NativeRecordFormatV1.ALIGNMENT);
        if (!allocation.allocated()) return allocation;
        writeAt(allocation.offset(), total, key, value, sequence, type);
        return allocation;
    }

    public void writeValueAt(int offset, byte[] key, byte[] value, long sequence) {
        if (value == null) throw new IllegalArgumentException("value must not be null");
        writeAt(offset, NativeRecordFormatV1.totalLength(key.length, value.length), key, value, sequence, NativeRecordFormatV1.VALUE);
    }

    public void writeTombstoneAt(int offset, byte[] key, long sequence) {
        writeAt(offset, NativeRecordFormatV1.totalLength(key.length, 0), key, new byte[0], sequence, NativeRecordFormatV1.TOMBSTONE);
    }

    private void writeAt(int offset, int total, byte[] key, byte[] value, long sequence, byte type) {
        if (key == null || sequence <= 0) throw new IllegalArgumentException("invalid record");
        MemorySegment record = NativeAccess.checkedSlice(region, offset, total);
        record.fill((byte) 0);
        NativeAccess.copyFromArray(key, record, NativeRecordFormatV1.HEADER_BYTES);
        if (type == NativeRecordFormatV1.VALUE) {
            NativeAccess.copyFromArray(value, record, NativeRecordFormatV1.HEADER_BYTES + key.length);
        }
        NativeAccess.setInt(record, NativeRecordFormatV1.KEY_LENGTH_OFFSET, key.length);
        NativeAccess.setInt(record, NativeRecordFormatV1.VALUE_LENGTH_OFFSET, value.length);
        NativeAccess.setShort(record, NativeRecordFormatV1.FORMAT_VERSION_OFFSET, NativeRecordFormatV1.VERSION);
        NativeAccess.setByte(record, NativeRecordFormatV1.RECORD_TYPE_OFFSET, type);
        NativeAccess.setByte(record, NativeRecordFormatV1.FLAGS_OFFSET, (byte) 0);
        NativeAccess.setLong(record, NativeRecordFormatV1.SEQUENCE_OFFSET, sequence);
        NativeAccess.setInt(record, NativeRecordFormatV1.TOTAL_LENGTH_OFFSET, total);
    }
}
