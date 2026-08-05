package io.aetherdb.client.api;

import java.util.List;

public record ClientWriteRequest(long configurationVersion, List<ClientWriteOperation> operations) {
    public ClientWriteRequest { operations=List.copyOf(operations); if(configurationVersion<0||operations.isEmpty()||operations.size()>10_000) throw new IllegalArgumentException("invalid client write"); }
}
