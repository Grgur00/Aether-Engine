package io.aetherdb.examples.social;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.time.Instant;
import java.util.Objects;

/** One post related to its author through {@link #authorId()}. */
@AetherRecord(schemaId = "6553812e-0d82-4078-bfa2-5818d8fe670d", version = 1)
public record SocialPost(
        @AetherField(id = 16) @AetherMaxLength(64) String id,
        @AetherField(id = 17) @AetherMaxLength(64) String authorId,
        @AetherField(id = 18) @AetherMaxLength(8192) String content,
        @AetherField(id = 19) Instant createdAt,
        @AetherField(id = 20) Instant updatedAt) {
    public SocialPost {
        requireIdentifier(id, "id");
        requireIdentifier(authorId, "authorId");
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("content must not be blank");
        }
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(createdAt)) {
            throw new IllegalArgumentException("updatedAt must not precede createdAt");
        }
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no '/'");
        }
    }
}
