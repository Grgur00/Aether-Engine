package io.aetherdb.reliability;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Crash point that throws when a specific ID has been hit a configured number of times. */
public final class TriggeringCrashPoint implements CrashPoint {
    private final String targetId;
    private final int triggerOnHit;
    private final AtomicInteger hits = new AtomicInteger();

    public TriggeringCrashPoint(String targetId, int triggerOnHit) {
        this.targetId = CrashPointRegistry.validateId(targetId);
        if (triggerOnHit <= 0) throw new IllegalArgumentException("trigger hit must be positive");
        this.triggerOnHit = triggerOnHit;
    }

    @Override
    public void hit(String id, CrashContext context) {
        Objects.requireNonNull(context, "context");
        if (!targetId.equals(id)) return;
        if (hits.incrementAndGet() == triggerOnHit) throw new CrashPointException(id);
    }

    public int hits() {
        return hits.get();
    }
}
