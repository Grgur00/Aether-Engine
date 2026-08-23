package io.aetherdb.client.api;

import java.util.UUID;

/** Stable application-level write response body carried inside successful RPC envelopes. */
public record ClientWriteResponse(
        ClientStatus status,
        UUID commandId,
        int operationCount,
        long firstSequence,
        long lastSequence,
        ClientEndpointHint leaderHint,
        String detail) {
    /** Validates response consistency and bounded operator-facing detail text. */
    public ClientWriteResponse {
        if (status == null
                || commandId == null
                || operationCount < 0
                || firstSequence < 0
                || lastSequence < 0
                || firstSequence > lastSequence && (firstSequence != 0 || lastSequence != 0)
                || detail == null
                || detail.length() > 4096) {
            throw new IllegalArgumentException("invalid client write response");
        }
        if (status == ClientStatus.OK && (operationCount <= 0 || firstSequence == 0 || lastSequence == 0)) {
            throw new IllegalArgumentException("applied write response requires sequence range");
        }
        if (status == ClientStatus.NOT_LEADER && leaderHint == null) {
            throw new IllegalArgumentException("leader redirect requires endpoint hint");
        }
        if (status != ClientStatus.NOT_LEADER && leaderHint != null) {
            throw new IllegalArgumentException("leader hint is only valid for redirects");
        }
    }
}
