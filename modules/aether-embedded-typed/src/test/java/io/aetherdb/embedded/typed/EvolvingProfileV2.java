package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

import java.time.Instant;
import java.util.Optional;

/** Compatible second view: the stable field is renamed and one optional field is added. */
@AetherRecord(schemaId = "579fa36c-9036-42e0-8f1e-39130e780be8", version = 2)
public record EvolvingProfileV2(
        @AetherField(id = 16, previousName = "name") @AetherMaxLength(100) String displayName,
        @AetherField(id = 17, optional = true) Optional<Instant> updatedAt) {}
