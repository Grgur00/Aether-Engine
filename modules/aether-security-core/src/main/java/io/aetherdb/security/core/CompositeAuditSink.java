package io.aetherdb.security.core;

import io.aetherdb.security.api.AuditEvent;
import io.aetherdb.security.api.AuditSink;
import io.aetherdb.security.api.AuditUnavailableException;

import java.util.List;
import java.util.Objects;

/** Audit sink that fails closed if any mandatory delegate fails. */
public final class CompositeAuditSink implements AuditSink {
    private final List<AuditSink> delegates;

    public CompositeAuditSink(List<AuditSink> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
        if (this.delegates.isEmpty()) throw new IllegalArgumentException("no audit sinks");
    }

    @Override
    public void record(AuditEvent event) throws AuditUnavailableException {
        AuditUnavailableException failure = null;
        for (AuditSink delegate : delegates) {
            try {
                delegate.record(event);
            } catch (AuditUnavailableException exception) {
                if (failure == null)
                    failure = new AuditUnavailableException("one or more audit sinks failed");
                failure.addSuppressed(exception);
            }
        }
        if (failure != null) throw failure;
    }
}
