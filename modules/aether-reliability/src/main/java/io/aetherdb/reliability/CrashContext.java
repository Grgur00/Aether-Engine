package io.aetherdb.reliability;

import java.util.Map;
import java.util.Objects;

/** Immutable context supplied to a crash point. */
public record CrashContext(Map<String, String> attributes) {
    public static final CrashContext EMPTY = new CrashContext(Map.of());

    public CrashContext {
        Objects.requireNonNull(attributes, "attributes");
        for (Map.Entry<String, String> entry : attributes.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank())
                throw new IllegalArgumentException("blank crash context key");
            if (entry.getValue() == null)
                throw new IllegalArgumentException("null crash context value");
        }
        attributes = Map.copyOf(attributes);
    }

    public static CrashContext of(String key, String value) {
        return new CrashContext(Map.of(key, value));
    }
}
