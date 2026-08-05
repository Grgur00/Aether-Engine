# Aether Social CRUD Sample

This sample models a small social service using three related typed Aether collections:

| Collection | Key | Value | Relationship |
|---|---|---|---|
| `social-profiles` | profile ID | `UserProfile` | Parent for posts and follows |
| `social-posts` | post ID | `SocialPost` | `authorId` refers to a profile |
| `social-follows` | `followerId/followedId` | `FollowRelationship` | Directed profile-to-profile edge |

Run it from the repository root:

```shell
./gradlew :examples:aether-sample-app:run
```

With no argument it uses in-memory storage. To persist the showcase data across runs:

```shell
./gradlew :examples:aether-sample-app:run --args="./data/social-network"
```

The application demonstrates:

- create, point-read, update, scan, and delete operations;
- an atomic batch that creates multiple profiles;
- foreign-key validation before creating posts and relationships;
- an atomic follow/unfollow batch that changes both the edge and follower count;
- one-to-many profile-to-post queries;
- a relational-style joined feed across follows, profiles, and posts;
- safe deletion that rejects a profile while related records still exist; and
- compile-time generated, versioned codecs for profiles, posts, and relationships.

The domain records declare only durable schema metadata:

```java
@AetherRecord(schemaId = "6553812e-0d82-4078-bfa2-5818d8fe670d", version = 1)
public record SocialPost(
        @AetherField(id = 16) @AetherMaxLength(64) String id,
        @AetherField(id = 17) @AetherMaxLength(64) String authorId,
        @AetherField(id = 18) @AetherMaxLength(8192) String content,
        @AetherField(id = 19) Instant createdAt,
        @AetherField(id = 20) Instant updatedAt) {}
```

No handwritten `ValueCodec` exists in the application. The annotation processor emits the canonical AER1 codec, descriptor, fingerprint, provider, and runtime registration.

The engine stores key/value records rather than executing SQL joins. The repository implements relationships and joins explicitly using typed scans and point reads, which makes the storage behavior visible in a compact example.

Only one writable process may open a persistent database directory at a time.

Defining a collection also persists its generated schema descriptor in Aether's
internal collection catalog. After running the persistent sample once, the
standalone Workbench can therefore show universal forms with field names such
as `authorId`, `content`, and `createdAt` without depending on this application JAR.
