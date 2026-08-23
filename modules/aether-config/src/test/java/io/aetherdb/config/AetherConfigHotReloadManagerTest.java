package io.aetherdb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class AetherConfigHotReloadManagerTest {
    private final AetherConfigHotReloadManager manager = AetherConfigHotReloadManager.defaults();

    @Test
    void appliesOnlyHotReloadableSettingChanges() {
        AetherConfiguration current =
                new AetherConfiguration(
                        Map.of(
                                "aether.security.encryption.kek.provider",
                                "local-keystore:test",
                                "aether.raft.heartbeat_interval_millis",
                                "100"));
        AetherConfiguration proposed =
                new AetherConfiguration(
                        Map.of(
                                "aether.security.encryption.kek.provider",
                                "local-keystore:test",
                                "aether.raft.heartbeat_interval_millis",
                                "125"));

        ConfigReloadDecision decision = manager.evaluate(current, proposed);

        assertThat(decision.accepted()).isTrue();
        assertThat(decision.appliedChanges())
                .extracting(ConfigReloadChange::name)
                .containsExactly("aether.raft.heartbeat_interval_millis");
        assertThat(decision.errors()).isEmpty();
    }

    @Test
    void deniesRestartRequiredAndRedactsSensitiveChanges() {
        AetherConfiguration current =
                new AetherConfiguration(
                        Map.of("aether.security.encryption.kek.provider", "local-keystore:old"));
        AetherConfiguration proposed =
                new AetherConfiguration(
                        Map.of("aether.security.encryption.kek.provider", "local-keystore:new"));

        ConfigReloadDecision decision = manager.evaluate(current, proposed);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.deniedChanges()).hasSize(1);
        ConfigReloadChange change = decision.deniedChanges().getFirst();
        assertThat(change.name()).isEqualTo("aether.security.encryption.kek.provider");
        assertThat(change.previousValue()).isEqualTo("REDACTED");
        assertThat(change.nextValue()).isEqualTo("REDACTED");
        assertThat(change.reason()).isEqualTo("restart required");
    }

    @Test
    void rejectsInvalidProposedConfigurationBeforeApplyingChanges() {
        AetherConfiguration current =
                new AetherConfiguration(
                        Map.of("aether.security.encryption.kek.provider", "local-keystore:test"));
        AetherConfiguration proposed =
                new AetherConfiguration(
                        Map.of(
                                "aether.security.encryption.kek.provider",
                                "local-keystore:test",
                                "aether.raft.heartbeat_interval_millis",
                                "200",
                                "aether.raft.election_timeout_min_millis",
                                "300"));

        ConfigReloadDecision decision = manager.evaluate(current, proposed);

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.changes()).isEmpty();
        assertThat(decision.errors()).containsExactly("election timeout minimum must exceed heartbeat interval by more than 2x");
    }
}
