package io.aetherdb.benchmarks;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Chapter 30 benchmark profile runner entry point. */
public final class BenchmarkProfileRunner {
    private BenchmarkProfileRunner() {}

    /** Runs the profile runner CLI. */
    public static void main(String[] args) throws Exception {
        int exit = runCli(args);
        if (exit != 0) System.exit(exit);
    }

    /** Executes CLI arguments and returns a process-style exit code for tests and scripts. */
    public static int runCli(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--list")) {
            for (BenchmarkProfile profile : BenchmarkProfileRegistry.requiredProfiles()) {
                System.out.println(profile.id() + "\t" + profile.scope() + "\t" + profile.description());
            }
            return 0;
        }
        BenchmarkProfileRunPlan plan;
        try {
            plan = plan(args);
        } catch (IllegalArgumentException failure) {
            System.err.println(failure.getMessage());
            return 2;
        }
        if (!plan.executable()) {
            System.err.println("profile is registered but not executable yet: " + plan.profile().id());
            return 3;
        }
        CvBenchmark.main(plan.cvArguments().toArray(String[]::new));
        return 0;
    }

    /** Builds a deterministic run plan without executing the benchmark. */
    public static BenchmarkProfileRunPlan plan(String[] args) {
        String profileId = null;
        Path directory = null;
        Path output = null;
        long records = 100_000;
        int reads = 100_000;
        int crashPoints = 0;
        int batchSize = 1_000;
        int valueBytes = 256;
        String durability = "GROUP_SYNC";
        String cacheMode = "REOPENED_WARMUP";
        for (int index = 0; index < args.length; index++) {
            String option = args[index];
            if (index + 1 >= args.length) throw new IllegalArgumentException("missing value for " + option);
            String value = args[++index];
            switch (option) {
                case "--profile" -> profileId = value;
                case "--directory" -> directory = Path.of(value);
                case "--output" -> output = Path.of(value);
                case "--records" -> records = Long.parseLong(value);
                case "--reads" -> reads = Integer.parseInt(value);
                case "--crash-points" -> crashPoints = Integer.parseInt(value);
                case "--batch-size" -> batchSize = Integer.parseInt(value);
                case "--value-bytes" -> valueBytes = Integer.parseInt(value);
                case "--durability" -> durability = normalizeEnum(value);
                case "--cache-mode" -> cacheMode = normalizeEnum(value);
                default -> throw new IllegalArgumentException("unknown option: " + option);
            }
        }
        if (profileId == null) throw new IllegalArgumentException("--profile is required");
        String requestedProfileId = profileId;
        if (directory == null) throw new IllegalArgumentException("--directory is required");
        if (output == null) {
            output =
                    directory.toAbsolutePath().normalize().resolveSibling(directory.getFileName() + "-results.json");
        }
        BenchmarkProfile profile =
                BenchmarkProfileRegistry.find(requestedProfileId)
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "unknown profile: " + requestedProfileId));
        List<String> cvArguments = cvArguments(profile.id(), directory, output, records, reads, crashPoints, batchSize, valueBytes, durability, cacheMode);
        return new BenchmarkProfileRunPlan(profile, directory, output, cvArguments);
    }

    private static List<String> cvArguments(
            String profileId,
            Path directory,
            Path output,
            long records,
            int reads,
            int crashPoints,
            int batchSize,
            int valueBytes,
            String durability,
            String cacheMode) {
        if (!profileId.equals("local.write.sequential.group_sync")
                && !profileId.equals("local.read.point_warm")
                && !profileId.equals("local.recovery.wal_replay")) {
            return List.of();
        }
        if (profileId.equals("local.write.sequential.group_sync")) {
            reads = Math.max(1, Math.min(reads, 1_000));
            crashPoints = 0;
            durability = "GROUP_SYNC";
        } else if (profileId.equals("local.read.point_warm")) {
            crashPoints = 0;
            cacheMode = "REOPENED_WARMUP";
        } else if (profileId.equals("local.recovery.wal_replay")) {
            crashPoints = Math.max(1, crashPoints);
        }
        List<String> arguments = new ArrayList<>();
        add(arguments, "--directory", directory.toString());
        add(arguments, "--output", output.toString());
        add(arguments, "--records", Long.toString(records));
        add(arguments, "--reads", Integer.toString(reads));
        add(arguments, "--crash-points", Integer.toString(crashPoints));
        add(arguments, "--batch-size", Integer.toString(batchSize));
        add(arguments, "--value-bytes", Integer.toString(valueBytes));
        add(arguments, "--durability", durability);
        add(arguments, "--cache-mode", cacheMode);
        return List.copyOf(arguments);
    }

    private static void add(List<String> values, String option, String value) {
        values.add(option);
        values.add(value);
    }

    private static String normalizeEnum(String value) {
        return value.toUpperCase(Locale.ROOT).replace('-', '_');
    }
}
