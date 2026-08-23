package io.aetherdb.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class AetherConfigValidatorTest {
    @Test
    void acceptsSafeProductionConfiguration() {
        AetherConfigValidator.validate(
                Map.of("aether.security.encryption.kek.provider", "local-keystore:test"));
    }

    @Test
    void rejectsProductionPlaintextTransport() {
        assertThatThrownBy(
                        () ->
                                AetherConfigValidator.validate(
                                        Map.of(
                                                "aether.security.transport.tls.enabled",
                                                "false",
                                                "aether.security.encryption.kek.provider",
                                                "local-keystore:test")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("tls.enabled");
    }

    @Test
    void rejectsProductionEncryptionWithoutKeyProvider() {
        assertThatThrownBy(() -> AetherConfigValidator.validate(Map.of()))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("KEK provider");
    }

    @Test
    void allowsDevelopmentWithoutKeyProvider() {
        AetherConfigValidator.validate(Map.of("aether.security.profile", "development"));
    }

    @Test
    void rejectsUnsafeRaftTiming() {
        assertThatThrownBy(
                        () ->
                                AetherConfigValidator.validate(
                                        Map.of(
                                                "aether.security.profile",
                                                "development",
                                                "aether.raft.heartbeat_interval_millis",
                                                "100",
                                                "aether.raft.election_timeout_min_millis",
                                                "200")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("election timeout");
    }

    @Test
    void rejectsInvalidDurabilityMode() {
        assertThatThrownBy(
                        () ->
                                AetherConfigValidator.validate(
                                        Map.of(
                                                "aether.security.profile",
                                                "development",
                                                "aether.wal.durability_mode",
                                                "ASYNC")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("aether.wal.durability_mode");
    }

    @Test
    void rejectsRpcMessageLimitBelowFrameLimit() {
        assertThatThrownBy(
                        () ->
                                AetherConfigValidator.validate(
                                        Map.of(
                                                "aether.security.profile",
                                                "development",
                                                "aether.rpc.max_frame_bytes",
                                                "65536",
                                                "aether.rpc.max_message_bytes",
                                                "32768")))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("rpc max message");
    }

    @Test
    void rejectsMemoryConsumersAboveBudget() {
        assertThatThrownBy(
                        () ->
                                AetherConfigValidator.validate(
                                        Map.of(
                                                "aether.security.profile",
                                                "development",
                                                "aether.resource.memory_budget_bytes",
                                                Long.toString(32L * 1024L * 1024L),
                                                "aether.block_cache.bytes",
                                                Long.toString(24L * 1024L * 1024L),
                                                "aether.memtable.native_bytes",
                                                Long.toString(16L * 1024L * 1024L))))
                .isInstanceOf(ConfigValidationException.class)
                .hasMessageContaining("memory budget");
    }

    @Test
    void registryContainsRequiredChapter32SettingsWithValidationMetadata() {
        AetherConfigRegistry registry = AetherConfigRegistry.defaults();

        assertThat(registry.settings().keySet())
                .containsAll(
                        List.of(
                                "aether.storage.path",
                                "aether.storage.lock.timeout_seconds",
                                "aether.wal.durability_mode",
                                "aether.wal.segment_bytes",
                                "aether.wal.force_timeout_seconds",
                                "aether.memtable.native_bytes",
                                "aether.memtable.immutable_limit",
                                "aether.sstable.target_data_block_bytes",
                                "aether.block_cache.bytes",
                                "aether.compaction.enabled",
                                "aether.compaction.max_background_jobs",
                                "aether.compaction.bandwidth_bytes_per_second",
                                "aether.snapshots.max_open",
                                "aether.rpc.bind_host",
                                "aether.rpc.bind_port",
                                "aether.rpc.max_frame_bytes",
                                "aether.rpc.max_message_bytes",
                                "aether.rpc.max_streams",
                                "aether.rpc.inbound_bytes",
                                "aether.rpc.outbound_bytes",
                                "aether.rpc.default_timeout_seconds",
                                "aether.raft.election_timeout_min_millis",
                                "aether.raft.election_timeout_max_millis",
                                "aether.raft.heartbeat_interval_millis",
                                "aether.raft.snapshot_threshold_entries",
                                "aether.raft.max_uncommitted_bytes",
                                "aether.backup.path",
                                "aether.backup.bandwidth_bytes_per_second",
                                "aether.security.profile",
                                "aether.security.transport.tls.enabled",
                                "aether.security.node.mtls.required",
                                "aether.security.audit.fail_open",
                                "aether.security.authorization.required",
                                "aether.security.encryption.at_rest.enabled",
                                "aether.security.encryption.kek.provider",
                                "aether.observability.metrics.enabled",
                                "aether.observability.tracing.enabled",
                                "aether.observability.log.level",
                                "aether.resource.memory_budget_bytes"));
        assertThat(registry.settings().values())
                .allSatisfy(
                        setting -> {
                            assertThat(setting.validationErrorCode()).startsWith("CFG_");
                            assertThat(setting.compatibilityEpoch()).isPositive();
                            assertThat(setting.unsafeCombinations()).isNotNull();
                        });
    }

    @Test
    void exposesSecuritySensitiveSettings() {
        AetherConfigRegistry registry = AetherConfigRegistry.defaults();

        assertThat(registry.require("aether.security.encryption.kek.provider").securitySensitive())
                .isTrue();
    }
}
