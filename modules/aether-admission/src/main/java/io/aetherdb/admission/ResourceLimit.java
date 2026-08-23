package io.aetherdb.admission;

/**
 * Soft, hard, and emergency bounds for one resource dimension.
 *
 * @param resource resource dimension
 * @param softLimit value at which slowdown begins
 * @param hardLimit value at which new work is rejected before acknowledgement
 * @param emergencyLimit value at which the node should be considered degraded/read-only
 */
public record ResourceLimit(
        AdmissionResource resource, long softLimit, long hardLimit, long emergencyLimit) {
    public ResourceLimit {
        if (resource == null) throw new IllegalArgumentException("resource is required");
        if (softLimit < 0 || hardLimit <= 0 || emergencyLimit <= 0)
            throw new IllegalArgumentException("limits must be non-negative and bounded");
        if (softLimit > hardLimit || hardLimit > emergencyLimit)
            throw new IllegalArgumentException("limits must satisfy soft <= hard <= emergency");
    }
}
