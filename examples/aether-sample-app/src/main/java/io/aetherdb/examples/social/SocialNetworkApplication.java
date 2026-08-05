package io.aetherdb.examples.social;

import io.aetherdb.api.typed.TypedAetherDatabase;
import io.aetherdb.embedded.typed.AetherEmbedded;
import java.nio.file.Path;
import java.time.Instant;

/** Runnable CRUD and relational-style showcase using Aether's typed embedded API. */
public final class SocialNetworkApplication {
    private SocialNetworkApplication() {}

    public static void main(String[] arguments) {
        try (TypedAetherDatabase database = arguments.length == 0
                ? AetherEmbedded.openInMemory()
                : AetherEmbedded.open(Path.of(arguments[0]))) {
            SocialNetworkRepository social = new SocialNetworkRepository(database);
            seedIfEmpty(social);

            UserProfile ada = social.findProfile("usr-1001").orElseThrow();
            social.updateProfile(new UserProfile(
                    ada.id(), ada.username(), ada.displayName(), ada.email(),
                    "Mathematics, analytical engines, and the first algorithm.",
                    ada.location(), ada.followerCount(), true, ada.createdAt()));

            social.updatePost(
                    "post-2001",
                    "The Analytical Engine weaves algebraic patterns.",
                    Instant.parse("2025-03-01T10:30:00Z"));

            SocialPost disposable = new SocialPost(
                    "post-draft", "usr-1002", "Draft to be deleted",
                    Instant.parse("2025-03-02T09:00:00Z"),
                    Instant.parse("2025-03-02T09:00:00Z"));
            if (social.findPost(disposable.id()).isEmpty()) social.createPost(disposable);
            social.deletePost(disposable.id());

            System.out.println("Profiles (READ after CREATE/UPDATE):");
            social.findProfiles().forEach(profile -> System.out.printf(
                    "  @%s — %s (%d relational follower)%n",
                    profile.username(), profile.displayName(), profile.followerCount()));

            System.out.println("\nAda's posts (profile -> posts relationship):");
            social.postsByAuthor("usr-1001")
                    .forEach(post -> System.out.println("  " + post.content()));

            System.out.println("\nGrace's joined feed (follows -> profiles -> posts):");
            social.feedFor("usr-1002").forEach(post -> {
                UserProfile author = social.findProfile(post.authorId()).orElseThrow();
                System.out.println("  @" + author.username() + ": " + post.content());
            });

            System.out.println("\nDeleted draft exists: "
                    + social.findPost("post-draft").isPresent());
        }
    }

    private static void seedIfEmpty(SocialNetworkRepository social) {
        if (!social.findProfiles().isEmpty()) return;

        UserProfile ada = profile(
                "usr-1001", "ada.codes", "Ada Lovelace", "ada@example.test",
                "London, UK", true, "2024-01-15T10:30:00Z");
        UserProfile grace = profile(
                "usr-1002", "grace.debugs", "Grace Hopper", "grace@example.test",
                "New York, US", true, "2024-02-20T14:15:00Z");
        social.createProfiles(ada, grace);

        social.createPost(new SocialPost(
                "post-2001", ada.id(), "Notes on the Analytical Engine",
                Instant.parse("2025-03-01T10:00:00Z"),
                Instant.parse("2025-03-01T10:00:00Z")));
        social.createPost(new SocialPost(
                "post-2002", ada.id(), "Poetical science meets machinery.",
                Instant.parse("2025-03-01T11:00:00Z"),
                Instant.parse("2025-03-01T11:00:00Z")));
        social.follow(grace.id(), ada.id());
    }

    private static UserProfile profile(
            String id,
            String username,
            String displayName,
            String email,
            String location,
            boolean verified,
            String createdAt) {
        return new UserProfile(
                id, username, displayName, email, "Computing pioneer", location,
                0, verified, Instant.parse(createdAt));
    }
}
