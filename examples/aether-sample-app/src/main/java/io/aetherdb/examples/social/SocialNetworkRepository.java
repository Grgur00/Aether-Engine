package io.aetherdb.examples.social;

import io.aetherdb.api.typed.CollectionId;
import io.aetherdb.api.typed.TypedAetherCollection;
import io.aetherdb.api.typed.TypedAetherDatabase;
import io.aetherdb.api.typed.TypedKeyValue;
import io.aetherdb.api.typed.TypedWriteBatch;
import io.aetherdb.api.typed.TypedWriteResult;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** CRUD repository spanning profiles, posts, and directed follow relationships. */
public final class SocialNetworkRepository {
    public static final CollectionId PROFILE_COLLECTION =
            CollectionId.of("7a39f7c1-d995-4b08-a866-4526e175e94f");
    public static final CollectionId POST_COLLECTION =
            CollectionId.of("5cc5f1bb-370f-4120-889a-9fc70ad8d787");
    public static final CollectionId FOLLOW_COLLECTION =
            CollectionId.of("6b959abb-3c7c-458d-8465-e04fbf469249");

    private final TypedAetherDatabase database;
    private final TypedAetherCollection<String, UserProfile> profiles;
    private final TypedAetherCollection<String, SocialPost> posts;
    private final TypedAetherCollection<String, FollowRelationship> follows;

    public SocialNetworkRepository(TypedAetherDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        profiles = database.defineCollection(
                PROFILE_COLLECTION, "social-profiles", String.class, UserProfile.class);
        posts = database.defineCollection(
                POST_COLLECTION, "social-posts", String.class, SocialPost.class);
        follows = database.defineCollection(
                FOLLOW_COLLECTION, "social-follows", String.class, FollowRelationship.class);
    }

    public TypedWriteResult createProfile(UserProfile profile) {
        Objects.requireNonNull(profile, "profile");
        requireAbsent(findProfile(profile.id()), "profile", profile.id());
        return profiles.put(profile.id(), profile);
    }

    public TypedWriteResult createProfiles(UserProfile... values) {
        TypedWriteBatch batch = database.batch();
        for (UserProfile profile : values) {
            Objects.requireNonNull(profile, "profile");
            requireAbsent(findProfile(profile.id()), "profile", profile.id());
            batch.put(profiles, profile.id(), profile);
        }
        return database.write(batch);
    }

    public Optional<UserProfile> findProfile(String id) { return profiles.get(id).value(); }

    public List<UserProfile> findProfiles() {
        return profiles.scanAll().stream().map(TypedKeyValue::value).toList();
    }

    public TypedWriteResult updateProfile(UserProfile profile) {
        requirePresent(findProfile(profile.id()), "profile", profile.id());
        return profiles.put(profile.id(), profile);
    }

    public TypedWriteResult deleteProfile(String id) {
        requirePresent(findProfile(id), "profile", id);
        if (!postsByAuthor(id).isEmpty() || relationships().stream()
                .anyMatch(relationship -> relationship.includes(id))) {
            throw new IllegalStateException("delete related posts and follows before profile " + id);
        }
        return profiles.delete(id);
    }

    public TypedWriteResult createPost(SocialPost post) {
        requirePresent(findProfile(post.authorId()), "author profile", post.authorId());
        requireAbsent(findPost(post.id()), "post", post.id());
        return posts.put(post.id(), post);
    }

    public Optional<SocialPost> findPost(String id) { return posts.get(id).value(); }

    public List<SocialPost> postsByAuthor(String authorId) {
        return posts.scanAll().stream()
                .map(TypedKeyValue::value)
                .filter(post -> post.authorId().equals(authorId))
                .sorted(Comparator.comparing(SocialPost::createdAt))
                .toList();
    }

    public TypedWriteResult updatePost(String id, String content, Instant updatedAt) {
        SocialPost current = requirePresent(findPost(id), "post", id);
        return posts.put(id, new SocialPost(
                current.id(), current.authorId(), content, current.createdAt(), updatedAt));
    }

    public TypedWriteResult deletePost(String id) {
        requirePresent(findPost(id), "post", id);
        return posts.delete(id);
    }

    public TypedWriteResult follow(String followerId, String followedId) {
        FollowRelationship relationship = new FollowRelationship(followerId, followedId);
        requirePresent(findProfile(followerId), "follower profile", followerId);
        UserProfile followed = requirePresent(findProfile(followedId), "followed profile", followedId);
        String key = relationshipKey(followerId, followedId);
        requireAbsent(follows.get(key).value(), "follow relationship", key);

        TypedWriteBatch batch = database.batch()
                .put(follows, key, relationship)
                .put(profiles, followedId, withFollowerCount(followed, followed.followerCount() + 1));
        return database.write(batch);
    }

    public TypedWriteResult unfollow(String followerId, String followedId) {
        String key = relationshipKey(followerId, followedId);
        requirePresent(follows.get(key).value(), "follow relationship", key);
        UserProfile followed = requirePresent(findProfile(followedId), "followed profile", followedId);
        TypedWriteBatch batch = database.batch()
                .delete(follows, key)
                .put(profiles, followedId,
                        withFollowerCount(followed, Math.max(0, followed.followerCount() - 1)));
        return database.write(batch);
    }

    public List<UserProfile> followersOf(String profileId) {
        return relationships().stream()
                .filter(relationship -> relationship.followedId().equals(profileId))
                .map(relationship -> findProfile(relationship.followerId()).orElseThrow())
                .toList();
    }

    /** Relational-style join: followed profiles -> their posts. */
    public List<SocialPost> feedFor(String profileId) {
        Set<String> followedIds = relationships().stream()
                .filter(relationship -> relationship.followerId().equals(profileId))
                .map(FollowRelationship::followedId)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return posts.scanAll().stream()
                .map(TypedKeyValue::value)
                .filter(post -> followedIds.contains(post.authorId()))
                .sorted(Comparator.comparing(SocialPost::createdAt).reversed())
                .toList();
    }

    private List<FollowRelationship> relationships() {
        return follows.scanAll().stream()
                .map(TypedKeyValue::value)
                .toList();
    }

    private static UserProfile withFollowerCount(UserProfile profile, long followerCount) {
        return new UserProfile(
                profile.id(), profile.username(), profile.displayName(), profile.email(),
                profile.bio(), profile.location(), followerCount, profile.verified(),
                profile.createdAt());
    }

    private static String relationshipKey(String followerId, String followedId) {
        return followerId + "/" + followedId;
    }

    private static void requireAbsent(Optional<?> value, String type, String id) {
        if (value.isPresent()) throw new IllegalStateException(type + " already exists: " + id);
    }

    private static <T> T requirePresent(Optional<T> value, String type, String id) {
        return value.orElseThrow(() -> new IllegalStateException(type + " does not exist: " + id));
    }
}
