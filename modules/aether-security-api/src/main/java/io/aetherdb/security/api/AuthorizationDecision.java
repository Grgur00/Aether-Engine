package io.aetherdb.security.api;

import java.util.Objects;

/** Authorization result with a stable reason code for audit and metrics. */
public record AuthorizationDecision(boolean allowed, String reasonCode) {
    public AuthorizationDecision {
        if (reasonCode == null || reasonCode.isBlank())
            throw new IllegalArgumentException("blank reason code");
    }

    public static AuthorizationDecision allow(String reasonCode) {
        return new AuthorizationDecision(true, Objects.requireNonNull(reasonCode));
    }

    public static AuthorizationDecision deny(String reasonCode) {
        return new AuthorizationDecision(false, Objects.requireNonNull(reasonCode));
    }
}
