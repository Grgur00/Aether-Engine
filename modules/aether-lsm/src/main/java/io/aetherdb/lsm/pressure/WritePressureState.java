package io.aetherdb.lsm.pressure;

/** Write-admission state derived from storage pressure. */
public enum WritePressureState {
    /** Admit writes without artificial delay. */ NORMAL,
    /** Admit writes after a bounded delay. */ SLOWDOWN,
    /** Reject or wait until a recoverable pressure source clears. */ STOPPED_RETRYABLE,
    /** Reject because a background subsystem failed. */ FAILED
}
