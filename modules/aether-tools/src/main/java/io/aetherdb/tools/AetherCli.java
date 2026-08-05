package io.aetherdb.tools;

import io.aetherdb.io.DatabaseIdentityV1;
import io.aetherdb.io.DatabaseLock;
import io.aetherdb.io.FormatOptionsV1;
import io.aetherdb.io.PathSecurityValidator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/** Safe operational CLI foundation for format identity inspection and metadata verification. */
public final class AetherCli {
    private static final String VERSION = "0.1.0-dev";
    private AetherCli() {}

    public static void main(String[] arguments) {
        int exit = run(arguments);
        if (exit != 0) System.exit(exit);
    }

    static int run(String[] arguments) {
        if (arguments.length == 0 || arguments[0].equals("help") || arguments[0].equals("--help")) { usage(); return 0; }
        try {
            return switch (arguments[0]) {
                case "version", "--version" -> { System.out.println("Aether Engine " + VERSION + " (format epoch 1)"); yield 0; }
                case "inspect" -> inspect(arguments);
                case "verify" -> verify(arguments);
                default -> { System.err.println("Unknown command: " + arguments[0]); usage(); yield 64; }
            };
        } catch (IOException failure) {
            System.err.println("I/O error: " + failure.getMessage()); return 4;
        } catch (IllegalArgumentException failure) {
            System.err.println("Invalid or corrupt metadata: " + failure.getMessage()); return 3;
        }
    }

    private static int inspect(String[] arguments) throws IOException {
        Arguments parsed = Arguments.parse(arguments);
        Path root = PathSecurityValidator.validateRoot(parsed.path, true);
        try (DatabaseLock databaseLock = DatabaseLock.acquire(root)) {
            java.util.Objects.requireNonNull(databaseLock);
            DatabaseIdentityV1 identity = DatabaseIdentityV1.decode(Files.readAllBytes(root.resolve("DB-IDENTITY")));
            FormatOptionsV1 options = FormatOptionsV1.decode(Files.readAllBytes(root.resolve("FORMAT-OPTIONS")));
            if (!identity.databaseId().equals(options.databaseId())) throw new IllegalArgumentException("identity/options UUID mismatch");
            if (parsed.json) {
                System.out.printf("{\"databaseUuid\":\"%s\",\"formatEpoch\":1,\"creatorVersion\":\"%d.%d\",\"creationEpochMillis\":%d,\"fingerprint\":\"%s\"}%n",
                        identity.databaseId(), identity.creatorMajor(), identity.creatorMinor(), identity.creationEpochMillis(),
                        java.util.HexFormat.of().formatHex(options.compatibilityFingerprint()));
            } else {
                System.out.println("Database UUID: " + identity.databaseId());
                System.out.println("Format epoch: 1");
                System.out.println("Creator version: " + identity.creatorMajor() + "." + identity.creatorMinor());
                System.out.println("Created: " + java.time.Instant.ofEpochMilli(identity.creationEpochMillis()));
                System.out.println("Compatibility fingerprint: " + java.util.HexFormat.of().formatHex(options.compatibilityFingerprint()));
                System.out.println("Metadata status: valid");
            }
        }
        return 0;
    }

    private static int verify(String[] arguments) throws IOException {
        int result = inspect(arguments);
        if (result == 0) System.out.println("Verification level METADATA completed successfully.");
        return result;
    }

    private static void usage() {
        System.out.println("Usage:");
        System.out.println("  aether version");
        System.out.println("  aether inspect <database-directory> [--json]");
        System.out.println("  aether verify <database-directory> [--json]");
    }

    private record Arguments(Path path, boolean json) {
        static Arguments parse(String[] arguments) {
            if (arguments.length < 2) throw new IllegalArgumentException("database directory is required");
            boolean json = Arrays.asList(arguments).contains("--json");
            return new Arguments(Path.of(arguments[1]), json);
        }
    }
}
