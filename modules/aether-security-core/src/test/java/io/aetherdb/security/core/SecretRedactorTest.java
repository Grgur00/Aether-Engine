package io.aetherdb.security.core;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class SecretRedactorTest {
    @Test
    void redactsWithoutLeakingOriginalSecret() {
        String redacted = SecretRedactor.redact("token", "super-secret-token");

        assertThat(redacted).startsWith("REDACTED:token:");
        assertThat(redacted).doesNotContain("super-secret-token");
    }

    @Test
    void redactionIsStableForCorrelation() {
        assertThat(SecretRedactor.redact("key", "same"))
                .isEqualTo(SecretRedactor.redact("key", "same"));
        assertThat(SecretRedactor.redact("key", "same"))
                .isNotEqualTo(SecretRedactor.redact("key", "different"));
    }
}
