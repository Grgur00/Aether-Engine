package io.aetherdb.training.cache;

import java.nio.file.Path;

/** Populates deterministic values consumed by the Python pipeline benchmark. */
public final class TrainingCachePopulate {
    private TrainingCachePopulate() {}

    public static void main(String[] arguments) {
        if (arguments.length < 1 || arguments.length > 3)
            throw new IllegalArgumentException("usage: <directory> [samples] [payloadBytes]");
        Path directory = Path.of(arguments[0]);
        int samples = arguments.length > 1 ? Integer.parseInt(arguments[1]) : 128;
        int payloadBytes = arguments.length > 2 ? Integer.parseInt(arguments[2]) : 1024 * 1024;
        if (samples < 1 || payloadBytes < 1 || payloadBytes % 4 != 0)
            throw new IllegalArgumentException("samples and payloadBytes are invalid; payloadBytes must be divisible by four");
        TransformationFingerprint transform = TransformationFingerprint.ofCanonicalDescriptor("python-training-pipeline-v1");
        try (TrainingCache cache = TrainingCache.open(directory)) {
            for (int sample = 0; sample < samples; sample++) {
                byte[] value = new byte[payloadBytes];
                for (int index = 0; index < value.length; index++) value[index] = (byte) (sample + index);
                cache.put(new CacheKey("python-benchmark", "sample-" + sample, transform), value);
                if ((sample + 1) % Math.max(1, samples / 10) == 0)
                    System.out.printf("populated %d/%d%n", sample + 1, samples);
            }
        }
    }
}
