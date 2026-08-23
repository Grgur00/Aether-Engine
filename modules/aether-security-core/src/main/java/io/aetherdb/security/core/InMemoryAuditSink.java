package io.aetherdb.security.core;

import io.aetherdb.security.api.AuditEvent;
import io.aetherdb.security.api.AuditSink;

import java.util.ArrayList;
import java.util.List;

/** Test and embedded-development audit sink retaining events in memory. */
public final class InMemoryAuditSink implements AuditSink {
    private final List<AuditEvent> events = new ArrayList<>();

    @Override
    public synchronized void record(AuditEvent event) {
        events.add(event);
    }

    public synchronized List<AuditEvent> events() {
        return List.copyOf(events);
    }
}
