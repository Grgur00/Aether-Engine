package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

/** Independently versioned nested schema fixture. */
@AetherRecord(schemaId = "36f5a8b2-2c39-4db8-b1b4-e82a4c1e20bc", version = 1)
public record PostalAddress(
        @AetherField(id = 16) @AetherMaxLength(200) String city,
        @AetherField(id = 17) @AetherMaxLength(20) String postalCode) {}
