package io.aetherdb.training.cache;

import java.nio.ByteBuffer;
import java.util.List;

/** Packed ordered results for one bounded cache-value batch. */
public record BatchValueResult(List<ValueStatus> statuses, int[] offsets, int[] lengths, ByteBuffer payload) {
    public enum ValueStatus { HIT_INLINE, MISS, CORRUPT, HIT_SEGMENT }

    public BatchValueResult {
        if (statuses == null || offsets == null || lengths == null || payload == null
                || statuses.size() != offsets.length || offsets.length != lengths.length)
            throw new IllegalArgumentException("inconsistent batch value result");
        statuses = List.copyOf(statuses);
        offsets = offsets.clone();
        lengths = lengths.clone();
        payload = payload.asReadOnlyBuffer();
    }
}
