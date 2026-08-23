package io.aetherdb.config;

import java.util.Objects;
import java.util.List;

/** One registered production setting definition. */
public record ConfigSetting(
        String name,
        ConfigType type,
        String defaultValue,
        Long minimum,
        Long maximum,
        ConfigScope scope,
        boolean hotReloadable,
        boolean restartRequired,
        boolean securitySensitive,
        int compatibilityEpoch,
        List<String> unsafeCombinations,
        String validationErrorCode) {
    public ConfigSetting {
        if (name == null || !name.matches("aether\\.[a-z0-9_.]+"))
            throw new IllegalArgumentException("invalid setting name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(scope, "scope");
        unsafeCombinations = List.copyOf(Objects.requireNonNull(unsafeCombinations, "unsafeCombinations"));
        if (validationErrorCode == null || validationErrorCode.isBlank())
            throw new IllegalArgumentException("validation error code is required");
        if (compatibilityEpoch < 1) throw new IllegalArgumentException("invalid compatibility epoch");
        if (minimum != null && maximum != null && minimum > maximum)
            throw new IllegalArgumentException("minimum exceeds maximum");
    }
}
