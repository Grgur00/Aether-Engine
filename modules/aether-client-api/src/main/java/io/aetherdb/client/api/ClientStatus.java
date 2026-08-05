package io.aetherdb.client.api;

/** Stable application-level outcome returned by client RPC operations. */
public enum ClientStatus {
    /** Operation succeeded. */ OK,
    /** Contacted node is not the leader. */ NOT_LEADER,
    /** Leader has not completed readiness checks. */ LEADER_NOT_READY,
    /** Cluster currently knows no leader. */ NO_LEADER_KNOWN,
    /** Request arguments are invalid. */ INVALID_ARGUMENT,
    /** A bounded server resource is exhausted. */ RESOURCE_EXHAUSTED,
    /** Request deadline elapsed. */ DEADLINE_EXCEEDED,
    /** Final write outcome cannot be determined. */ INDETERMINATE,
    /** Requested key or resource is absent. */ NOT_FOUND,
    /** Client used an incompatible configuration version. */ CONFIGURATION_MISMATCH,
    /** Command identity was reused with different content. */ COMMAND_ID_CONFLICT,
    /** Local storage engine failed. */ ENGINE_FAILED,
    /** Requested read consistency mode is disabled. */ READ_MODE_DISABLED,
    /** Scan identity is unknown. */ SCAN_NOT_FOUND,
    /** Scan lease expired. */ SCAN_EXPIRED,
    /** Scan page token does not match server state. */ SCAN_PAGE_MISMATCH,
    /** Response would exceed the configured bound. */ RESULT_TOO_LARGE,
    /** Durable or replicated data failed integrity validation. */ DATA_LOSS,
    /** Caller is not authenticated. */ UNAUTHENTICATED,
    /** Caller lacks permission. */ PERMISSION_DENIED
}
