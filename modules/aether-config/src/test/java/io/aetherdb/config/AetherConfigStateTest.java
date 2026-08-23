package io.aetherdb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class AetherConfigStateTest {
    @Test
    void acceptedHotReloadAtomicallyUpdatesCurrentConfiguration() {
        AetherConfigState state =
                AetherConfigState.defaults(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.encryption.kek.provider",
                                        "local-keystore:test",
                                        "aether.raft.heartbeat_interval_millis",
                                        "100")));

        ConfigReloadDecision decision =
                state.reload(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.encryption.kek.provider",
                                        "local-keystore:test",
                                        "aether.raft.heartbeat_interval_millis",
                                        "125")));

        assertThat(decision.accepted()).isTrue();
        assertThat(state.current().get("aether.raft.heartbeat_interval_millis")).contains("125");
    }

    @Test
    void deniedHotReloadLeavesCurrentConfigurationUnchanged() {
        AetherConfigState state =
                AetherConfigState.defaults(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.encryption.kek.provider",
                                        "local-keystore:old")));

        ConfigReloadDecision decision =
                state.reload(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.encryption.kek.provider",
                                        "local-keystore:new")));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.deniedChanges()).extracting(ConfigReloadChange::reason).containsExactly("restart required");
        assertThat(state.current().get("aether.security.encryption.kek.provider"))
                .contains("local-keystore:old");
    }

    @Test
    void invalidHotReloadLeavesCurrentConfigurationUnchanged() {
        AetherConfigState state =
                AetherConfigState.defaults(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.encryption.kek.provider",
                                        "local-keystore:test",
                                        "aether.raft.heartbeat_interval_millis",
                                        "100")));

        ConfigReloadDecision decision =
                state.reload(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.encryption.kek.provider",
                                        "local-keystore:test",
                                        "aether.raft.heartbeat_interval_millis",
                                        "200",
                                        "aether.raft.election_timeout_min_millis",
                                        "300")));

        assertThat(decision.accepted()).isFalse();
        assertThat(decision.errors()).containsExactly("election timeout minimum must exceed heartbeat interval by more than 2x");
        assertThat(state.current().get("aether.raft.heartbeat_interval_millis")).contains("100");
    }
}
