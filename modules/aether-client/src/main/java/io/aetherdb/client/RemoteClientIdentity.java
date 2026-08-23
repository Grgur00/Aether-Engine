package io.aetherdb.client;

/** Stable authenticated client identity used to partition pooled remote channels. */
public record RemoteClientIdentity(String principal) {
    /** Validates a bounded non-blank principal name. */
    public RemoteClientIdentity {
        if (principal == null
                || principal.isBlank()
                || principal.length() > 256
                || principal.chars().anyMatch(character -> character == 0 || Character.isISOControl(character))) {
            throw new IllegalArgumentException("invalid client identity");
        }
        principal = principal.strip();
    }
}
