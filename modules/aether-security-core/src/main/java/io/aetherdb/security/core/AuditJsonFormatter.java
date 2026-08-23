package io.aetherdb.security.core;

import io.aetherdb.security.api.AuditEvent;

/** Formats audit events as one-line JSON with sensitive identifiers redacted. */
public final class AuditJsonFormatter {
    private AuditJsonFormatter() {}

    public static String format(AuditEvent event) {
        StringBuilder json = new StringBuilder(384);
        json.append('{');
        append(json, "event_time", event.eventTime().toString()).append(',');
        append(json, "event_id", event.eventId().toString()).append(',');
        append(json, "cluster_id_hash", redactNullable("cluster", event.clusterId())).append(',');
        append(json, "node_id_hash", redactNullable("node", event.nodeId())).append(',');
        append(json, "principal_id_hash", redactNullable("principal", event.principalId()))
                .append(',');
        append(json, "principal_kind", event.principalKind().name()).append(',');
        append(json, "operation", event.operation()).append(',');
        append(json, "resource_kind", event.resourceKind()).append(',');
        append(json, "resource_id_hash", redactNullable("resource", event.resourceId())).append(',');
        append(json, "decision", event.decision()).append(',');
        append(json, "reason_code", event.reasonCode()).append(',');
        append(json, "request_id", event.requestId() == null ? "" : event.requestId()).append(',');
        append(json, "trace_id", event.traceId() == null ? "" : event.traceId());
        return json.append('}').toString();
    }

    private static String redactNullable(String kind, String value) {
        return value == null || value.isBlank() ? "" : SecretRedactor.redact(kind, value);
    }

    private static StringBuilder append(StringBuilder json, String name, String value) {
        return json.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String escape(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\b' -> escaped.append("\\b");
                case '\f' -> escaped.append("\\f");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20)
                        escaped.append("\\u").append("%04x".formatted((int) character));
                    else escaped.append(character);
                }
            }
        }
        return escaped.toString();
    }
}
