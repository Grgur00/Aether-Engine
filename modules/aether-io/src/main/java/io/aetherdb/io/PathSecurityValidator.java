package io.aetherdb.io;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Set;

/** Defensive validation for flat managed database paths without following symlinks. */
public final class PathSecurityValidator {
    private static final Set<String> EXACT_MANAGED = Set.of("DB-IDENTITY", "FORMAT-OPTIONS", "LOCK", "CURRENT", "CHECKPOINT");
    private PathSecurityValidator() {}

    public static Path validateRoot(Path root, boolean strict) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        if (strict && Files.isSymbolicLink(normalized)) throw new IOException("database root must not be a symbolic link");
        if (!Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) throw new IOException("database root is not a directory");
        try (var entries = Files.list(normalized)) {
            for (Path entry : entries.toList()) if (isManaged(entry.getFileName().toString())) {
                if (Files.isSymbolicLink(entry)) throw new IOException("managed path is a symbolic link: " + entry.getFileName());
                if (!Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)) throw new IOException("managed path is not a regular file: " + entry.getFileName());
            }
        }
        return normalized;
    }
    public static Path managed(Path root, String name) {
        if (!isManaged(name) || name.contains("/") || name.contains("\\")) throw new IllegalArgumentException("invalid managed file name");
        Path result = root.toAbsolutePath().normalize().resolve(name).normalize();
        if (!result.getParent().equals(root.toAbsolutePath().normalize())) throw new IllegalArgumentException("managed path escapes database root");
        return result;
    }
    public static boolean isManaged(String name) {
        return EXACT_MANAGED.contains(name) || name.startsWith("MANIFEST-") || name.startsWith("WAL-") || name.startsWith("SST-")
                || name.startsWith("DB-") || name.startsWith("FORMAT-") || name.startsWith("CHECKPOINT");
    }
}
