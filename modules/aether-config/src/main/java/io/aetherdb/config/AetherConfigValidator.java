package io.aetherdb.config;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** Validates registered Aether settings and Chapter 32 unsafe combinations. */
public final class AetherConfigValidator {
    private final AetherConfigRegistry registry;

    public AetherConfigValidator(AetherConfigRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public void validate(AetherConfiguration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        for (String name : configuration.values().keySet())
            if (!registry.settings().containsKey(name))
                throw new ConfigValidationException("unknown setting: " + name);
        for (ConfigSetting setting : registry.settings().values()) validateValue(setting, configuration.getOrDefault(setting));
        validateUnsafeCombinations(configuration);
    }

    private static void validateValue(ConfigSetting setting, String raw) {
        switch (setting.type()) {
            case BOOLEAN -> {
                if (!raw.equalsIgnoreCase("true") && !raw.equalsIgnoreCase("false"))
                    throw new ConfigValidationException("invalid boolean setting: " + setting.name());
            }
            case INTEGER, LONG -> {
                long value;
                try {
                    value = Long.parseLong(raw);
                } catch (NumberFormatException failure) {
                    throw new ConfigValidationException("invalid numeric setting: " + setting.name());
                }
                if (setting.minimum() != null && value < setting.minimum()
                        || setting.maximum() != null && value > setting.maximum()) {
                    throw new ConfigValidationException("setting out of range: " + setting.name());
                }
            }
            case STRING -> {
                // Presence is sufficient; semantic combinations are validated below.
            }
        }
    }

    private void validateUnsafeCombinations(AetherConfiguration configuration) {
        String profile = value(configuration, "aether.security.profile").toLowerCase(Locale.ROOT);
        boolean production = profile.equals("production");
        if (!production && !profile.equals("development"))
            throw new ConfigValidationException("invalid security profile");
        if (production) {
            requireTrue(configuration, "aether.security.transport.tls.enabled");
            requireTrue(configuration, "aether.security.node.mtls.required");
            requireTrue(configuration, "aether.security.authorization.required");
            requireFalse(configuration, "aether.security.audit.fail_open");
            if (isTrue(configuration, "aether.security.encryption.at_rest.enabled")
                    && value(configuration, "aether.security.encryption.kek.provider").isBlank()) {
                throw new ConfigValidationException("production encryption requires KEK provider");
            }
        }
        validateEnum(
                configuration,
                "aether.wal.durability_mode",
                java.util.Set.of("SYNC", "GROUP_SYNC"));
        validateEnum(
                configuration,
                "aether.observability.log.level",
                java.util.Set.of("TRACE", "DEBUG", "INFO", "WARN", "ERROR"));
        long frameBytes = longValue(configuration, "aether.rpc.max_frame_bytes");
        long messageBytes = longValue(configuration, "aether.rpc.max_message_bytes");
        if (messageBytes < frameBytes)
            throw new ConfigValidationException("rpc max message bytes must be at least max frame bytes");
        long heartbeat = longValue(configuration, "aether.raft.heartbeat_interval_millis");
        long electionMin = longValue(configuration, "aether.raft.election_timeout_min_millis");
        long electionMax = longValue(configuration, "aether.raft.election_timeout_max_millis");
        if (electionMin <= heartbeat * 2)
            throw new ConfigValidationException("election timeout minimum must exceed heartbeat interval by more than 2x");
        if (electionMax < electionMin)
            throw new ConfigValidationException("election timeout maximum is below minimum");
        long memoryBudget = longValue(configuration, "aether.resource.memory_budget_bytes");
        long memoryConsumers =
                longValue(configuration, "aether.block_cache.bytes")
                        + longValue(configuration, "aether.memtable.native_bytes")
                        + longValue(configuration, "aether.compaction.max_background_jobs")
                                * longValue(configuration, "aether.sstable.target_data_block_bytes");
        if (memoryConsumers > memoryBudget)
            throw new ConfigValidationException("cache, memtable, and compaction buffers exceed memory budget");
    }

    private void validateEnum(AetherConfiguration configuration, String name, java.util.Set<String> allowed) {
        String raw = value(configuration, name).toUpperCase(Locale.ROOT);
        if (!allowed.contains(raw)) throw new ConfigValidationException("invalid value for " + name);
    }

    private void requireTrue(AetherConfiguration configuration, String name) {
        if (!isTrue(configuration, name)) throw new ConfigValidationException("production requires " + name);
    }

    private void requireFalse(AetherConfiguration configuration, String name) {
        if (isTrue(configuration, name)) throw new ConfigValidationException("production rejects " + name);
    }

    private boolean isTrue(AetherConfiguration configuration, String name) {
        return Boolean.parseBoolean(value(configuration, name));
    }

    private long longValue(AetherConfiguration configuration, String name) {
        return Long.parseLong(value(configuration, name));
    }

    private String value(AetherConfiguration configuration, String name) {
        return configuration.getOrDefault(registry.require(name));
    }

    public static AetherConfigValidator defaults() {
        return new AetherConfigValidator(AetherConfigRegistry.defaults());
    }

    public static void validate(Map<String, String> values) {
        defaults().validate(new AetherConfiguration(values));
    }
}
