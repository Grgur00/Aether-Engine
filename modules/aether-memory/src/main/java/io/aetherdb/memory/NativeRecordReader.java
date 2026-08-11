package io.aetherdb.memory;

import java.lang.foreign.MemorySegment;

/** Checked record decoder and zero-copy comparator. */
@SuppressWarnings("preview")
public final class NativeRecordReader {
    private final NativeRegion region;

    public NativeRecordReader(NativeRegion region) {
        this.region = region;
    }

    public NativeRecordView openChecked(int offset) {
        if (offset < RegionConfig.FIRST_ALLOCATABLE_OFFSET
                || offset % NativeRecordFormatV1.ALIGNMENT != 0
                || region.capacityBytes() - offset < NativeRecordFormatV1.HEADER_BYTES) {
            throw corrupt("invalid record offset");
        }
        MemorySegment header =
                NativeAccess.checkedSlice(region, offset, NativeRecordFormatV1.HEADER_BYTES);
        int total = NativeAccess.getInt(header, NativeRecordFormatV1.TOTAL_LENGTH_OFFSET);
        int keyLength = NativeAccess.getInt(header, NativeRecordFormatV1.KEY_LENGTH_OFFSET);
        int valueLength = NativeAccess.getInt(header, NativeRecordFormatV1.VALUE_LENGTH_OFFSET);
        short version = NativeAccess.getShort(header, NativeRecordFormatV1.FORMAT_VERSION_OFFSET);
        byte type = NativeAccess.getByte(header, NativeRecordFormatV1.RECORD_TYPE_OFFSET);
        byte flags = NativeAccess.getByte(header, NativeRecordFormatV1.FLAGS_OFFSET);
        long sequence = NativeAccess.getLong(header, NativeRecordFormatV1.SEQUENCE_OFFSET);
        if (total < NativeRecordFormatV1.HEADER_BYTES
                || total % NativeRecordFormatV1.ALIGNMENT != 0
                || total > NativeRecordFormatV1.MAX_RECORD_BYTES)
            throw corrupt("invalid total length");
        if (keyLength < 0
                || keyLength > NativeRecordFormatV1.MAX_KEY_BYTES
                || valueLength < 0
                || valueLength > NativeRecordFormatV1.MAX_VALUE_BYTES)
            throw corrupt("invalid payload lengths");
        if (version != NativeRecordFormatV1.VERSION
                || flags != 0
                || (type != NativeRecordFormatV1.VALUE && type != NativeRecordFormatV1.TOMBSTONE))
            throw corrupt("unsupported header");
        if (sequence <= 0 || type == NativeRecordFormatV1.TOMBSTONE && valueLength != 0)
            throw corrupt("invalid record semantics");
        int expected;
        try {
            expected = NativeRecordFormatV1.totalLength(keyLength, valueLength);
        } catch (IllegalArgumentException | ArithmeticException failure) {
            throw corrupt("invalid derived length");
        }
        if (expected != total || (long) offset + total > region.capacityBytes())
            throw corrupt("record exceeds bounds");
        MemorySegment record = NativeAccess.checkedSlice(region, offset, total);
        int logical = NativeRecordFormatV1.HEADER_BYTES + keyLength + valueLength;
        for (int index = logical; index < total; index++) {
            if (NativeAccess.getByte(record, index) != 0) throw corrupt("non-zero record padding");
        }
        return new NativeRecordView(region, offset, total, keyLength, valueLength, sequence, type);
    }

    public int compareKey(int offset, byte[] candidate) {
        return openChecked(offset).compareKey(candidate);
    }

    private static NativeRecordCorruptionException corrupt(String message) {
        return new NativeRecordCorruptionException(message);
    }
}
