package io.aetherdb.examples.social;

import io.aetherdb.codec.annotation.AetherField;
import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

/** A directed edge from one profile to another. */
@AetherRecord(schemaId = "b160834c-3a61-4811-830e-40d694abbcd6", version = 1)
public record FollowRelationship(
        @AetherField(id = 16) @AetherMaxLength(64) String followerId,
        @AetherField(id = 17) @AetherMaxLength(64) String followedId) {
    public FollowRelationship {
        requireIdentifier(followerId, "followerId");
        requireIdentifier(followedId, "followedId");
        if (followerId.equals(followedId)) {
            throw new IllegalArgumentException("cannot follow yourself");
        }
    }

    public boolean includes(String profileId) {
        return followerId.equals(profileId) || followedId.equals(profileId);
    }

    private static void requireIdentifier(String value, String name) {
        if (value == null || value.isBlank() || value.indexOf('/') >= 0) {
            throw new IllegalArgumentException(name + " must be non-blank and contain no '/'");
        }
    }
}
