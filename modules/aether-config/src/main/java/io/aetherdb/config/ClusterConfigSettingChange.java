package io.aetherdb.config;

import java.util.Objects;

/** One staged cluster-wide setting transition from an expected old value to a new value. */
public record ClusterConfigSettingChange(String settingName, String fromValue, String toValue) {
    public ClusterConfigSettingChange {
        if (settingName == null || settingName.isBlank())
            throw new IllegalArgumentException("settingName is required");
        fromValue = Objects.requireNonNull(fromValue, "fromValue");
        toValue = Objects.requireNonNull(toValue, "toValue");
        if (fromValue.equals(toValue)) throw new IllegalArgumentException("staged change must change value");
    }
}
