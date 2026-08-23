package io.aetherdb.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Evaluates admin/API hot-reload attempts against registry reloadability metadata. */
public final class AetherConfigHotReloadManager {
    private static final String REDACTED = "REDACTED";

    private final AetherConfigRegistry registry;
    private final AetherConfigValidator validator;

    public AetherConfigHotReloadManager(AetherConfigRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        validator = new AetherConfigValidator(registry);
    }

    public ConfigReloadDecision evaluate(AetherConfiguration current, AetherConfiguration proposed) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(proposed, "proposed");
        try {
            validator.validate(current);
            validator.validate(proposed);
        } catch (ConfigValidationException failure) {
            return new ConfigReloadDecision(false, List.of(), List.of(failure.getMessage()));
        }

        List<ConfigReloadChange> changes = new ArrayList<>();
        for (ConfigSetting setting : registry.settings().values()) {
            String previousValue = current.getOrDefault(setting);
            String nextValue = proposed.getOrDefault(setting);
            if (previousValue.equals(nextValue)) continue;
            boolean allowed = setting.hotReloadable() && !setting.restartRequired();
            changes.add(
                    new ConfigReloadChange(
                            setting.name(),
                            diagnosticValue(setting, previousValue),
                            diagnosticValue(setting, nextValue),
                            allowed,
                            allowed ? "" : deniedReason(setting)));
        }
        return new ConfigReloadDecision(
                changes.stream().allMatch(ConfigReloadChange::applied), changes, List.of());
    }

    private static String deniedReason(ConfigSetting setting) {
        if (setting.restartRequired()) return "restart required";
        return "setting is not hot reloadable";
    }

    private static String diagnosticValue(ConfigSetting setting, String value) {
        return setting.securitySensitive() && !value.isBlank() ? REDACTED : value;
    }

    public static AetherConfigHotReloadManager defaults() {
        return new AetherConfigHotReloadManager(AetherConfigRegistry.defaults());
    }
}
