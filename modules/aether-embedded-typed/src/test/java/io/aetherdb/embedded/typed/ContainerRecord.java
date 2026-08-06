package io.aetherdb.embedded.typed;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxEntries;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Generated-codec fixture for bounded optional and canonical container fields. */
@AetherRecord(schemaId = "4614e650-9688-428b-af86-6cccab57f9ea", version = 1)
public record ContainerRecord(
        @AetherField(id = 16) @AetherMaxLength(512) Optional<String> alias,
        @AetherField(id = 17) @AetherMaxEntries(10) @AetherMaxLength(4096) List<Long> scores,
        @AetherField(id = 18) @AetherMaxEntries(10) @AetherMaxLength(4096) Set<String> tags,
        @AetherField(id = 19) @AetherMaxEntries(10) @AetherMaxLength(4096) Map<String, Integer> counters,
        @AetherField(id = 20) @AetherMaxEntries(10) @AetherMaxLength(4096) List<List<String>> matrix) {}
