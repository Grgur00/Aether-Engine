package io.aetherdb.memory;

import java.lang.foreign.MemorySegment;

/** Non-owning checked view valid only while its region remains alive. */
@SuppressWarnings("preview")
public final class NativeRecordView {
    private final NativeRegion region;
    private final int offset;
    private final int totalLength;
    private final int keyLength;
    private final int valueLength;
    private final long sequence;
    private final byte type;

    NativeRecordView(NativeRegion region, int offset, int totalLength, int keyLength, int valueLength, long sequence, byte type) {
        this.region = region;
        this.offset = offset;
        this.totalLength = totalLength;
        this.keyLength = keyLength;
        this.valueLength = valueLength;
        this.sequence = sequence;
        this.type = type;
    }

    public int offset() { alive(); return offset; }
    public int totalLength() { alive(); return totalLength; }
    public int keyLength() { alive(); return keyLength; }
    public int valueLength() { alive(); return valueLength; }
    public long sequence() { alive(); return sequence; }
    public byte recordType() { alive(); return type; }
    public boolean isTombstone() { alive(); return type == NativeRecordFormatV1.TOMBSTONE; }

    public byte[] copyKey() {
        MemorySegment root = alive();
        return NativeAccess.copyToArray(root, (long) offset + NativeRecordFormatV1.HEADER_BYTES, keyLength);
    }

    public byte[] copyValue() {
        if (isTombstone()) throw new IllegalStateException("tombstone has no value");
        MemorySegment root = alive();
        return NativeAccess.copyToArray(root, (long) offset + NativeRecordFormatV1.HEADER_BYTES + keyLength, valueLength);
    }

    public int compareKey(byte[] candidate) {
        if (candidate == null) throw new IllegalArgumentException("candidate must not be null");
        return NativeAccess.compareUnsigned(alive(), (long) offset + NativeRecordFormatV1.HEADER_BYTES, keyLength, candidate);
    }

    private MemorySegment alive() { return region.rootSegment(); }
}
