package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

/** First released view of the schema-evolution integration fixture. */
@AetherRecord(schemaId = "579fa36c-9036-42e0-8f1e-39130e780be8", version = 1)
public record EvolvingProfileV1(
        @AetherField(id = 16) @AetherMaxLength(100) String name) {}
