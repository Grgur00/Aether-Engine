package io.aetherdb.client;

import io.aetherdb.rpc.api.RpcEndpoint;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

/** Bounded endpoint resolver with stable order and optional leader preference. */
public final class RemoteEndpointResolver {
    private final int maximumEndpoints;
    private final LinkedHashSet<RpcEndpoint> endpoints = new LinkedHashSet<>();
    private RpcEndpoint preferredLeader;

    public RemoteEndpointResolver(List<RpcEndpoint> initialEndpoints, int maximumEndpoints) {
        if (maximumEndpoints <= 0) throw new IllegalArgumentException("maximum endpoints must be positive");
        this.maximumEndpoints = maximumEndpoints;
        replaceEndpoints(initialEndpoints);
    }

    public synchronized void replaceEndpoints(List<RpcEndpoint> replacements) {
        Objects.requireNonNull(replacements, "replacements");
        LinkedHashSet<RpcEndpoint> copied = new LinkedHashSet<>(replacements);
        if (copied.isEmpty()) throw new IllegalArgumentException("at least one endpoint is required");
        if (copied.size() > maximumEndpoints)
            throw new IllegalArgumentException("endpoint set exceeds configured maximum");
        endpoints.clear();
        endpoints.addAll(copied);
        if (preferredLeader != null && !endpoints.contains(preferredLeader)) preferredLeader = null;
    }

    public synchronized void observeLeader(RpcEndpoint leader) {
        Objects.requireNonNull(leader, "leader");
        if (!endpoints.contains(leader)) {
            if (endpoints.size() == maximumEndpoints)
                throw new IllegalArgumentException("leader endpoint exceeds configured maximum");
            endpoints.add(leader);
        }
        preferredLeader = leader;
    }

    public synchronized void clearLeader(RpcEndpoint leader) {
        if (preferredLeader != null && preferredLeader.equals(leader)) preferredLeader = null;
    }

    public synchronized List<RpcEndpoint> candidates() {
        ArrayList<RpcEndpoint> result = new ArrayList<>();
        if (preferredLeader != null) result.add(preferredLeader);
        for (RpcEndpoint endpoint : endpoints)
            if (!endpoint.equals(preferredLeader)) result.add(endpoint);
        return List.copyOf(result);
    }
}
