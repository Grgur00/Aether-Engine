package io.aetherdb.release;

import java.util.Objects;

/**
 * Non-negotiable release rule that prevents a production-ready label until resolved.
 *
 * @param code stable blocker code
 * @param description human-readable rule violation
 * @param severity blocker severity
 * @param resolved whether the release candidate has resolved the blocker
 */
public record ReleaseBlocker(
        String code, String description, ReleaseBlockerSeverity severity, boolean resolved) {
    public ReleaseBlocker {
        code = requireText(code, "code");
        description = requireText(description, "description");
        Objects.requireNonNull(severity, "severity");
    }

    boolean blocking() {
        return !resolved && severity == ReleaseBlockerSeverity.BLOCKER;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
