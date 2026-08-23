package io.aetherdb.benchmarks;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Canonical Chapter 30 benchmark profile registry. */
public final class BenchmarkProfileRegistry {
    private static final List<BenchmarkProfile> REQUIRED_PROFILES =
            List.of(
                    local("local.write.sequential.group_sync", "Sequential local writes with GROUP_SYNC durability"),
                    local("local.write.random.group_sync", "Random local writes with GROUP_SYNC durability"),
                    local("local.write.sync_per_write", "Local writes forced synchronously per operation"),
                    local("local.write.async_wal", "Local writes admitted with asynchronous WAL durability"),
                    local("local.write.batch_matrix", "Local write throughput and latency across batch sizes"),
                    local("local.read.point_warm", "Warm point-read workload"),
                    local("local.read.point_cold_open", "Cold-open point-read workload"),
                    local("local.read.range_scan", "Local ordered range-scan workload"),
                    local("local.mixed.read_write", "Mixed local read/write workload"),
                    local("local.compaction_active", "Local workload while compaction is active"),
                    local("local.recovery.wal_replay", "Recovery and WAL replay workload"),
                    local("local.open.close", "Database open/close workload"),
                    local("local.cache.hit_miss", "Cache hit/miss workload"),
                    local("local.checkpoint", "Checkpoint creation workload"),
                    local("local.restore_verify", "Restore and verification workload"),
                    distributed("rpc.echo", "RPC echo baseline"),
                    distributed("rpc.write.leader", "RPC writes routed to leader"),
                    distributed("raft.write.replicated_3", "Replicated writes on a three-node Raft group"),
                    distributed("raft.write.replicated_5", "Replicated writes on a five-node Raft group"),
                    distributed("raft.read.linearizable", "Linearizable Raft reads"),
                    distributed(
                            "raft.read_follower_stale_if_enabled",
                            "Follower stale reads when explicitly enabled"),
                    distributed("raft.leader_failover", "Leader failover workload"),
                    distributed("raft.snapshot_transfer", "Snapshot transfer workload"),
                    distributed("raft.membership_change", "Membership-change workload"));

    private static final Map<String, BenchmarkProfile> BY_ID = index(REQUIRED_PROFILES);

    private BenchmarkProfileRegistry() {}

    /** Returns all required Chapter 30 profiles in stable specification order. */
    public static List<BenchmarkProfile> requiredProfiles() {
        return REQUIRED_PROFILES;
    }

    /** Resolves a profile by stable identifier. */
    public static Optional<BenchmarkProfile> find(String id) {
        return Optional.ofNullable(BY_ID.get(id));
    }

    /** Returns required local profiles. */
    public static List<BenchmarkProfile> localProfiles() {
        return requiredProfiles().stream()
                .filter(profile -> profile.scope() == BenchmarkProfileScope.LOCAL)
                .toList();
    }

    /** Returns required distributed profiles. */
    public static List<BenchmarkProfile> distributedProfiles() {
        return requiredProfiles().stream()
                .filter(profile -> profile.scope() == BenchmarkProfileScope.DISTRIBUTED)
                .toList();
    }

    private static BenchmarkProfile local(String id, String description) {
        return new BenchmarkProfile(id, BenchmarkProfileScope.LOCAL, description);
    }

    private static BenchmarkProfile distributed(String id, String description) {
        return new BenchmarkProfile(id, BenchmarkProfileScope.DISTRIBUTED, description);
    }

    private static Map<String, BenchmarkProfile> index(List<BenchmarkProfile> profiles) {
        Map<String, BenchmarkProfile> byId = new LinkedHashMap<>();
        for (BenchmarkProfile profile : profiles) {
            if (byId.put(profile.id(), profile) != null) {
                throw new IllegalStateException("duplicate benchmark profile: " + profile.id());
            }
        }
        return Map.copyOf(byId);
    }
}
