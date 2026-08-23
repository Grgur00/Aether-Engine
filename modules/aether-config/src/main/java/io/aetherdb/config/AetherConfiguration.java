package io.aetherdb.config;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable string-backed configuration values after source precedence has been resolved. */
public record AetherConfiguration(Map<String, String> values) {
    public AetherConfiguration {
        values = Map.copyOf(Objects.requireNonNull(values, "values"));
    }

    public Optional<String> get(String name) {
        return Optional.ofNullable(values.get(name));
    }

    public String getOrDefault(ConfigSetting setting) {
        return values.getOrDefault(setting.name(), setting.defaultValue());
    }
}
