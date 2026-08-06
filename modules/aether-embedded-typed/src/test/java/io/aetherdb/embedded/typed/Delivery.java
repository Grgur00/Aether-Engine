package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

/** Parent fixture combining a stable enum and an independently identified nested record. */
@AetherRecord(schemaId = "caa1cb01-5d65-46ea-95e5-2d1f28c6e88f", version = 1)
public record Delivery(
        @AetherField(id = 16) DeliveryStatus status,
        @AetherField(id = 17) @AetherMaxLength(2048) PostalAddress destination) {}
