package io.aetherdb.benchmarks;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BenchmarkProfileRegistryTest {
    @Test
    void registryContainsAllRequiredChapter30Profiles() {
        assertThat(BenchmarkProfileRegistry.localProfiles())
                .extracting(BenchmarkProfile::id)
                .containsExactly(
                        "local.write.sequential.group_sync",
                        "local.write.random.group_sync",
                        "local.write.sync_per_write",
                        "local.write.async_wal",
                        "local.write.batch_matrix",
                        "local.read.point_warm",
                        "local.read.point_cold_open",
                        "local.read.range_scan",
                        "local.mixed.read_write",
                        "local.compaction_active",
                        "local.recovery.wal_replay",
                        "local.open.close",
                        "local.cache.hit_miss",
                        "local.checkpoint",
                        "local.restore_verify");
        assertThat(BenchmarkProfileRegistry.distributedProfiles())
                .extracting(BenchmarkProfile::id)
                .containsExactly(
                        "rpc.echo",
                        "rpc.write.leader",
                        "raft.write.replicated_3",
                        "raft.write.replicated_5",
                        "raft.read.linearizable",
                        "raft.read_follower_stale_if_enabled",
                        "raft.leader_failover",
                        "raft.snapshot_transfer",
                        "raft.membership_change");
    }

    @Test
    void resolvesKnownProfilesAndRejectsUnknownIds() {
        assertThat(BenchmarkProfileRegistry.find("local.read.range_scan"))
                .hasValueSatisfying(
                        profile -> assertThat(profile.scope()).isEqualTo(BenchmarkProfileScope.LOCAL));
        assertThat(BenchmarkProfileRegistry.find("missing")).isEmpty();
    }
}
