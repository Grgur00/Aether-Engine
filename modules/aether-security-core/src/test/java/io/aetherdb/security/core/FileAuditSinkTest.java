package io.aetherdb.security.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.security.api.AuditEvent;
import io.aetherdb.security.api.AuditUnavailableException;
import io.aetherdb.security.api.PrincipalKind;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileAuditSinkTest {
    @TempDir Path temporary;

    @Test
    void appendsRedactedJsonLines() throws Exception {
        Path audit = temporary.resolve("audit").resolve("events.jsonl");
        AuditEvent event =
                new AuditEvent(
                        Instant.EPOCH,
                        UUID.fromString("11111111-1111-1111-1111-111111111111"),
                        "cluster-secret",
                        "node-secret",
                        "principal-secret",
                        PrincipalKind.CLIENT,
                        "backup.restore",
                        "backup",
                        "backup-secret",
                        "DENY",
                        "RBAC_DENY_DEFAULT",
                        "request-1",
                        "trace-1");

        try (FileAuditSink sink = new FileAuditSink(audit, true)) {
            sink.record(event);
            sink.record(event);
        }

        String contents = Files.readString(audit);
        assertThat(contents.lines()).hasSize(2);
        assertThat(contents).contains("\"operation\":\"backup.restore\"");
        assertThat(contents).doesNotContain("cluster-secret");
        assertThat(contents).doesNotContain("backup-secret");
    }

    @Test
    void failsClosedAfterClose() throws Exception {
        Path audit = temporary.resolve("events.jsonl");
        FileAuditSink sink = new FileAuditSink(audit, false);
        sink.close();

        AuditEvent event =
                new AuditEvent(
                        Instant.EPOCH,
                        UUID.randomUUID(),
                        "c",
                        "n",
                        "p",
                        PrincipalKind.CLIENT,
                        "security.policy.update",
                        "policy",
                        "root",
                        "ALLOW",
                        "TEST",
                        "",
                        "");

        assertThatThrownBy(() -> sink.record(event))
                .isInstanceOf(AuditUnavailableException.class)
                .hasMessageContaining("append");
    }
}
