package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

import java.time.Instant;
import java.util.UUID;

@AetherRecord(version = 1)
public record LedgerEntry(
        UUID accountId, long amountMinor, @AetherMaxLength(3) String currency, Instant bookedAt) {}
