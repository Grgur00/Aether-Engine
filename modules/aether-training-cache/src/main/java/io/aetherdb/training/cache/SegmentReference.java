package io.aetherdb.training.cache;

import java.util.Arrays;
import java.util.Objects;

/** Validated location and integrity metadata for one immutable cached payload. */
public record SegmentReference(String segmentId, long generation, long offset, int length, byte[] checksum) {
    public SegmentReference {
        Objects.requireNonNull(segmentId, "segmentId");
        Objects.requireNonNull(checksum, "checksum");
        if (segmentId.isBlank() || generation < 1 || offset < 0 || length < 0 || checksum.length != 32)
            throw new IllegalArgumentException("invalid segment reference");
        checksum = checksum.clone();
    }

    @Override public byte[] checksum() { return checksum.clone(); }

    public boolean sameChecksum(byte[] value) { return Arrays.equals(checksum, value); }
}
