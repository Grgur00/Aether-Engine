package io.aetherdb.admission;

/** One current resource value used for an admission decision. */
public record ResourceMeasurement(AdmissionResource resource, long currentValue) {
    public ResourceMeasurement {
        if (resource == null) throw new IllegalArgumentException("resource is required");
        if (currentValue < 0) throw new IllegalArgumentException("resource value must be non-negative");
    }
}
