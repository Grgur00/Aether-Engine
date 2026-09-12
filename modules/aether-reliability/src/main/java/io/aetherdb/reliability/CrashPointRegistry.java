package io.aetherdb.reliability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local crash-point dispatcher disabled by default. */
public final class CrashPointRegistry {
    private static final java.util.regex.Pattern VALID_ID =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+");
    private static final CrashPoint DISABLED = (ignored, context) -> {};
    private static final AtomicReference<CrashPoint> ACTIVE = new AtomicReference<>(DISABLED);

    private CrashPointRegistry() {}

    public static void hit(String id) {
        hit(id, CrashContext.EMPTY);
    }

    public static void hit(String id, CrashContext context) {
        validateId(id);
        ACTIVE.get().hit(id, Objects.requireNonNull(context, "context"));
    }

    public static ScopedCrashPoint install(CrashPoint crashPoint) {
        Objects.requireNonNull(crashPoint, "crashPoint");
        CrashPoint previous = ACTIVE.getAndSet(crashPoint);
        return new ScopedCrashPoint(previous);
    }

    static void restore(CrashPoint previous) {
        ACTIVE.set(previous);
    }

    public static String validateId(String id) {
        if (id == null || !VALID_ID.matcher(id).matches())
            throw new IllegalArgumentException("invalid crash point id");
        return id;
    }
}
