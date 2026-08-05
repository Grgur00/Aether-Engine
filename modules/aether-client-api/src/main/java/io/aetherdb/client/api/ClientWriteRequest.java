package io.aetherdb.client.api;

import java.util.List;

/** Bounded ordered mutation batch submitted through the client protocol.
 * @param configurationVersion caller's cluster configuration version
 * @param operations one to ten thousand ordered mutations */
public record ClientWriteRequest(long configurationVersion, List<ClientWriteOperation> operations) {
    /** Copies operations and validates request bounds. */
    public ClientWriteRequest { operations=List.copyOf(operations); if(configurationVersion<0||operations.isEmpty()||operations.size()>10_000) throw new IllegalArgumentException("invalid client write"); }
}
