package io.aetherdb.format.catalog;

import java.util.Objects;

/** Cataloged immutable binary fixture used to pin a persisted format encoder. */
public record FormatGoldenFixture(
        String formatId,
        String fixtureName,
        int byteLength,
        String sha256Hex,
        String description) {
    public FormatGoldenFixture {
        if (formatId == null || !formatId.matches("aether\\.[a-z0-9_.-]+\\.v[0-9]+"))
            throw new IllegalArgumentException("invalid fixture format id");
        if (fixtureName == null || !fixtureName.matches("[a-z0-9][a-z0-9.-]*"))
            throw new IllegalArgumentException("invalid fixture name");
        if (byteLength <= 0) throw new IllegalArgumentException("fixture length must be positive");
        if (sha256Hex == null || !sha256Hex.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("invalid fixture sha256");
        if (description == null || description.isBlank())
            throw new IllegalArgumentException("blank fixture description");
        description = description.strip();
        Objects.requireNonNull(formatId, "formatId");
        Objects.requireNonNull(fixtureName, "fixtureName");
    }
}
