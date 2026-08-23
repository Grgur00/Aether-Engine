package io.aetherdb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class ClusterConfigCompatibilityCheckerTest {
    private final ClusterConfigCompatibilityChecker checker = ClusterConfigCompatibilityChecker.defaults();

    @Test
    void acceptsMatchingClusterWideSettingsAcrossVotingMembers() {
        ClusterConfigCompatibilityReport report =
                checker.checkVotingMembers(
                        List.of(member("n1", Map.of()), member("n2", Map.of())),
                        Set.of());

        assertThat(report.compatible()).isTrue();
        assertThat(report.votingMembers()).isEqualTo(2);
        assertThat(report.issues()).isEmpty();
    }

    @Test
    void rejectsUnstagedClusterWideSettingMismatch() {
        ClusterConfigCompatibilityReport report =
                checker.checkVotingMembers(
                        List.of(
                                member("n1", Map.of("aether.rpc.max_frame_bytes", "8192")),
                                member("n2", Map.of("aether.rpc.max_frame_bytes", "16384"))),
                        Set.of());

        assertThat(report.compatible()).isFalse();
        assertThat(report.issues())
                .extracting(ClusterConfigCompatibilityIssue::code)
                .contains(ClusterConfigCompatibilityChecker.CLUSTER_SETTING_MISMATCH);
    }

    @Test
    void allowsExplicitlyStagedClusterWideSettingMismatch() {
        ClusterConfigCompatibilityReport report =
                checker.checkVotingMembers(
                        List.of(
                                member("n1", Map.of("aether.rpc.max_frame_bytes", "8192")),
                                member("n2", Map.of("aether.rpc.max_frame_bytes", "16384"))),
                        Set.of("aether.rpc.max_frame_bytes"));

        assertThat(report.compatible()).isTrue();
        assertThat(report.stagedSettings()).containsExactly("aether.rpc.max_frame_bytes");
    }

    @Test
    void stagedChangeProposalAllowsOnlyExpectedOldAndNewValues() {
        ClusterConfigCompatibilityReport report =
                checker.checkStagedChange(
                        List.of(
                                member("n1", Map.of("aether.rpc.max_frame_bytes", "8192")),
                                member("n2", Map.of("aether.rpc.max_frame_bytes", "16384"))),
                        new ClusterConfigChangeProposal(
                                "cfg-1",
                                List.of(
                                        new ClusterConfigSettingChange(
                                                "aether.rpc.max_frame_bytes", "8192", "16384"))));

        assertThat(report.compatible()).isTrue();
        assertThat(report.stagedSettings()).containsExactly("aether.rpc.max_frame_bytes");
    }

    @Test
    void stagedChangeProposalRejectsUnexpectedMemberValue() {
        ClusterConfigCompatibilityReport report =
                checker.checkStagedChange(
                        List.of(
                                member("n1", Map.of("aether.rpc.max_frame_bytes", "8192")),
                                member("n2", Map.of("aether.rpc.max_frame_bytes", "32768"))),
                        new ClusterConfigChangeProposal(
                                "cfg-1",
                                List.of(
                                        new ClusterConfigSettingChange(
                                                "aether.rpc.max_frame_bytes", "8192", "16384"))));

        assertThat(report.compatible()).isFalse();
        assertThat(report.issues())
                .extracting(ClusterConfigCompatibilityIssue::code)
                .contains(ClusterConfigCompatibilityChecker.STAGED_VALUE_OUTSIDE_PROPOSAL);
    }

    @Test
    void stagedChangeProposalRequiresMixedOldAndNewValuesDuringTransition() {
        ClusterConfigCompatibilityReport report =
                checker.checkStagedChange(
                        List.of(
                                member("n1", Map.of("aether.rpc.max_frame_bytes", "16384")),
                                member("n2", Map.of("aether.rpc.max_frame_bytes", "16384"))),
                        new ClusterConfigChangeProposal(
                                "cfg-1",
                                List.of(
                                        new ClusterConfigSettingChange(
                                                "aether.rpc.max_frame_bytes", "8192", "16384"))));

        assertThat(report.compatible()).isFalse();
        assertThat(report.issues())
                .extracting(ClusterConfigCompatibilityIssue::code)
                .contains(ClusterConfigCompatibilityChecker.STAGED_CHANGE_NOT_IN_PROGRESS);
    }

    @Test
    void rejectsInvalidMemberConfigAndInvalidStagedSetting() {
        ClusterConfigCompatibilityReport report =
                checker.checkVotingMembers(
                        List.of(member("n1", Map.of("aether.security.transport.tls.enabled", "false"))),
                        Set.of("aether.security.profile"));

        assertThat(report.compatible()).isFalse();
        assertThat(report.issues())
                .extracting(ClusterConfigCompatibilityIssue::code)
                .contains(
                        ClusterConfigCompatibilityChecker.CONFIG_VALIDATION_FAILED,
                        ClusterConfigCompatibilityChecker.INVALID_STAGED_SETTING);
    }

    @Test
    void rejectsInvalidMemberCompatibilityEpoch() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () ->
                                new ClusterConfigMember(
                                        "old-node",
                                        new AetherConfiguration(
                                                Map.of(
                                                        "aether.security.encryption.kek.provider",
                                                        "local-keystore:test")),
                                        0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("supportedCompatibilityEpoch");
    }

    private static ClusterConfigMember member(String nodeId, Map<String, String> values) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(values);
        merged.putIfAbsent("aether.security.encryption.kek.provider", "local-keystore:test");
        return new ClusterConfigMember(nodeId, new AetherConfiguration(merged), 1);
    }
}
