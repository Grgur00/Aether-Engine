package io.aetherdb.examples.social;

import io.aetherdb.codec.annotation.AetherMaxLength;
import io.aetherdb.codec.annotation.AetherRecord;

/** A directed edge from one profile to another. */
@AetherRecord(version = 1)
public record FollowRelationship(
        @AetherMaxLength(64) String followerId,
        @AetherMaxLength(64) String followedId) {
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
