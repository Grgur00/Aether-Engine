package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/** Exercises every generated scalar added beyond the original sample subset. */
@AetherRecord(schemaId = "92ed246e-40b5-41a4-9640-b7923d77481b", version = 1)
public record ScalarMatrix(
        @AetherField(id = 16) byte byteValue,
        @AetherField(id = 17) short shortValue,
        @AetherField(id = 18) float floatValue,
        @AetherField(id = 19) char charValue,
        @AetherField(id = 20) LocalDate date,
        @AetherField(id = 21) LocalTime time,
        @AetherField(id = 22) LocalDateTime dateTime,
        @AetherField(id = 23) Duration duration,
        @AetherField(id = 24) @AetherMaxLength(64) BigInteger integer,
        @AetherField(id = 25) @AetherMaxLength(64) BigDecimal decimal,
        @AetherField(id = 26) @AetherMaxLength(32) byte[] bytes) {}
