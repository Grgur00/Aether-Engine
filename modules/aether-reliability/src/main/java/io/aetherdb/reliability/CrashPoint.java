package io.aetherdb.reliability;

/** Hook invoked at deterministic failure-injection points. */
@FunctionalInterface
public interface CrashPoint {
    void hit(String id, CrashContext context);
}
