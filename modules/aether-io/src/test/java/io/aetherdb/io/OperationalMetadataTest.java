package io.aetherdb.io;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.format.catalog.AetherFormatCatalog;
import io.aetherdb.format.catalog.FormatGoldenFixture;
import io.aetherdb.format.catalog.FormatGoldenFixtureCatalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

final class OperationalMetadataTest {
    @Test
    void identityIsExactRoundTripAndDetectsCorruption() {
        DatabaseIdentityV1 identity =
                new DatabaseIdentityV1(
                        UUID.fromString("12345678-1234-5678-9abc-def012345678"), 42, 1, 7);
        byte[] encoded = identity.encode();
        assertThat(encoded).hasSize(128);
        assertThat(DatabaseIdentityV1.decode(encoded)).isEqualTo(identity);
        encoded[60] = 1;
        assertThatThrownBy(() -> DatabaseIdentityV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void identityMatchesGoldenFixtureCatalog() {
        DatabaseIdentityV1 identity =
                new DatabaseIdentityV1(
                        UUID.fromString("12345678-1234-5678-9abc-def012345678"), 42, 1, 7);
        byte[] encoded = identity.encode();
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.database_identity.v1", "canonical-db-identity-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(DatabaseIdentityV1.decode(encoded)).isEqualTo(identity);
    }

    @Test
    void formatOptionsAreExactRoundTripWithStableFingerprint() {
        FormatOptionsV1 options =
                new FormatOptionsV1(UUID.fromString("12345678-1234-5678-9abc-def012345678"), 42);
        byte[] encoded = options.encode();
        assertThat(encoded).hasSize(4_096);
        assertThat(options.compatibilityFingerprint()).hasSize(32);
        assertThat(FormatOptionsV1.decode(encoded)).isEqualTo(options);
        encoded[600] = 1;
        assertThatThrownBy(() -> FormatOptionsV1.decode(encoded))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void formatOptionsMatchGoldenFixtureCatalog() {
        FormatOptionsV1 options =
                new FormatOptionsV1(UUID.fromString("12345678-1234-5678-9abc-def012345678"), 42);
        byte[] encoded = options.encode();
        FormatGoldenFixture fixture =
                FormatGoldenFixtureCatalog.current(AetherFormatCatalog.current())
                        .require("aether.format_options.v1", "canonical-format-options-v1");

        assertThat(encoded).hasSize(fixture.byteLength());
        assertThat(sha256Hex(encoded)).isEqualTo(fixture.sha256Hex());
        assertThat(FormatOptionsV1.decode(encoded)).isEqualTo(options);
    }

    @Test
    void exclusiveLockPreventsSecondOwnerAndLeavesFile(@TempDir Path root) throws IOException {
        try (DatabaseLock first = DatabaseLock.acquire(root)) {
            assertThat(first).isNotNull();
            assertThatThrownBy(() -> DatabaseLock.acquire(root))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("already held");
        }
        assertThat(Files.isRegularFile(root.resolve("LOCK"))).isTrue();
        try (DatabaseLock second = DatabaseLock.acquire(root)) {
            assertThat(second).isNotNull();
        }
    }

    @Test
    void managedSymlinkIsRejected(@TempDir Path root) throws IOException {
        Path target = Files.createTempFile("aether-lock-target", ".tmp");
        try {
            Files.createSymbolicLink(root.resolve("CURRENT"), target);
            assertThatThrownBy(() -> PathSecurityValidator.validateRoot(root, true))
                    .isInstanceOf(IOException.class)
                    .hasMessageContaining("symbolic link");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    private static String sha256Hex(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }
}
