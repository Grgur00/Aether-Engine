package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherRecord;

@AetherRecord(version = 1)
public record Todo(String id, String title, boolean completed) {}
