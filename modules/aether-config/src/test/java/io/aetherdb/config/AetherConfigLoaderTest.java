package io.aetherdb.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class AetherConfigLoaderTest {
    @TempDir Path temporary;

    @Test
    void appliesFileEnvironmentAndOverridePrecedence() throws Exception {
        Path file = temporary.resolve("aether.properties");
        Files.writeString(
                file,
                """
                aether.security.profile=development
                aether.rpc.max_frame_bytes=4096
                aether.security.encryption.kek.provider=file-value
                """);
        AetherConfigLoader loader = AetherConfigLoader.defaults();

        AetherConfiguration configuration =
                loader.load(
                        file,
                        Map.of(
                                "AETHER_RPC_MAX_FRAME_BYTES",
                                "8192",
                                "AETHER_SECURITY_ENCRYPTION_KEK_PROVIDER",
                                "env-value"),
                        Map.of("aether.rpc.max_frame_bytes", "16384"));

        assertThat(configuration.get("aether.security.profile")).contains("development");
        assertThat(configuration.get("aether.security.encryption.kek.provider")).contains("env-value");
        assertThat(configuration.get("aether.rpc.max_frame_bytes")).contains("16384");
    }

    @Test
    void redactsSecuritySensitiveDiagnostics() throws Exception {
        AetherConfigLoader loader = AetherConfigLoader.defaults();
        AetherConfiguration configuration =
                loader.load(
                        null,
                        Map.of(),
                        Map.of("aether.security.encryption.kek.provider", "local-secret"));

        Map<String, String> diagnostics = loader.redactedDiagnosticView(configuration);

        assertThat(diagnostics.get("aether.security.encryption.kek.provider")).isEqualTo("REDACTED");
        assertThat(diagnostics).doesNotContainValue("local-secret");
    }
}
