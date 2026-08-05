package io.aetherdb.examples.social;

import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.time.Instant;
import java.util.Objects;

/** One post related to its author through {@link #authorId()}. */
@AetherRecord(version = 1)
public record SocialPost(
        @AetherMaxLength(64) String id,
        @AetherMaxLength(64) String authorId,
        @AetherMaxLength(8192) String content,
        Instant createdAt,
        Instant updatedAt) {
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
