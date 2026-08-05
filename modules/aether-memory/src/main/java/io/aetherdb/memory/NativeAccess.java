package io.aetherdb.memory;

import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.lang.invoke.VarHandle;

/** Central checked little-endian FFM access boundary. */
@SuppressWarnings("preview")
public final class NativeAccess {
    private static final ValueLayout.OfInt INT = ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfShort SHORT = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfLong LONG = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final ValueLayout.OfInt ALIGNED_INT = ValueLayout.JAVA_INT.withOrder(ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_HANDLE = ALIGNED_INT.varHandle();
    private NativeAccess() {}

    public static MemorySegment checkedSlice(NativeRegion region, long offset, long length) {
        if (offset < 0 || length < 0) throw new IllegalArgumentException("negative slice");
        long end = Math.addExact(offset, length);
        if (end > region.capacityBytes()) throw new IndexOutOfBoundsException("slice exceeds region");
        return region.rootSegment().asSlice(offset, length);
    }

    public static int getInt(MemorySegment segment, long offset) { return segment.get(INT, offset); }
    public static void setInt(MemorySegment segment, long offset, int value) { segment.set(INT, offset, value); }
    public static int getIntAcquire(MemorySegment segment, long offset) {
        return (int) INT_HANDLE.getAcquire(segment.asSlice(offset, Integer.BYTES));
    }
    public static void setIntRelease(MemorySegment segment, long offset, int value) {
        INT_HANDLE.setRelease(segment.asSlice(offset, Integer.BYTES), value);
    }
    public static short getShort(MemorySegment segment, long offset) { return segment.get(SHORT, offset); }
    public static void setShort(MemorySegment segment, long offset, short value) { segment.set(SHORT, offset, value); }
    public static long getLong(MemorySegment segment, long offset) { return segment.get(LONG, offset); }
    public static void setLong(MemorySegment segment, long offset, long value) { segment.set(LONG, offset, value); }
    public static byte getByte(MemorySegment segment, long offset) { return segment.get(ValueLayout.JAVA_BYTE, offset); }
    public static void setByte(MemorySegment segment, long offset, byte value) { segment.set(ValueLayout.JAVA_BYTE, offset, value); }

    public static void copyFromArray(byte[] source, MemorySegment target, long targetOffset) {
        MemorySegment.copy(MemorySegment.ofArray(source), 0, target, targetOffset, source.length);
    }

    public static byte[] copyToArray(MemorySegment source, long sourceOffset, int length) {
        byte[] target = new byte[length];
        MemorySegment.copy(source, sourceOffset, MemorySegment.ofArray(target), 0, length);
        return target;
    }

    public static int compareUnsigned(MemorySegment segment, long offset, int length, byte[] candidate) {
        int limit = Math.min(length, candidate.length);
        for (int index = 0; index < limit; index++) {
            int left = Byte.toUnsignedInt(getByte(segment, offset + index));
            int right = Byte.toUnsignedInt(candidate[index]);
            if (left != right) return Integer.compare(left, right);
        }
        return Integer.compare(length, candidate.length);
    }
}
