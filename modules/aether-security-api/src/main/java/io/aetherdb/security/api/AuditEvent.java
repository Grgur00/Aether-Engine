package io.aetherdb.security.api;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Minimal structured audit event required for protected operations. */
public record AuditEvent(
        Instant eventTime,
        UUID eventId,
        String clusterId,
        String nodeId,
        String principalId,
        PrincipalKind principalKind,
        String operation,
        String resourceKind,
        String resourceId,
        String decision,
        String reasonCode,
        String requestId,
        String traceId) {
    public AuditEvent {
        Objects.requireNonNull(eventTime, "eventTime");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(principalKind, "principalKind");
        if (isBlank(operation) || isBlank(resourceKind) || isBlank(resourceId) || isBlank(decision)
                || isBlank(reasonCode)) {
            throw new IllegalArgumentException("audit event has blank mandatory field");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
