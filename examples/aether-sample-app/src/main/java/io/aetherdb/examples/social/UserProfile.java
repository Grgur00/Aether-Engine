package io.aetherdb.examples.social;

import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.time.Instant;
import java.util.Objects;

/** A realistic social-network profile stored as several related Aether records. */
@AetherRecord(version = 1)
public record UserProfile(
        @AetherMaxLength(64) String id,
        @AetherMaxLength(64) String username,
        @AetherMaxLength(256) String displayName,
        @AetherMaxLength(320) String email,
        @AetherMaxLength(4096) String bio,
        @AetherMaxLength(256) String location,
        long followerCount,
        boolean verified,
        Instant createdAt) {
    public UserProfile {
        requireText(id, "id"); requireText(username, "username"); requireText(displayName, "displayName");
        requireText(email, "email"); Objects.requireNonNull(bio, "bio"); Objects.requireNonNull(location, "location");
        Objects.requireNonNull(createdAt, "createdAt");
        if (id.indexOf('/') >= 0) throw new IllegalArgumentException("profile ID must not contain '/'");
        if (followerCount < 0) throw new IllegalArgumentException("follower count must be non-negative");
    }
    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
    }
}
