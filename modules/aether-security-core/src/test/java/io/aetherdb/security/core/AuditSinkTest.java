package io.aetherdb.security.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.security.api.AuditEvent;
import io.aetherdb.security.api.AuditSink;
import io.aetherdb.security.api.AuditUnavailableException;
import io.aetherdb.security.api.PrincipalKind;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

final class AuditSinkTest {
    private static final AuditEvent EVENT =
            new AuditEvent(
                    Instant.EPOCH,
                    UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    "cluster-secret",
                    "node-secret",
                    "principal-secret",
                    PrincipalKind.CLIENT,
                    "security.key.rotate",
                    "key",
                    "key-secret",
                    "ALLOW",
                    "TEST",
                    "request-1",
                    "trace-1");

    @Test
    void jsonFormatterRedactsSensitiveIdentifiers() {
        String json = AuditJsonFormatter.format(EVENT);

        assertThat(json).contains("\"operation\":\"security.key.rotate\"");
        assertThat(json).contains("\"cluster_id_hash\":\"REDACTED:cluster:");
        assertThat(json).doesNotContain("cluster-secret");
        assertThat(json).doesNotContain("principal-secret");
        assertThat(json).doesNotContain("key-secret");
    }

    @Test
    void inMemorySinkRetainsEventsForTestsAndDevelopment() {
        InMemoryAuditSink sink = new InMemoryAuditSink();

        sink.record(EVENT);

        assertThat(sink.events()).containsExactly(EVENT);
    }

    @Test
    void compositeSinkFailsClosedWhenDelegateFails() {
        InMemoryAuditSink successful = new InMemoryAuditSink();
        AuditSink failing =
                ignored -> {
                    throw new AuditUnavailableException("down");
                };
        CompositeAuditSink sink = new CompositeAuditSink(List.of(successful, failing));

        assertThatThrownBy(() -> sink.record(EVENT))
                .isInstanceOf(AuditUnavailableException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(1));
        assertThat(successful.events()).containsExactly(EVENT);
    }
}
