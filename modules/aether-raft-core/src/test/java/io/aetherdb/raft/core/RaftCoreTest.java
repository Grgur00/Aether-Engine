package io.aetherdb.raft.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.reliability.CrashPointException;
import io.aetherdb.reliability.CrashPointIds;
import io.aetherdb.reliability.CrashPointRegistry;
import io.aetherdb.reliability.ScopedCrashPoint;
import io.aetherdb.reliability.TriggeringCrashPoint;
import io.aetherdb.config.AetherConfiguration;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;

final class RaftCoreTest {
    @Test
    void quorumAndCurrentTermCommitRule() {
        assertThat(RaftQuorum.required(5)).isEqualTo(3);
        var tracker = new RaftCommitTracker();
        assertThat(tracker.recalculate(List.of(8L, 8L, 4L), 3, index -> 2)).isZero();
        assertThat(tracker.recalculate(List.of(8L, 8L, 4L), 3, index -> 3)).isEqualTo(8);
    }

    @Test
    void followerMatchIsMonotonic() {
        var progress = new FollowerProgress(10);
        progress.matched(7);
        progress.matched(5);
        assertThat(progress.matchIndex()).isEqualTo(7);
        assertThat(progress.nextIndex()).isEqualTo(8);
    }

    @Test
    void raftRuntimeConfigurationUsesChapter32Settings() {
        RaftRuntimeConfiguration configuration =
                RaftRuntimeConfiguration.from(
                        new AetherConfiguration(
                                Map.of(
                                        "aether.security.profile",
                                        "development",
                                        "aether.raft.heartbeat_interval_millis",
                                        "150",
                                        "aether.raft.election_timeout_min_millis",
                                        "400",
                                        "aether.raft.election_timeout_max_millis",
                                        "900",
                                        "aether.raft.snapshot_threshold_entries",
                                        "7",
                                        "aether.raft.max_uncommitted_bytes",
                                        Long.toString(4L * 1024L * 1024L))));

        assertThat(configuration.heartbeatInterval()).isEqualTo(Duration.ofMillis(150));
        assertThat(configuration.electionTimeoutMin()).isEqualTo(Duration.ofMillis(400));
        assertThat(configuration.electionTimeoutMax()).isEqualTo(Duration.ofMillis(900));
        assertThat(configuration.shouldSnapshot(6)).isFalse();
        assertThat(configuration.shouldSnapshot(7)).isTrue();
        assertThat(configuration.admitsUncommittedBytes(3L * 1024L * 1024L, 1024L)).isTrue();
        assertThat(configuration.admitsUncommittedBytes(4L * 1024L * 1024L, 1)).isFalse();
    }

    @Test
    void commitCrashPointFiresAfterMajorityAdvancesCommitIndex() {
        var tracker = new RaftCommitTracker();
        var crashPoint =
                new TriggeringCrashPoint(
                        CrashPointIds.RAFT_COMMIT_AFTER_MAJORITY_BEFORE_APPLY, 1);

        try (ScopedCrashPoint scope = CrashPointRegistry.install(crashPoint)) {
            assertThat(scope).isNotNull();
            assertThatThrownBy(() -> tracker.recalculate(List.of(8L, 8L, 4L), 3, index -> 3))
                    .isInstanceOf(CrashPointException.class)
                    .hasMessageContaining(CrashPointIds.RAFT_COMMIT_AFTER_MAJORITY_BEFORE_APPLY);
        }

        assertThat(crashPoint.hits()).isEqualTo(1);
        assertThat(tracker.commitIndex()).isEqualTo(8);
    }
}
