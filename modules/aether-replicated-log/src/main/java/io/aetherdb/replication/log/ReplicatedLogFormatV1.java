package io.aetherdb.replication.log;

/** Frozen Chapter 18 replicated-log v1 sizes, limits, and file naming. */
public final class ReplicatedLogFormatV1 {
    /** Segment header region and meaningful header sizes. */ public static final int SEGMENT_HEADER_REGION_BYTES = 4096, SEGMENT_HEADER_BYTES = 192;
    /** Entry header and trailer sizes. */ public static final int ENTRY_HEADER_BYTES = 192, ENTRY_TRAILER_BYTES = 8;
    /** Alignment and persistent segment limits. */ public static final int ALIGNMENT_BYTES = 8;
    /** Rotation target, hard segment limit, and maximum record size. */
    public static final long TARGET_SEGMENT_BYTES = 134_217_728L, HARD_SEGMENT_BYTES = 268_435_456L;
    /** Maximum complete aligned entry record bytes. */ public static final int MAXIMUM_ENTRY_RECORD_BYTES = 67_108_864;
    private ReplicatedLogFormatV1() {}
    /** Formats a canonical managed segment name. */
    public static String segmentName(long number) {
        if (number < 1) throw new IllegalArgumentException("segment number must be positive");
        return "RLOG-%020d.aerlog".formatted(number);
    }
    /** Calculates the aligned complete record length for a payload. */
    public static int recordLength(int payloadBytes) {
        if (payloadBytes < 0) throw new IllegalArgumentException("negative payload length");
        int unaligned = Math.addExact(Math.addExact(ENTRY_HEADER_BYTES, payloadBytes), ENTRY_TRAILER_BYTES);
        int padding = (ALIGNMENT_BYTES - unaligned % ALIGNMENT_BYTES) % ALIGNMENT_BYTES;
        int result = Math.addExact(unaligned, padding);
        if (result > MAXIMUM_ENTRY_RECORD_BYTES) throw new IllegalArgumentException("replicated entry record too large");
        return result;
    }
}
