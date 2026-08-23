package io.aetherdb.release;

/** Chapter 38 production-readiness evidence rows. */
public enum CertificationArea {
    CORRECTNESS,
    DURABILITY,
    RECOVERY,
    RAFT,
    SECURITY,
    PERFORMANCE,
    RESOURCE_LIMITS,
    COMPATIBILITY,
    BACKUP_RESTORE,
    KUBERNETES,
    OBSERVABILITY,
    RELEASE_PROVENANCE,
    DOCUMENTATION
}
