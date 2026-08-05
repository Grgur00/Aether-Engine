package io.aetherdb.examples.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.aetherdb.api.typed.TypedWriteResult;
import io.aetherdb.codec.generated.GeneratedCodecs;
import io.aetherdb.embedded.typed.AetherEmbedded;
import java.time.Instant;
import org.junit.jupiter.api.Test;

final class SocialNetworkRepositoryTest {
    @Test
    void profileCrudAndRelationalPostQueriesWorkTogether() {
        try (var database = AetherEmbedded.openInMemory()) {
            SocialNetworkRepository social = new SocialNetworkRepository(database);
            UserProfile ada = profile("usr-1", "ada", 0);
            UserProfile grace = profile("usr-2", "grace", 0);

            assertThat(social.createProfiles(ada, grace))
                    .isInstanceOfSatisfying(TypedWriteResult.Applied.class,
                            result -> assertThat(result.operationCount()).isEqualTo(2));
            assertThat(social.findProfile(ada.id())).contains(ada);

            UserProfile renamed = new UserProfile(
                    ada.id(), ada.username(), "Ada King", ada.email(), ada.bio(),
                    ada.location(), ada.followerCount(), true, ada.createdAt());
            social.updateProfile(renamed);
            assertThat(social.findProfile(ada.id())).contains(renamed);

            social.createPost(post("post-1", ada.id(), "First", "2025-01-01T10:00:00Z"));
            social.createPost(post("post-2", ada.id(), "Second", "2025-01-01T11:00:00Z"));
            social.follow(grace.id(), ada.id());

            assertThat(social.followersOf(ada.id())).extracting(UserProfile::id)
                    .containsExactly(grace.id());
            assertThat(social.feedFor(grace.id())).extracting(SocialPost::id)
                    .containsExactly("post-2", "post-1");
            assertThat(social.findProfile(ada.id()).orElseThrow().followerCount()).isEqualTo(1);

            social.updatePost("post-1", "Edited", Instant.parse("2025-01-02T10:00:00Z"));
            assertThat(social.findPost("post-1").orElseThrow().content()).isEqualTo("Edited");
            social.deletePost("post-2");
            assertThat(social.findPost("post-2")).isEmpty();

            assertThatThrownBy(() -> social.deleteProfile(ada.id()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("related posts and follows");
            social.deletePost("post-1");
            social.unfollow(grace.id(), ada.id());
            social.deleteProfile(ada.id());
            assertThat(social.findProfile(ada.id())).isEmpty();
        }
    }

    @Test
    void foreignKeysAndUniquenessAreEnforcedByTheRepository() {
        try (var database = AetherEmbedded.openInMemory()) {
            SocialNetworkRepository social = new SocialNetworkRepository(database);
            UserProfile ada = profile("usr-1", "ada", 0);
            social.createProfile(ada);

            assertThatThrownBy(() -> social.createProfile(ada))
                    .hasMessageContaining("already exists");
            assertThatThrownBy(() -> social.createPost(
                    post("post-1", "missing", "No author", "2025-01-01T10:00:00Z")))
                    .hasMessageContaining("author profile does not exist");
            assertThatThrownBy(() -> social.follow(ada.id(), "missing"))
                    .hasMessageContaining("followed profile does not exist");
        }
    }

    @Test
    void codecsRoundTripAndRejectUnknownVersions() {
        UserProfile profile = profile("usr-1", "ada", 0);
        SocialPost post = post("post-1", profile.id(), "Hello", "2025-01-01T10:00:00Z");
        var profileCodec = GeneratedCodecs.forRecord(UserProfile.class);
        var postCodec = GeneratedCodecs.forRecord(SocialPost.class);
        assertThat(profileCodec.decode(1, profileCodec.encode(profile)))
                .isEqualTo(profile);
        assertThat(postCodec.decode(1, postCodec.encode(post)))
                .isEqualTo(post);
        assertThatThrownBy(() -> postCodec.decode(2, new byte[0]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static UserProfile profile(String id, String username, long followers) {
        return new UserProfile(
                id, username, username.toUpperCase(), username + "@example.test",
                "A useful bio", "Zagreb, HR", followers, false,
                Instant.parse("2025-01-01T00:00:00Z"));
    }

    private static SocialPost post(String id, String authorId, String content, String timestamp) {
        Instant instant = Instant.parse(timestamp);
        return new SocialPost(id, authorId, content, instant, instant);
    }
}
