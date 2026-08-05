package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.time.Instant;
import java.util.UUID;

@AetherRecord(
        schemaId = "a0e988c2-74f0-4243-b44f-c395916e0a74",
        version = 1)
public record LedgerEntry(
        @AetherField(id = 16) UUID accountId,
        @AetherField(id = 17) long amountMinor,
        @AetherField(id = 18) @AetherMaxLength(3) String currency,
        @AetherField(id = 19) Instant bookedAt) {}
