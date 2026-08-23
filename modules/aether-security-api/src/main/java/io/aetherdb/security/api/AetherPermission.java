package io.aetherdb.security.api;

import java.util.Locale;

/** Permission name using the stable {@code domain.action} form. */
public record AetherPermission(String name) {
    private static final java.util.regex.Pattern PATTERN =
            java.util.regex.Pattern.compile("[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*");

    public AetherPermission {
        if (name == null || !PATTERN.matcher(name).matches())
            throw new IllegalArgumentException("invalid permission name");
        name = name.toLowerCase(Locale.ROOT);
    }

    public static AetherPermission of(String name) {
        return new AetherPermission(name);
    }
}
