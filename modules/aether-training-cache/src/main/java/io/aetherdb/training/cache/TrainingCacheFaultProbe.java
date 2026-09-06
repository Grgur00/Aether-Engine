package io.aetherdb.training.cache;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Subprocess probe. The external Python driver kills the writer at a named boundary. */
public final class TrainingCacheFaultProbe {
    private TrainingCacheFaultProbe() {}

    private static CacheKey key(String sample) {
        return new CacheKey("fault", sample, TransformationFingerprint.ofCanonicalDescriptor("fault-v1"));
    }

    private static byte[] payload(String sample) {
        byte[] value = new byte[512 * 1024];
        new java.util.Random(sample.hashCode()).nextBytes(value);
        return value;
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 4) throw new IllegalArgumentException("usage: <write|verify> <root> <point> <single|batch>");
        Path root = Path.of(arguments[1]);
        Path witness = root.resolve("acknowledged.txt");
        Path marker = root.resolve("boundary.txt");
        if (arguments[0].equals("write")) {
            Files.createDirectories(root);
            try (TrainingCache cache = TrainingCache.open(root.resolve("store"), 1L << 30, TrainingCacheDurability.DURABLE)) {
                cache.put(key("baseline"), payload("baseline"));
                Files.writeString(witness, "baseline\n");
                System.setProperty("aether.training.test.fault", arguments[2]);
                System.setProperty("aether.training.test.marker", marker.toString());
                if (arguments[3].equals("batch"))
                    cache.putMany(java.util.List.of(new CacheEntry(key("target"), payload("target")),
                            new CacheEntry(key("target-2"), payload("target-2"))));
                else cache.put(key("target"), payload("target"));
                // Records that the caller actually observed the API return.
                Files.writeString(witness, arguments[3].equals("batch") ? "baseline\ntarget\ntarget-2\n" : "baseline\ntarget\n");
                TrainingCacheFaultHooks.reach("after-ack");
            }
            throw new IllegalStateException("requested boundary was never reached");
        }
        if (!arguments[0].equals("verify")) throw new IllegalArgumentException("unknown probe action");
        var acknowledged = Files.readAllLines(witness);
        int visible = 0;
        int lost = 0;
        int corrupt = 0;
        int visibleTargets = 0;
        long orphanBytes = 0;
        long started = System.nanoTime();
        try (TrainingCache cache = TrainingCache.open(root.resolve("store"), 1L << 30, TrainingCacheDurability.DURABLE)) {
            long recoveryNanos = System.nanoTime() - started;
            java.util.Set<String> referenced = new java.util.HashSet<>();
            var samples = arguments[3].equals("batch") ? java.util.List.of("baseline", "target", "target-2")
                    : java.util.List.of("baseline", "target");
            for (String sample : samples) {
                byte[] actual = cache.get(key(sample));
                if (actual != null) {
                    visible++;
                    if (!sample.equals("baseline")) visibleTargets++;
                    if (!Arrays.equals(actual, payload(sample))) corrupt++;
                    SegmentReference reference = cache.getRef(key(sample));
                    if (reference != null) referenced.add(reference.segmentId());
                } else if (acknowledged.contains(sample)) lost++;
            }
            corrupt += Math.toIntExact(cache.metrics().corruptEntries());
            try (var paths = Files.list(root.resolve("store/segments"))) {
                for (Path file : paths.toList()) {
                    if (!referenced.contains(file.getFileName().toString())) orphanBytes += Files.size(file);
                }
            }
            boolean atomicBatch = !arguments[3].equals("batch") || visibleTargets == 0 || visibleTargets == 2;
            System.out.println("{\"visibleArtifacts\":" + visible + ",\"acknowledgedWrites\":" + acknowledged.size()
                    + ",\"lostAcknowledgedWrites\":" + lost + ",\"corruptArtifacts\":" + corrupt
                    + ",\"orphanBytes\":" + orphanBytes + ",\"recoveryMs\":" + recoveryNanos / 1e6
                    + ",\"targetCount\":" + (samples.size() - 1) + ",\"atomicBatchVisibility\":" + atomicBatch
                    + ",\"passed\":" + (lost == 0 && corrupt == 0 && atomicBatch) + "}");
        }
    }
}
