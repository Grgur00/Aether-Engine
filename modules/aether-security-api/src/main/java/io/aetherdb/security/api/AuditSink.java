package io.aetherdb.security.api;

/** Receives mandatory security audit events. */
@FunctionalInterface
public interface AuditSink {
    void record(AuditEvent event) throws AuditUnavailableException;
}
