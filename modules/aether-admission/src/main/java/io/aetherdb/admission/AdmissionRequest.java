package io.aetherdb.admission;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable requested resource deltas and node state for one operation admission.
 *
 * @param charges resource increments required before the operation can cross acknowledgement
 * @param draining whether this node rejects new work due to shutdown or leadership transfer
 */
public record AdmissionRequest(Map<AdmissionResource, Long> charges, boolean draining) {
    public AdmissionRequest {
        Objects.requireNonNull(charges, "charges");
        EnumMap<AdmissionResource, Long> copied = new EnumMap<>(AdmissionResource.class);
        for (Map.Entry<AdmissionResource, Long> entry : charges.entrySet()) {
            if (entry.getKey() == null) throw new IllegalArgumentException("resource is required");
            long value = entry.getValue();
            if (value < 0) throw new IllegalArgumentException("charge must be non-negative");
            if (value > 0) copied.put(entry.getKey(), value);
        }
        charges = Map.copyOf(copied);
    }
}
