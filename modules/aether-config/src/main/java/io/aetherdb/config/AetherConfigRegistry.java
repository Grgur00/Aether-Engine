package io.aetherdb.config;

import java.util.LinkedHashMap;
import java.util.Map;

/** Initial Chapter 32 setting registry. */
public final class AetherConfigRegistry {
    private static final long KIB = 1024L;
    private static final long MIB = 1024L * KIB;
    private static final long GIB = 1024L * MIB;

    private final Map<String, ConfigSetting> settings;

    private AetherConfigRegistry(Map<String, ConfigSetting> settings) {
        this.settings = Map.copyOf(settings);
    }

    public static AetherConfigRegistry defaults() {
        LinkedHashMap<String, ConfigSetting> settings = new LinkedHashMap<>();
        add(settings, "aether.storage.path", ConfigType.STRING, "", null, null, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.storage.lock.timeout_seconds", ConfigType.INTEGER, "30", 1L, 3600L, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.wal.durability_mode", ConfigType.STRING, "GROUP_SYNC", null, null, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.wal.segment_bytes", ConfigType.LONG, Long.toString(64L * MIB), MIB, 64L * MIB, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.wal.force_timeout_seconds", ConfigType.INTEGER, "5", 1L, 300L, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.memtable.native_bytes", ConfigType.LONG, Long.toString(64L * MIB), MIB, 512L * MIB, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.memtable.immutable_limit", ConfigType.INTEGER, "4", 1L, 64L, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.sstable.target_data_block_bytes", ConfigType.INTEGER, "4096", 1024L, 64L * KIB, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.block_cache.bytes", ConfigType.LONG, Long.toString(128L * MIB), 0L, 8L * GIB, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.compaction.enabled", ConfigType.BOOLEAN, "true", null, null, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.compaction.max_background_jobs", ConfigType.INTEGER, "2", 1L, 64L, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.compaction.bandwidth_bytes_per_second", ConfigType.LONG, Long.toString(128L * MIB), 0L, 16L * GIB, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.snapshots.max_open", ConfigType.INTEGER, "64", 1L, 4096L, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.rpc.bind_host", ConfigType.STRING, "0.0.0.0", null, null, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.rpc.bind_port", ConfigType.INTEGER, "9483", 1L, 65_535L, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.rpc.max_frame_bytes", ConfigType.INTEGER, Integer.toString((int) MIB), 1024L, MIB, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.rpc.max_message_bytes", ConfigType.INTEGER, Integer.toString(8 * (int) MIB), 1024L, 64L * MIB, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.rpc.max_streams", ConfigType.INTEGER, "256", 1L, 65_535L, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.rpc.inbound_bytes", ConfigType.LONG, Long.toString(64L * MIB), MIB, 8L * GIB, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.rpc.outbound_bytes", ConfigType.LONG, Long.toString(64L * MIB), MIB, 8L * GIB, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.rpc.default_timeout_seconds", ConfigType.INTEGER, "30", 1L, 3600L, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.raft.election_timeout_min_millis", ConfigType.LONG, "300", 50L, 600_000L, ConfigScope.CLUSTER_WIDE, true, false, false);
        add(settings, "aether.raft.election_timeout_max_millis", ConfigType.LONG, "600", 50L, 600_000L, ConfigScope.CLUSTER_WIDE, true, false, false);
        add(settings, "aether.raft.heartbeat_interval_millis", ConfigType.LONG, "100", 10L, 60_000L, ConfigScope.CLUSTER_WIDE, true, false, false);
        add(settings, "aether.raft.snapshot_threshold_entries", ConfigType.LONG, "100000", 1L, Long.MAX_VALUE, ConfigScope.CLUSTER_WIDE, true, false, false);
        add(settings, "aether.raft.max_uncommitted_bytes", ConfigType.LONG, Long.toString(256L * MIB), MIB, 8L * GIB, ConfigScope.CLUSTER_WIDE, true, false, false);

        add(settings, "aether.backup.path", ConfigType.STRING, "", null, null, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.backup.bandwidth_bytes_per_second", ConfigType.LONG, Long.toString(128L * MIB), 0L, 16L * GIB, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.security.profile", ConfigType.STRING, "production", null, null, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.security.transport.tls.enabled", ConfigType.BOOLEAN, "true", null, null, ConfigScope.NODE_LOCAL, false, true, false);
        add(settings, "aether.security.node.mtls.required", ConfigType.BOOLEAN, "true", null, null, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.security.audit.fail_open", ConfigType.BOOLEAN, "false", null, null, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.security.authorization.required", ConfigType.BOOLEAN, "true", null, null, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.security.encryption.at_rest.enabled", ConfigType.BOOLEAN, "true", null, null, ConfigScope.CLUSTER_WIDE, false, true, false);
        add(settings, "aether.security.encryption.kek.provider", ConfigType.STRING, "", null, null, ConfigScope.NODE_LOCAL, false, true, true);

        add(settings, "aether.observability.metrics.enabled", ConfigType.BOOLEAN, "true", null, null, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.observability.tracing.enabled", ConfigType.BOOLEAN, "false", null, null, ConfigScope.NODE_LOCAL, true, false, false);
        add(settings, "aether.observability.log.level", ConfigType.STRING, "INFO", null, null, ConfigScope.NODE_LOCAL, true, false, false);

        add(settings, "aether.resource.memory_budget_bytes", ConfigType.LONG, Long.toString(512L * MIB), 32L * MIB, 64L * GIB, ConfigScope.NODE_LOCAL, true, false, false);
        return new AetherConfigRegistry(settings);
    }

    public Map<String, ConfigSetting> settings() {
        return settings;
    }

    public ConfigSetting require(String name) {
        ConfigSetting setting = settings.get(name);
        if (setting == null) throw new IllegalArgumentException("unknown setting: " + name);
        return setting;
    }

    private static void add(
            Map<String, ConfigSetting> settings,
            String name,
            ConfigType type,
            String defaultValue,
            Long minimum,
            Long maximum,
            ConfigScope scope,
            boolean hotReloadable,
            boolean restartRequired,
            boolean securitySensitive) {
        settings.put(
                name,
                new ConfigSetting(
                        name,
                        type,
                        defaultValue,
                        minimum,
                        maximum,
                        scope,
                        hotReloadable,
                        restartRequired,
                        securitySensitive,
                        1,
                        java.util.List.of(),
                        validationErrorCode(name)));
    }

    private static String validationErrorCode(String name) {
        return "CFG_" + name.substring("aether.".length()).toUpperCase(java.util.Locale.ROOT).replace('.', '_');
    }
}
