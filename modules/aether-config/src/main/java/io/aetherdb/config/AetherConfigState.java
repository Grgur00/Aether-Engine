package io.aetherdb.config;

import java.util.Objects;

/** Thread-safe owner of a resolved configuration that applies accepted hot reloads atomically. */
public final class AetherConfigState {
    private final AetherConfigHotReloadManager reloadManager;
    private AetherConfiguration current;

    public AetherConfigState(AetherConfiguration initial, AetherConfigRegistry registry) {
        current = Objects.requireNonNull(initial, "initial");
        reloadManager = new AetherConfigHotReloadManager(Objects.requireNonNull(registry, "registry"));
        ConfigReloadDecision validation = reloadManager.evaluate(current, current);
        if (!validation.accepted())
            throw new ConfigValidationException(
                    validation.errors().isEmpty()
                            ? "initial configuration is not reloadable"
                            : validation.errors().getFirst());
    }

    public synchronized AetherConfiguration current() {
        return current;
    }

    public synchronized ConfigReloadDecision reload(AetherConfiguration proposed) {
        ConfigReloadDecision decision = reloadManager.evaluate(current, proposed);
        if (decision.accepted()) current = Objects.requireNonNull(proposed, "proposed");
        return decision;
    }

    public static AetherConfigState defaults(AetherConfiguration initial) {
        return new AetherConfigState(initial, AetherConfigRegistry.defaults());
    }
}
