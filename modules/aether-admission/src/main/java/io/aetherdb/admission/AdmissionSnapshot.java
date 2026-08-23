package io.aetherdb.admission;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Consistent resource measurements for one admission evaluation. */
public record AdmissionSnapshot(Map<AdmissionResource, ResourceMeasurement> measurements) {
    public AdmissionSnapshot {
        Objects.requireNonNull(measurements, "measurements");
        EnumMap<AdmissionResource, ResourceMeasurement> copied =
                new EnumMap<>(AdmissionResource.class);
        for (Map.Entry<AdmissionResource, ResourceMeasurement> entry : measurements.entrySet()) {
            if (entry.getKey() != entry.getValue().resource())
                throw new IllegalArgumentException("measurement key does not match resource");
            copied.put(entry.getKey(), entry.getValue());
        }
        measurements = Map.copyOf(copied);
    }

    public long value(AdmissionResource resource) {
        ResourceMeasurement measurement = measurements.get(resource);
        return measurement == null ? 0 : measurement.currentValue();
    }
}
