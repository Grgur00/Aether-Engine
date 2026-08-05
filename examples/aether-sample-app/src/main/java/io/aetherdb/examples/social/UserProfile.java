package io.aetherdb.examples.social;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;
import java.time.Instant;
import java.util.Objects;

/** A realistic social-network profile stored as several related Aether records. */
@AetherRecord(schemaId = "28f98ef5-d249-4f64-9791-09267036396a", version = 1)
public record UserProfile(
        @AetherField(id = 16) @AetherMaxLength(64) String id,
        @AetherField(id = 17) @AetherMaxLength(64) String username,
        @AetherField(id = 18) @AetherMaxLength(256) String displayName,
        @AetherField(id = 19) @AetherMaxLength(320) String email,
        @AetherField(id = 20) @AetherMaxLength(4096) String bio,
        @AetherField(id = 21) @AetherMaxLength(256) String location,
        @AetherField(id = 22) long followerCount,
        @AetherField(id = 23) boolean verified,
        @AetherField(id = 24) Instant createdAt) {
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
