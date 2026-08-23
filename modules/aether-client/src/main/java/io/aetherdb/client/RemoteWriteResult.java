package io.aetherdb.client;

import io.aetherdb.client.api.ClientStatus;

import java.util.Arrays;
import java.util.UUID;

/** Terminal remote write result returned by the Java client facade. */
public record RemoteWriteResult(
        ClientStatus status,
        UUID commandId,
        int attempts,
        int operationCount,
        long firstSequence,
        long lastSequence,
        byte[] body,
        String detail) {
    /** Validates and defensively copies response data. */
    public RemoteWriteResult {
        if (status == null
                || commandId == null
                || attempts <= 0
                || operationCount < 0
                || firstSequence < 0
                || lastSequence < 0
                || firstSequence > lastSequence && (firstSequence != 0 || lastSequence != 0)
                || body == null
                || detail == null
                || detail.length() > 4096) {
            throw new IllegalArgumentException("invalid remote write result");
        }
        body = Arrays.copyOf(body, body.length);
    }

    /** Returns a defensive response body copy. */
    @Override
    public byte[] body() {
        return Arrays.copyOf(body, body.length);
    }
}
