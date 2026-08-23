package io.aetherdb.config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Checks cluster-wide settings across voting members before startup or staged config changes. */
public final class ClusterConfigCompatibilityChecker {
    public static final String CONFIG_VALIDATION_FAILED = "CONFIG_VALIDATION_FAILED";
    public static final String CLUSTER_SETTING_MISMATCH = "CLUSTER_SETTING_MISMATCH";
    public static final String UNSUPPORTED_COMPATIBILITY_EPOCH = "UNSUPPORTED_COMPATIBILITY_EPOCH";
    public static final String INVALID_STAGED_SETTING = "INVALID_STAGED_SETTING";
    public static final String STAGED_VALUE_OUTSIDE_PROPOSAL = "STAGED_VALUE_OUTSIDE_PROPOSAL";
    public static final String STAGED_CHANGE_NOT_IN_PROGRESS = "STAGED_CHANGE_NOT_IN_PROGRESS";

    private final AetherConfigRegistry registry;
    private final AetherConfigValidator validator;

    public ClusterConfigCompatibilityChecker(AetherConfigRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        validator = new AetherConfigValidator(registry);
    }

    public ClusterConfigCompatibilityReport checkVotingMembers(
            List<ClusterConfigMember> members, Set<String> stagedSettings) {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        stagedSettings = Set.copyOf(Objects.requireNonNull(stagedSettings, "stagedSettings"));
        if (members.isEmpty()) throw new IllegalArgumentException("at least one voting member is required");

        List<ClusterConfigCompatibilityIssue> issues = new ArrayList<>();
        for (String stagedSetting : stagedSettings) {
            ConfigSetting setting = registry.settings().get(stagedSetting);
            if (setting == null || setting.scope() != ConfigScope.CLUSTER_WIDE) {
                issues.add(
                        new ClusterConfigCompatibilityIssue(
                                INVALID_STAGED_SETTING,
                                stagedSetting,
                                "",
                                "staged setting must be registered and cluster-wide"));
            }
        }

        for (ClusterConfigMember member : members) {
            try {
                validator.validate(member.configuration());
            } catch (ConfigValidationException failure) {
                issues.add(
                        new ClusterConfigCompatibilityIssue(
                                CONFIG_VALIDATION_FAILED,
                                "",
                                member.nodeId(),
                                failure.getMessage()));
            }
        }

        ClusterConfigMember baseline = members.getFirst();
        for (ConfigSetting setting : registry.settings().values()) {
            if (setting.scope() != ConfigScope.CLUSTER_WIDE) continue;
            for (ClusterConfigMember member : members) {
                if (member.supportedCompatibilityEpoch() < setting.compatibilityEpoch()) {
                    issues.add(
                            new ClusterConfigCompatibilityIssue(
                                    UNSUPPORTED_COMPATIBILITY_EPOCH,
                                    setting.name(),
                                    member.nodeId(),
                                    "member does not support setting compatibility epoch "
                                            + setting.compatibilityEpoch()));
                }
            }
            if (stagedSettings.contains(setting.name())) continue;
            String expected = baseline.configuration().getOrDefault(setting);
            for (ClusterConfigMember member : members.subList(1, members.size())) {
                String actual = member.configuration().getOrDefault(setting);
                if (!expected.equals(actual)) {
                    issues.add(
                            new ClusterConfigCompatibilityIssue(
                                    CLUSTER_SETTING_MISMATCH,
                                    setting.name(),
                                    member.nodeId(),
                                    "cluster-wide setting differs from baseline member "
                                            + baseline.nodeId()));
                }
            }
        }
        List<String> staged = stagedSettings.stream().sorted(Comparator.naturalOrder()).toList();
        return new ClusterConfigCompatibilityReport(issues.isEmpty(), members.size(), staged, issues);
    }

    public ClusterConfigCompatibilityReport checkStagedChange(
            List<ClusterConfigMember> members, ClusterConfigChangeProposal proposal) {
        members = List.copyOf(Objects.requireNonNull(members, "members"));
        proposal = Objects.requireNonNull(proposal, "proposal");
        ClusterConfigCompatibilityReport base = checkVotingMembers(members, proposal.settingNames());
        List<ClusterConfigCompatibilityIssue> issues = new ArrayList<>(base.issues());
        for (ClusterConfigSettingChange change : proposal.changes()) {
            ConfigSetting setting = registry.settings().get(change.settingName());
            if (setting == null || setting.scope() != ConfigScope.CLUSTER_WIDE) continue;
            boolean sawFrom = false;
            boolean sawTo = false;
            for (ClusterConfigMember member : members) {
                String value = member.configuration().getOrDefault(setting);
                if (value.equals(change.fromValue())) {
                    sawFrom = true;
                } else if (value.equals(change.toValue())) {
                    sawTo = true;
                } else {
                    issues.add(
                            new ClusterConfigCompatibilityIssue(
                                    STAGED_VALUE_OUTSIDE_PROPOSAL,
                                    change.settingName(),
                                    member.nodeId(),
                                    "member value is neither proposed old nor new value"));
                }
            }
            if (!sawFrom || !sawTo) {
                issues.add(
                        new ClusterConfigCompatibilityIssue(
                                STAGED_CHANGE_NOT_IN_PROGRESS,
                                change.settingName(),
                                "",
                                "staged change must include at least one old and one new member value"));
            }
        }
        return new ClusterConfigCompatibilityReport(
                issues.isEmpty(), members.size(), base.stagedSettings(), issues);
    }

    public static ClusterConfigCompatibilityChecker defaults() {
        return new ClusterConfigCompatibilityChecker(AetherConfigRegistry.defaults());
    }
}
