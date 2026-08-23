package io.aetherdb.config;

/** Configuration source in increasing precedence order. */
public enum ConfigSource {
    FILE,
    ENVIRONMENT,
    OVERRIDE
}
