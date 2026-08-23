package io.aetherdb.config;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;

/** Loads Aether configuration from properties, environment, and explicit overrides. */
public final class AetherConfigLoader {
    private final AetherConfigRegistry registry;
    private final AetherConfigValidator validator;

    public AetherConfigLoader(AetherConfigRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        validator = new AetherConfigValidator(registry);
    }

    public AetherConfiguration load(
            Path propertiesPath, Map<String, String> environment, Map<String, String> overrides)
            throws IOException {
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        if (propertiesPath != null) values.putAll(readProperties(propertiesPath));
        values.putAll(fromEnvironment(environment));
        values.putAll(Objects.requireNonNull(overrides, "overrides"));
        AetherConfiguration configuration = new AetherConfiguration(values);
        validator.validate(configuration);
        return configuration;
    }

    public Map<String, String> redactedDiagnosticView(AetherConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        for (ConfigSetting setting : registry.settings().values()) {
            String value = configuration.getOrDefault(setting);
            result.put(setting.name(), setting.securitySensitive() && !value.isBlank() ? "REDACTED" : value);
        }
        for (String name : configuration.values().keySet())
            if (!registry.settings().containsKey(name)) result.put(name, "UNKNOWN");
        return Map.copyOf(result);
    }

    private static Map<String, String> readProperties(Path path) throws IOException {
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(path)) {
            properties.load(reader);
        }
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String name : properties.stringPropertyNames()) values.put(name, properties.getProperty(name));
        return values;
    }

    private Map<String, String> fromEnvironment(Map<String, String> environment) {
        Objects.requireNonNull(environment, "environment");
        LinkedHashMap<String, String> values = new LinkedHashMap<>();
        for (String setting : registry.settings().keySet()) {
            String envName = setting.toUpperCase(Locale.ROOT).replace('.', '_');
            String value = environment.get(envName);
            if (value != null) values.put(setting, value);
        }
        return values;
    }

    public static AetherConfigLoader defaults() {
        return new AetherConfigLoader(AetherConfigRegistry.defaults());
    }
}
