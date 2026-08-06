package io.aetherdb.replication.log;

import io.aetherdb.format.checksum.MaskedCrc32c;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Exact 192-byte replicated-log segment header in a zero-filled 4 KiB region. */
public record ReplicatedLogSegmentHeaderV1(UUID clusterId, UUID nodeId, long segmentNumber,
                                           long firstIndex, long previousIndex, long previousTerm,
                                           byte[] previousEntryHash, long creationEpochMillis) {
    /** Validates identity, chain boundary, and defensive hash ownership. */
    public ReplicatedLogSegmentHeaderV1 {
        UUID zero = new UUID(0, 0);
        if (clusterId == null || nodeId == null || clusterId.equals(zero) || nodeId.equals(zero)
                || segmentNumber < 1 || firstIndex < 1 || previousIndex != firstIndex - 1
                || previousTerm < 0 || previousEntryHash == null || previousEntryHash.length != 32
                || creationEpochMillis < 0 || previousIndex == 0 && (previousTerm != 0 || !allZero(previousEntryHash))
                || previousIndex > 0 && previousTerm < 1) throw new IllegalArgumentException("invalid replicated segment header");
        previousEntryHash = previousEntryHash.clone();
    }
    /** Returns the prior entry hash defensively. */ @Override public byte[] previousEntryHash() { return previousEntryHash.clone(); }
    @Override public boolean equals(Object other) {
        return this == other || other instanceof ReplicatedLogSegmentHeaderV1 header
                && segmentNumber == header.segmentNumber && firstIndex == header.firstIndex
                && previousIndex == header.previousIndex && previousTerm == header.previousTerm
                && creationEpochMillis == header.creationEpochMillis && clusterId.equals(header.clusterId)
                && nodeId.equals(header.nodeId) && Arrays.equals(previousEntryHash, header.previousEntryHash);
    }
    @Override public int hashCode() {
        return 31 * java.util.Objects.hash(clusterId, nodeId, segmentNumber, firstIndex,
                previousIndex, previousTerm, creationEpochMillis) + Arrays.hashCode(previousEntryHash);
    }
    /** Encodes the complete forced header region. */
    public byte[] encodeRegion() {
        byte[] result = new byte[ReplicatedLogFormatV1.SEGMENT_HEADER_REGION_BYTES];
        ByteBuffer bytes = ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN);
        bytes.put("AETHRSG1".getBytes(StandardCharsets.US_ASCII)).putShort((short) 1)
                .putShort((short) ReplicatedLogFormatV1.SEGMENT_HEADER_BYTES).putInt(0);
        putUuid(bytes, clusterId); putUuid(bytes, nodeId); bytes.putLong(segmentNumber).putLong(firstIndex)
                .putLong(previousIndex).putLong(previousTerm).put(previousEntryHash)
                .putLong(ReplicatedLogFormatV1.TARGET_SEGMENT_BYTES)
                .putLong(ReplicatedLogFormatV1.HARD_SEGMENT_BYTES)
                .putInt(ReplicatedLogFormatV1.MAXIMUM_ENTRY_RECORD_BYTES)
                .putInt(ReplicatedLogFormatV1.ENTRY_HEADER_BYTES).putLong(1).putLong(creationEpochMillis);
        bytes.position(188).putInt(MaskedCrc32c.masked(result, 0, 188)); return result;
    }
    /** Decodes and validates a full header region against its file identity. */
    public static ReplicatedLogSegmentHeaderV1 decodeRegion(byte[] region, UUID clusterId,
                                                             UUID nodeId, long segmentNumber) {
        if (region == null || region.length != ReplicatedLogFormatV1.SEGMENT_HEADER_REGION_BYTES) {
            throw new IllegalArgumentException("replicated segment header region must be 4096 bytes");
        }
        ByteBuffer bytes = ByteBuffer.wrap(region).order(ByteOrder.LITTLE_ENDIAN); byte[] magic = new byte[8]; bytes.get(magic);
        if (!Arrays.equals(magic, "AETHRSG1".getBytes(StandardCharsets.US_ASCII)) || bytes.getShort() != 1
                || bytes.getShort() != ReplicatedLogFormatV1.SEGMENT_HEADER_BYTES || bytes.getInt() != 0) {
            throw new IllegalArgumentException("invalid replicated segment header");
        }
        UUID storedCluster = getUuid(bytes), storedNode = getUuid(bytes); long storedSegment = bytes.getLong();
        long first = bytes.getLong(), previous = bytes.getLong(), term = bytes.getLong(); byte[] hash = new byte[32]; bytes.get(hash);
        if (!storedCluster.equals(clusterId) || !storedNode.equals(nodeId) || storedSegment != segmentNumber
                || bytes.getLong() != ReplicatedLogFormatV1.TARGET_SEGMENT_BYTES
                || bytes.getLong() != ReplicatedLogFormatV1.HARD_SEGMENT_BYTES
                || bytes.getInt() != ReplicatedLogFormatV1.MAXIMUM_ENTRY_RECORD_BYTES
                || bytes.getInt() != ReplicatedLogFormatV1.ENTRY_HEADER_BYTES || bytes.getLong() != 1) {
            throw new IllegalArgumentException("replicated segment identity or format mismatch");
        }
        long created = bytes.getLong();
        for (int index = 152; index < 188; index++) if (region[index] != 0) throw new IllegalArgumentException("nonzero segment reserved byte");
        for (int index = 192; index < region.length; index++) if (region[index] != 0) throw new IllegalArgumentException("nonzero segment header-region tail");
        if (ByteBuffer.wrap(region, 188, 4).order(ByteOrder.LITTLE_ENDIAN).getInt()
                != MaskedCrc32c.masked(region, 0, 188)) throw new IllegalArgumentException("segment header checksum mismatch");
        return new ReplicatedLogSegmentHeaderV1(storedCluster, storedNode, storedSegment,
                first, previous, term, hash, created);
    }
    private static boolean allZero(byte[] bytes) { for (byte value : bytes) if (value != 0) return false; return true; }
    private static void putUuid(ByteBuffer bytes, UUID id) { bytes.putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits()); }
    private static UUID getUuid(ByteBuffer bytes) { return new UUID(bytes.getLong(), bytes.getLong()); }
}
