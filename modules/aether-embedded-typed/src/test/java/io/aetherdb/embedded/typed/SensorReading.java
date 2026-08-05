package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.time.Instant;

@AetherRecord(
        schemaId = "2df89037-10fb-42ed-b6da-a1025fa59444",
        version = 1)
public record SensorReading(
        @AetherField(id = 16) @AetherMaxLength(64) String sensorId,
        @AetherField(id = 17) Instant timestamp,
        @AetherField(id = 18) double temperature,
        @AetherField(id = 19) int sampleCount,
        @AetherField(id = 20) boolean calibrated) {}
