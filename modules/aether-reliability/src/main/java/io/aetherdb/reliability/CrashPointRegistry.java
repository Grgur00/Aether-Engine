package io.aetherdb.reliability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Process-local crash-point dispatcher disabled by default. */
public final class CrashPointRegistry {
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
        if (id == null || !id.matches("[a-z][a-z0-9_]*(?:\\.[a-z][a-z0-9_]*)+"))
            throw new IllegalArgumentException("invalid crash point id");
        return id;
    }
}
